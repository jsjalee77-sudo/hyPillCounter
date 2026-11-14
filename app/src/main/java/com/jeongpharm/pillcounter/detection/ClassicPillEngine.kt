package com.jeongpharm.pillcounter.detection

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 분석 결과. [warning] 이 있으면 사용자에게 촬영 환경 안내를 보여준다. */
data class ClassicResult(val pills: List<Pill>, val warning: String? = null)

/**
 * 학습 모델 없이 동작하는 고전 영상처리 기반 알약 계수 엔진 (순수 Kotlin, 단위 테스트 가능).
 *
 * 1. 3x3 블러 → 트레이 배경색 추정(테두리 가중 최빈색) → 격자별 국소 배경(조명 불균일·그림자 보정)
 * 2. 국소 배경과의 색 거리 → Otsu 이진화(감도 반영)
 * 3. 작은 구멍(빛 반사) 메우기 → 모폴로지 열림(얇은 접촉부 분리)
 * 4. 연결 요소 → 알약이 아닌 것 제거 (화면 가장자리에 걸친 것, 가늘고 긴 선, 너무 크거나 불규칙한 덩어리)
 * 5. 색상별 그룹 → 오목한(붙어 있는) 덩어리만 면적비로 개수 산출 후 k-means 분리.
 *    볼록한 덩어리는 크기가 커도 1알로 본다 (크기가 다른 알약이 섞여도 과다 계수하지 않도록).
 */
object ClassicPillEngine {

    /** 배경과 비슷한 픽셀 비율이 이보다 낮으면 단색 트레이 위가 아니라고 판단 */
    private const val MIN_BG_COVERAGE = 0.30
    /** 볼록도(면적/볼록껍질 면적)가 이보다 낮으면 여러 알이 붙은 덩어리로 본다 */
    private const val SOLID_SINGLE = 0.92
    /** 장축/단축 비가 이보다 크면 선(트레이 테두리, 그림자 경계 등)으로 보고 제외 */
    private const val MAX_ELONGATION = 4.5

    fun detect(px: IntArray, w: Int, h: Int, sensitivity: Float = 0.5f): List<Pill> =
        analyze(px, w, h, sensitivity).pills

