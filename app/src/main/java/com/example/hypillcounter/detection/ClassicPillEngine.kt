package com.example.hypillcounter.detection

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 학습 모델 없이 동작하는 고전 영상처리 기반 알약 계수 엔진 (순수 Kotlin, 단위 테스트 가능).
 *
 * 1. 3x3 블러 → 트레이 배경색 추정(테두리 가중 최빈색)
 * 2. 배경색과의 색 거리 맵 → Otsu 이진화(감도 반영)
 * 3. 작은 구멍(빛 반사) 메우기 → 모폴로지 열림(얇은 접촉부 분리)
 * 4. 연결 요소 라벨링 → 색상별 그룹화
 * 5. 그룹별 "알약 1개 면적" 추정 → 붙어있는 덩어리는 면적비로 개수 산출 후 k-means 로 위치 분리
 */
object ClassicPillEngine {

    fun detect(px: IntArray, w: Int, h: Int, sensitivity: Float = 0.5f): List<Pill> {
        val n = w * h
        if (w < 8 || h < 8 || px.size < n) return emptyList()
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
        val dist = IntArray(n)
        val hist = IntArray(256)
        for (i in 0 until n) {
            val dr = r[i] - bg[0]
            val dg = g[i] - bg[1]
            val db = b[i] - bg[2]
            val d = min(255, sqrt((dr * dr + dg * dg + db * db).toDouble()).toInt())
            dist[i] = d
            hist[d]++
        }
        val otsu = otsuThreshold(hist, n)
        val minT = 14f + (1f - s) * 36f
        val t = (max(otsu.toFloat(), minT) * (1.25f - 0.5f * s)).roundToInt().coerceIn(8, 220)

        var mask = BooleanArray(n) { dist[it] >= t }
        fillSmallHoles(mask, w, h, maxHole = max(16, (n * 0.002).toInt()))
        val iters = max(1, (max(w, h) / 400f).roundToInt())
        mask = dilate(erode(mask, w, h, iters), w, h, iters)

        val labels = IntArray(n)
        val count = labelComponents(mask, w, h, true, labels)
        if (count == 0) return emptyList()
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
        val valid = (1..count).map { comps[it] }.filter { it.area >= minArea }
        if (valid.isEmpty()) return emptyList()

        // 색상 기반 그룹화 (종류별로 1알 면적이 다를 수 있으므로)
        val groups = mutableListOf<MutableList<Comp>>()
        val means = mutableListOf<FloatArray>()
        for (c in valid.sortedByDescending { it.area }) {
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

        val pills = ArrayList<Pill>()
        for (grp in groups) {
            val unit = estimateUnitArea(IntArray(grp.size) { grp[it].area })
            for (c in grp) {
                if (grp.size > 1 && c.area < unit * 0.35) continue // 파편/노이즈
                val k = max(1, (c.area / unit).roundToInt()).coerceAtMost(60)
                if (k == 1) pills += c.toPill(w, h)
                else pills += splitComponent(c, k, labels, w, r0, g0, b0, h)
            }
        }
        return pills
    }

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

        fun toPill(w: Int, h: Int): Pill {
            val a = area.toDouble()
            val mx = sx / a
            val my = sy / a
            val vx = sxx / a - mx * mx + 1.0 / 12
            val vy = syy / a - my * my + 1.0 / 12
            val cxy = sxy / a - mx * my
            val tr = vx + vy
            val det = vx * vy - cxy * cxy
            val disc = sqrt(max(0.0, tr * tr / 4 - det))
            val l1 = tr / 2 + disc
            val l2 = max(1e-6, tr / 2 - disc)
            val elong = sqrt(max(1.0, l1 / l2)).toFloat()
            val color = (0xFF shl 24) or (meanR shl 16) or (meanG shl 8) or meanB
            return Pill(
                x = ((mx + 0.5) / w).toFloat(),
                y = ((my + 0.5) / h).toFloat(),
                radius = (sqrt(a / PI) / w).toFloat(),
                color = color,
                elongation = elong,
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