    fun analyze(px: IntArray, w: Int, h: Int, sensitivity: Float = 0.5f): ClassicResult {
        val n = w * h
        if (w < 8 || h < 8 || px.size < n) return ClassicResult(emptyList())
        val s = sensitivity.coerceIn(0f, 1f)

        val r0 = IntArray(n)
        val g0 = IntArray(n)
        val b0 = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]
            r0[i] = (c shr 16) and 0xFF
            g0[i] = (c shr 8) and 0xFF
            b0[i] = c and 0xFF
        }
        val r = boxBlur3(r0, w, h)
        val g = boxBlur3(g0, w, h)
        val b = boxBlur3(b0, w, h)

        val bg = estimateBackground(r, g, b, w, h)
        val local = localBackground(r, g, b, w, h, bg)
        val dist = IntArray(n)
        val hist = IntArray(256)
        var bgLike = 0
        for (i in 0 until n) {
            val dr = r[i] - local[3 * i]
            val dg = g[i] - local[3 * i + 1]
            val db = b[i] - local[3 * i + 2]
            val d = min(255, sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt())
            dist[i] = d
            hist[d]++
            if (d < 30) bgLike++
        }
        if (bgLike < n * MIN_BG_COVERAGE) {
            return ClassicResult(emptyList(), "배경이 고르지 않습니다. 단색 트레이를 가이드 박스에 꽉 채워 촬영해 주세요")
        }
        // 배경 잡음 기반 기준값 (Otsu 는 진한 알약에 끌려 연한 알약을 놓치므로 사용하지 않음)
        var acc = 0
        var median = 0
        while (median < 255 && acc + hist[median] < n / 2) { acc += hist[median]; median++ }
        val minT = 14f + (1f - s) * 36f
        val t = (max(minT, median * 2.5f + 8f) * (1.25f - 0.5f * s)).roundToInt().coerceIn(8, 220)

        var mask = BooleanArray(n) { dist[it] >= t }
        fillSmallHoles(mask, w, h, maxHole = max(16, (n * 0.02).toInt())) // 반사광·투명 캡슐 내부
        val iters = max(1, (max(w, h) / 400f).roundToInt())
        mask = dilate(erode(mask, w, h, iters), w, h, iters)

        val labels = IntArray(n)
        val count = labelComponents(mask, w, h, true, labels)
        if (count == 0) return ClassicResult(emptyList())
        val comps = Array(count + 1) { Comp(it) }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val l = labels[i]
                if (l != 0) comps[l].add(x, y, r0[i], g0[i], b0[i])
            }
        }
        val minArea = max(12, n / 25000)
        val valid = (1..count).map { comps[it] }.filter { c ->
            if (c.area < minArea || c.area > n / 4) return@filter false
            // 가이드 박스 밖으로 이어지는 물체(트레이 테두리, 손, 배경)는 제외
            if (c.minX == 0 || c.minY == 0 || c.maxX == w - 1 || c.maxY == h - 1) return@filter false
            c.solidity = solidity(c, labels, w)
            c.elongation() <= MAX_ELONGATION && c.solidity >= 0.45
        }
        // 화면 대비 너무 작은 것은 바탕 무늬(대리석 점, 먼지)로 본다. 알약은 화면의 0.03% 이상
        val minPill = max(minArea, (n * 0.0003).toInt())
        val sized = valid.filter { it.area >= minPill }
        // 그림자 제거: 배경과 색조가 같고 더 어두우며, 다른 덩어리 바로 옆에 붙어 있는 것
        val shadowLike = sized.filter { isShadowLike(it, local, w) }.toSet()
        val kept = sized.filter { c ->
            c !in shadowLike || sized.none { o -> o !== c && o !in shadowLike && near(c, o, 4) }
        }
        if (kept.isEmpty()) return ClassicResult(emptyList())

        // 색상 기반 그룹화 (종류별로 1알 면적이 다를 수 있으므로)
        val groups = mutableListOf<MutableList<Comp>>()
        val means = mutableListOf<FloatArray>()
        for (c in kept.sortedByDescending { it.area }) {
            val cr = c.meanR.toFloat()
            val cg = c.meanG.toFloat()
            val cb = c.meanB.toFloat()
            var best = -1
            var bestD = 48f
            for (gi in groups.indices) {
                val m = means[gi]
                val d = sqrt((cr - m[0]) * (cr - m[0]) + (cg - m[1]) * (cg - m[1]) + (cb - m[2]) * (cb - m[2]))
                if (d < bestD) {
                    best = gi
                    bestD = d
                }
            }
            if (best < 0) {
                groups += mutableListOf(c)
                means += floatArrayOf(cr, cg, cb)
            } else {
                val grp = groups[best]
                grp += c
                val m = means[best]
                m[0] += (cr - m[0]) / grp.size
                m[1] += (cg - m[1]) / grp.size
                m[2] += (cb - m[2]) / grp.size
            }
        }

        // 전체 기준 1알 면적 (볼록한 덩어리의 중앙값). 그룹 추정이 파편 때문에 너무 작아지는 것을 막는다
        val solidAreas = kept.filter { it.solidity >= SOLID_SINGLE }.map { it.area }.sorted()
        val globalUnit = (solidAreas.ifEmpty { kept.map { it.area }.sorted() }).let { it[it.size / 2] }.toDouble()

        val pills = ArrayList<Pill>()
        for (grp in groups) {
            // 1알 면적은 볼록한(단독일 가능성이 높은) 덩어리로 추정
            val base = grp.filter { it.solidity >= SOLID_SINGLE && it.area >= globalUnit * 0.15 }.ifEmpty { grp }
            val unit = max(estimateUnitArea(IntArray(base.size) { base[it].area }), globalUnit * 0.4)
            for (c in grp) {
                if (c.area < globalUnit * 0.04) continue // 큰 알약 옆의 작은 파편
                val ratio = c.area / unit
                // 잘록한 목으로 나뉘는 중심부 개수 = 붙어 있는 알약 수.
                // 그림자·반사로 모양이 약간 오목한 것만으로는 나누지 않는다.
                val cores = if (c.solidity < SOLID_SINGLE) countCores(c, labels, w) else 1
                val k = when {
                    cores >= 2 -> cores
                    c.solidity < 0.8 && ratio >= 2.5 -> ratio.roundToInt() // 목이 안 보이는 큰 무더기
                    else -> 1
                }.coerceIn(1, 60)
                if (k == 1) pills += c.toPill(w, h)
                else pills += splitComponent(c, k, labels, w, r0, g0, b0, h)
            }
        }
        return ClassicResult(pills)
    }

    /**
     * 그림자처럼 보이는가: 그 자리 배경보다 15~60% 어둡고 색조(정규화 RGB)는 배경과 거의 같다.
     * 검은·진한 알약은 훨씬 어둡거나 색조가 달라서 해당하지 않는다.
     */
    private fun isShadowLike(c: Comp, local: IntArray, w: Int): Boolean {
        val cx = (c.sx / c.area).toInt()
        val cy = (c.sy / c.area).toInt()
        val o = 3 * (cy * w + cx)
        val br = local[o].toDouble(); val bgG = local[o + 1].toDouble(); val bb = local[o + 2].toDouble()
        val lb = br + bgG + bb
        val lc = (c.meanR + c.meanG + c.meanB).toDouble()
        if (lb <= 0 || lc <= 0) return false
        val ratio = lc / lb
        if (ratio !in 0.40..0.85) return false
        val dr = c.meanR / lc - br / lb
        val dg = c.meanG / lc - bgG / lb
        val db = c.meanB / lc - bb / lb
        return sqrt(dr * dr + dg * dg + db * db) < 0.04
    }

    /** 두 덩어리의 경계 상자가 gap 픽셀 이내로 붙어 있는가 */
    private fun near(a: Comp, b: Comp, gap: Int): Boolean =
        a.minX <= b.maxX + gap && b.minX <= a.maxX + gap && a.minY <= b.maxY + gap && b.minY <= a.maxY + gap

    /** 덩어리 면적 목록에서 "알약 1개" 면적을 추정한다. */
    internal fun estimateUnitArea(areas: IntArray): Double {
        val sorted = areas.sorted()
        if (sorted.isEmpty()) return 1.0
        if (sorted.size < 3) {
            val a = sorted.first()
            val b = sorted.last()
            return if (a < 0.3 * b) b.toDouble() else a.toDouble()
        }
        val m = sorted[sorted.size / 2].toDouble()
        var best = m
        var bestScore = Double.MAX_VALUE
        for (step in 0..60) {
            val u = m * (0.40 + step * 0.0125)
            var err = 0.0
            var singles = 0
            for (a in sorted) {
                val q = a / u
                if (q < 0.5) {
                    err += 0.25
                    continue
                }
                val k = q.roundToInt()
                val res = (q - k) / sqrt(k.toDouble())
                err += res * res
                if (k == 1) singles++
            }
            var score = err / sorted.size
            if (singles < 0.2 * sorted.size) score += 0.05
            if (score < bestScore - 1e-9) {
                bestScore = score
                best = u
            }
        }
        return best
    }

    /**
     * 덩어리를 안쪽으로 깎아(거리 변환) 남는 중심부 개수를 센다.
     * 깎는 두께 = 단축 반지름의 55%. 붙은 두 알 사이의 좁은 목은 끊어지고, 단독 알약은 하나로 남는다.
     */
    private fun countCores(c: Comp, labels: IntArray, w: Int): Int {
        val bw = c.maxX - c.minX + 3
        val bh = c.maxY - c.minY + 3
        val inf = Int.MAX_VALUE / 4
        val d = IntArray(bw * bh) { 0 }
        for (y in 1 until bh - 1) for (x in 1 until bw - 1) {
            if (labels[(c.minY + y - 1) * w + (c.minX + x - 1)] == c.id) d[y * bw + x] = inf
        }
        // 3-4 챔퍼 거리 변환 (값/3 ≈ 픽셀 거리)
        for (y in 1 until bh - 1) for (x in 1 until bw - 1) {
            val i = y * bw + x
            if (d[i] == 0) continue
            d[i] = minOf(d[i], d[i - 1] + 3, d[i - bw] + 3, minOf(d[i - bw - 1] + 4, d[i - bw + 1] + 4))
        }
        for (y in bh - 2 downTo 1) for (x in bw - 2 downTo 1) {
            val i = y * bw + x
            if (d[i] == 0) continue
            d[i] = minOf(d[i], d[i + 1] + 3, d[i + bw] + 3, minOf(d[i + bw + 1] + 4, d[i + bw - 1] + 4))
        }
        // 단축 반지름 (2차 모멘트: 반지름 b 인 원의 분산 = b²/4)
        val a = c.area.toDouble()
        val mx = c.sx / a
        val my = c.sy / a
        val vx = c.sxx / a - mx * mx
        val vy = c.syy / a - my * my
        val cxy = c.sxy / a - mx * my
        val tr = vx + vy
        val l2 = max(0.0, tr / 2 - sqrt(max(0.0, tr * tr / 4 - (vx * vy - cxy * cxy))))
        val minorR = 2 * sqrt(l2)
        val th = max(2.0, 0.55 * minorR) * 3
        val core = BooleanArray(bw * bh) { d[it] > th }
        val lab = IntArray(bw * bh)
        val k = labelComponents(core, bw, bh, true, lab)
        if (k <= 1) return k.coerceAtLeast(1)
        val size = IntArray(k + 1)
        for (l in lab) if (l > 0) size[l]++
        val largest = size.max()
        return (1..k).count { size[it] >= max(4, largest / 8) }.coerceAtLeast(1)
    }

    /** 덩어리의 볼록도 = 픽셀 수 / 볼록껍질 면적. 단독 알약 ≈ 0.95 이상, 붙은 알약 ≈ 0.6~0.9 */
    private fun solidity(c: Comp, labels: IntArray, w: Int): Double {
        val pts = ArrayList<Long>()
        for (y in c.minY..c.maxY) {
            val row = y * w
            var lo = -1
            var hi = -1
            for (x in c.minX..c.maxX) if (labels[row + x] == c.id) {
                if (lo < 0) lo = x
                hi = x
            }
            if (lo < 0) continue
            // 픽셀 모서리 좌표로 껍질을 만든다
            pts += pack(lo, y); pts += pack(lo, y + 1); pts += pack(hi + 1, y); pts += pack(hi + 1, y + 1)
        }
        val hull = convexHull(pts)
        if (hull.size < 3) return 1.0
        var a2 = 0L
        for (i in hull.indices) {
            val p = hull[i]
            val q = hull[(i + 1) % hull.size]
            a2 += ux(p).toLong() * uy(q) - ux(q).toLong() * uy(p)
        }
        val hullArea = abs(a2) / 2.0
        return if (hullArea <= 0) 1.0 else (c.area / hullArea).coerceAtMost(1.0)
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    private fun ux(p: Long): Int = (p shr 32).toInt()
    private fun uy(p: Long): Int = p.toInt()

    private fun convexHull(points: List<Long>): List<Long> {
        val p = points.distinct().sortedWith(compareBy({ ux(it) }, { uy(it) }))
        if (p.size < 3) return p
        fun cross(o: Long, a: Long, b: Long): Long =
            (ux(a) - ux(o)).toLong() * (uy(b) - uy(o)) - (uy(a) - uy(o)).toLong() * (ux(b) - ux(o))
        val hull = ArrayList<Long>()
        for (pt in p) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], pt) <= 0) hull.removeAt(hull.size - 1)
            hull += pt
        }
        val lower = hull.size + 1
        for (i in p.size - 2 downTo 0) {
            val pt = p[i]
            while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], pt) <= 0) hull.removeAt(hull.size - 1)
            hull += pt
        }
        hull.removeAt(hull.size - 1)
        return hull
    }

    /**
     * 격자(약 8x8)별 배경색을 구해 픽셀마다 보간한다. 조명 그라데이션·그림자·비네팅을 보정해
     * 어두운 구석이 알약으로 잡히는 것을 막는다. 결과는 픽셀마다 [r,g,b] 가 이어진 배열.
     */
    private fun localBackground(r: IntArray, g: IntArray, b: IntArray, w: Int, h: Int, bg: IntArray): IntArray {
        val cell = max(8, max(w, h) / 8)
        val gx = (w + cell - 1) / cell
        val gy = (h + cell - 1) / cell
        var est = Array(gx * gy) { bg.copyOf() }
        val dirs = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1))
        for (pass in 0 until 3) {
            val tol = if (pass == 0) 48 else 36
            val ok = BooleanArray(gx * gy)
            val next = Array(gx * gy) { est[it].copyOf() }
            for (cy in 0 until gy) for (cx in 0 until gx) {
                val ci = cy * gx + cx
                val ref = est[ci]
                var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0; var total = 0
                for (y in cy * cell until min(h, (cy + 1) * cell)) {
                    val row = y * w
                    for (x in cx * cell until min(w, (cx + 1) * cell)) {
                        val i = row + x
                        total++
                        val dr = r[i] - ref[0]; val dg = g[i] - ref[1]; val db = b[i] - ref[2]
                        if (dr * dr + dg * dg + db * db <= tol * tol) {
                            sr += r[i]; sg += g[i]; sb += b[i]; cnt++
                        }
                    }
                }
                if (cnt > 0 && cnt >= total * 0.2) {
                    next[ci] = intArrayOf((sr / cnt).toInt(), (sg / cnt).toInt(), (sb / cnt).toInt())
                    ok[ci] = true
                }
            }
            // 배경을 찾지 못한 칸(알약으로 덮인 칸 등)은 이웃 칸 값으로 채운다
            if (ok.any { it }) {
                var filled = ok
                while (!filled.all { it }) {
                    val nf = filled.copyOf()
                    for (cy in 0 until gy) for (cx in 0 until gx) {
                        val ci = cy * gx + cx
                        if (filled[ci]) continue
                        var sr = 0; var sg = 0; var sb = 0; var k = 0
                        for (d in dirs) {
                            val nx = cx + d[0]; val ny = cy + d[1]
                            if (nx !in 0 until gx || ny !in 0 until gy) continue
                            val ni = ny * gx + nx
                            if (!filled[ni]) continue
                            sr += next[ni][0]; sg += next[ni][1]; sb += next[ni][2]; k++
                        }
                        if (k > 0) {
                            next[ci] = intArrayOf(sr / k, sg / k, sb / k)
                            nf[ci] = true
                        }
                    }
                    filled = nf
                }
            }
            est = next
        }
        // 칸 중심 사이를 쌍선형 보간
        val out = IntArray(w * h * 3)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (gy - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(gy - 1, y0 + 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (gx - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(gx - 1, x0 + 1); val tx = fx - x0
                val a = est[y0 * gx + x0]; val bb = est[y0 * gx + x1]
                val c = est[y1 * gx + x0]; val d = est[y1 * gx + x1]
                val o = 3 * (y * w + x)
                for (k in 0..2) {
                    val top = a[k] + (bb[k] - a[k]) * tx
                    val bot = c[k] + (d[k] - c[k]) * tx
                    out[o + k] = (top + (bot - top) * ty).roundToInt()
                }
            }
        }
        return out
    }

    private fun splitComponent(
        c: Comp, k: Int, labels: IntArray, w: Int,
        r0: IntArray, g0: IntArray, b0: IntArray, h: Int,
    ): List<Pill> {
        val xs = IntArray(c.area)
        val ys = IntArray(c.area)
        var p = 0
        for (y in c.minY..c.maxY) {
            val row = y * w
            for (x in c.minX..c.maxX) {
                if (labels[row + x] == c.id) {
                    xs[p] = x; ys[p] = y; p++
                }
            }
        }
        if (p == 0) return emptyList()
        val stride = max(1, p / 3000)
        val cx = FloatArray(k)
        val cy = FloatArray(k)

        // farthest-point 시드
        val mx = (c.sx / c.area).toFloat()
        val my = (c.sy / c.area).toFloat()
        var far = 0
        var farD = -1f
        var i = 0
        while (i < p) {
            val d = (xs[i] - mx) * (xs[i] - mx) + (ys[i] - my) * (ys[i] - my)
            if (d > farD) { farD = d; far = i }
            i += stride
        }
        cx[0] = xs[far].toFloat(); cy[0] = ys[far].toFloat()
        val minD = FloatArray(p) { Float.MAX_VALUE }
        for (j in 1 until k) {
            var arg = 0
            var argD = -1f
            i = 0
            while (i < p) {
                val d = (xs[i] - cx[j - 1]) * (xs[i] - cx[j - 1]) + (ys[i] - cy[j - 1]) * (ys[i] - cy[j - 1])
                if (d < minD[i]) minD[i] = d
                if (minD[i] > argD) { argD = minD[i]; arg = i }
                i += stride
            }
            cx[j] = xs[arg].toFloat(); cy[j] = ys[arg].toFloat()
        }

        // Lloyd 반복
        val sumX = DoubleArray(k)
        val sumY = DoubleArray(k)
        val cnt = IntArray(k)
        repeat(12) {
            sumX.fill(0.0); sumY.fill(0.0); cnt.fill(0)
            i = 0
            while (i < p) {
                val j = nearest(xs[i].toFloat(), ys[i].toFloat(), cx, cy)
                sumX[j] += xs[i].toDouble(); sumY[j] += ys[i].toDouble(); cnt[j]++
                i += stride
            }
            for (j in 0 until k) if (cnt[j] > 0) {
                cx[j] = (sumX[j] / cnt[j]).toFloat(); cy[j] = (sumY[j] / cnt[j]).toFloat()
            }
        }

        val parts = Array(k) { Comp(-1) }
        for (q in 0 until p) {
            val j = nearest(xs[q].toFloat(), ys[q].toFloat(), cx, cy)
            val idx = ys[q] * w + xs[q]
            parts[j].add(xs[q], ys[q], r0[idx], g0[idx], b0[idx])
        }
        return parts.filter { it.area > 0 }.map { it.toPill(w, h) }
    }

    private fun nearest(x: Float, y: Float, cx: FloatArray, cy: FloatArray): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (j in cx.indices) {
            val d = (x - cx[j]) * (x - cx[j]) + (y - cy[j]) * (y - cy[j])
            if (d < bestD) { bestD = d; best = j }
        }
        return best
    }

    private class Comp(val id: Int) {
        var area = 0
        var sx = 0.0; var sy = 0.0
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sr = 0L; var sg = 0L; var sb = 0L
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = -1; var maxY = -1
        var solidity = 1.0

        fun add(x: Int, y: Int, r: Int, g: Int, b: Int) {
            area++
            sx += x; sy += y
            sxx += x.toDouble() * x; syy += y.toDouble() * y; sxy += x.toDouble() * y
            sr += r; sg += g; sb += b
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        val meanR get() = (sr / area).toInt()
        val meanG get() = (sg / area).toInt()
        val meanB get() = (sb / area).toInt()

        /** 2차 모멘트로 구한 장축/단축 비 (1 = 원형) */
        fun elongation(): Double {
            val a = area.toDouble()
            val mx = sx / a
            val my = sy / a
            val vx = sxx / a - mx * mx + 1.0 / 12
            val vy = syy / a - my * my + 1.0 / 12
            val cxy = sxy / a - mx * my
            val tr = vx + vy
            val disc = sqrt(max(0.0, tr * tr / 4 - (vx * vy - cxy * cxy)))
            return sqrt(max(1.0, (tr / 2 + disc) / max(1e-6, tr / 2 - disc)))
        }

        fun toPill(w: Int, h: Int): Pill {
            val a = area.toDouble()
            val mx = sx / a
            val my = sy / a
            val color = (0xFF shl 24) or (meanR shl 16) or (meanG shl 8) or meanB
            return Pill(
                x = ((mx + 0.5) / w).toFloat(),
                y = ((my + 0.5) / h).toFloat(),
                radius = (sqrt(a / PI) / w).toFloat(),
                color = color,
                elongation = elongation().toFloat(),
                boxW = (maxX - minX + 1).toFloat() / w,
                boxH = (maxY - minY + 1).toFloat() / h,
            )
        }
    }

    private fun boxBlur3(src: IntArray, w: Int, h: Int): IntArray {
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = src[row + x]
                val l = if (x > 0) src[row + x - 1] else c
                val r = if (x < w - 1) src[row + x + 1] else c
                tmp[row + x] = (l + c + r) / 3
            }
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = tmp[row + x]
                val u = if (y > 0) tmp[row - w + x] else c
                val d = if (y < h - 1) tmp[row + w + x] else c
                out[row + x] = (u + c + d) / 3
            }
        }
        return out
    }

    /** 테두리 영역에 가중치를 둔 최빈 색상을 트레이 배경색으로 본다. */
    private fun estimateBackground(r: IntArray, g: IntArray, b: IntArray, w: Int, h: Int): IntArray {
        val hist = IntArray(4096)
        val bx = max(1, (w * 0.06f).toInt())
        val by = max(1, (h * 0.06f).toInt())
        for (y in 0 until h) {
            val row = y * w
            val edgeY = y < by || y >= h - by
            for (x in 0 until w) {
                val i = row + x
                val bin = ((r[i] shr 4) shl 8) or ((g[i] shr 4) shl 4) or (b[i] shr 4)
                hist[bin] += if (edgeY || x < bx || x >= w - bx) 4 else 1
            }
        }
        var mode = 0
        for (k in 1 until 4096) if (hist[k] > hist[mode]) mode = k
        val cr = ((mode shr 8) and 15) * 16 + 8
        val cg = ((mode shr 4) and 15) * 16 + 8
        val cb = (mode and 15) * 16 + 8
        var sr = 0L; var sg = 0L; var sb = 0L; var cnt = 0L
        for (i in r.indices) {
            if (abs(r[i] - cr) <= 24 && abs(g[i] - cg) <= 24 && abs(b[i] - cb) <= 24) {
                sr += r[i]; sg += g[i]; sb += b[i]; cnt++
            }
        }
        return if (cnt == 0L) intArrayOf(cr, cg, cb)
        else intArrayOf((sr / cnt).toInt(), (sg / cnt).toInt(), (sb / cnt).toInt())
    }

    private fun otsuThreshold(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var wB = 0L
        var maxVar = -1.0
        var th = 0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > maxVar) { maxVar = v; th = t }
        }
        return th + 1
    }

    private fun fillSmallHoles(mask: BooleanArray, w: Int, h: Int, maxHole: Int) {
        val labels = IntArray(mask.size)
        val k = labelComponents(mask, w, h, false, labels)
        if (k == 0) return
        val size = IntArray(k + 1)
        val touches = BooleanArray(k + 1)
        for (y in 0 until h) for (x in 0 until w) {
            val l = labels[y * w + x]
            if (l == 0) continue
            size[l]++
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touches[l] = true
        }
        for (i in mask.indices) {
            val l = labels[i]
            if (l != 0 && !touches[l] && size[l] <= maxHole) mask[i] = true
        }
    }

    /** 4-연결 라벨링. labels 는 0 으로 초기화되어 있어야 한다. 라벨 개수를 반환. */
    private fun labelComponents(mask: BooleanArray, w: Int, h: Int, value: Boolean, labels: IntArray): Int {
        val n = w * h
        val queue = IntArray(n)
        var next = 0
        for (start in 0 until n) {
            if (mask[start] != value || labels[start] != 0) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                if (x > 0) { val j = i - 1; if (mask[j] == value && labels[j] == 0) { labels[j] = next; queue[tail++] = j } }
                if (x < w - 1) { val j = i + 1; if (mask[j] == value && labels[j] == 0) { labels[j] = next; queue[tail++] = j } }
                if (i >= w) { val j = i - w; if (mask[j] == value && labels[j] == 0) { labels[j] = next; queue[tail++] = j } }
                if (i + w < n) { val j = i + w; if (mask[j] == value && labels[j] == 0) { labels[j] = next; queue[tail++] = j } }
            }
        }
        return next
    }

    private fun erode(src: BooleanArray, w: Int, h: Int, iters: Int): BooleanArray {
        var cur = src
        repeat(iters) {
            val out = BooleanArray(cur.size)
            for (y in 1 until h - 1) {
                val row = y * w
                for (x in 1 until w - 1) {
                    val i = row + x
                    out[i] = cur[i] && cur[i - 1] && cur[i + 1] && cur[i - w] && cur[i + w]
                }
            }
            cur = out
        }
        return cur
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int, iters: Int): BooleanArray {
        var cur = src
        repeat(iters) {
            val out = BooleanArray(cur.size)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    out[i] = cur[i] ||
                        (x > 0 && cur[i - 1]) || (x < w - 1 && cur[i + 1]) ||
                        (y > 0 && cur[i - w]) || (y < h - 1 && cur[i + w])
                }
            }
            cur = out
        }
        return cur
    }
}
