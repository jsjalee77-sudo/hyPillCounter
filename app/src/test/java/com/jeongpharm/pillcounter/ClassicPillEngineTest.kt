package com.jeongpharm.pillcounter

import com.jeongpharm.pillcounter.detection.ClassicPillEngine
import com.jeongpharm.pillcounter.detection.PillGrouping
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ClassicPillEngineTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private class Canvas(val w: Int, val h: Int, bg: Int) {
        val px = IntArray(w * h) { bg }
        fun circle(cx: Int, cy: Int, r: Int, color: Int) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                if (x !in 0 until w || y !in 0 until h) continue
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = color
            }
        }
        fun capsule(cx: Int, cy: Int, halfLen: Int, r: Int, color: Int) {
            for (y in cy - r..cy + r) for (x in cx - halfLen - r..cx + halfLen + r) {
                if (x !in 0 until w || y !in 0 until h) continue
                val nx = x.coerceIn(cx - halfLen, cx + halfLen)
                if ((x - nx) * (x - nx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = color
            }
        }
        fun noise(rnd: Random, amp: Int) {
            for (i in px.indices) {
                val c = px[i]
                fun ch(v: Int) = (v + rnd.nextInt(-amp, amp + 1)).coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
            }
        }
    }

    @Test
    fun countsSeparatedWhitePillsOnDarkTray() {
        val c = Canvas(480, 360, rgb(30, 32, 40))
        var n = 0
        for (row in 0 until 5) for (col in 0 until 6) {
            c.circle(50 + col * 75, 45 + row * 68, 18, rgb(240, 240, 235)); n++
        }
        c.noise(Random(1), 6)
        val pills = ClassicPillEngine.detect(c.px, c.w, c.h)
        assertEquals(n, pills.size)
    }

    @Test
    fun splitsTouchingPills() {
        val c = Canvas(480, 360, rgb(25, 25, 30))
        // 12 개 단독
        for (i in 0 until 12) c.circle(40 + (i % 6) * 75, 50 + (i / 6) * 70, 16, rgb(235, 235, 230))
        // 붙어있는 2개짜리 3쌍 + 3개짜리 1묶음
        for (i in 0 until 3) {
            c.circle(60 + i * 140, 230, 16, rgb(235, 235, 230))
            c.circle(91 + i * 140, 230, 16, rgb(235, 235, 230))
        }
        c.circle(200, 310, 16, rgb(235, 235, 230))
        c.circle(231, 310, 16, rgb(235, 235, 230))
        c.circle(262, 310, 16, rgb(235, 235, 230))
        c.noise(Random(2), 6)
        val pills = ClassicPillEngine.detect(c.px, c.w, c.h)
        assertEquals(12 + 6 + 3, pills.size)
    }

    @Test
    fun groupsMixedColorsAndShapes() {
        val c = Canvas(520, 380, rgb(20, 40, 90)) // 파란 트레이
        for (i in 0 until 10) c.circle(40 + (i % 5) * 95, 50 + (i / 5) * 70, 15, rgb(245, 245, 240))
        for (i in 0 until 8) c.capsule(50 + (i % 4) * 120, 220 + (i / 4) * 80, 16, 11, rgb(240, 200, 30))
        c.noise(Random(3), 5)
        val pills = ClassicPillEngine.detect(c.px, c.w, c.h)
        assertEquals(18, pills.size)
        val groups = pills.groupingBy { PillGrouping.groupOf(it) }.eachCount()
        assertEquals(10, groups["흰색 원형"])
        assertEquals(8, groups["노란색 장형"])
    }

    @Test
    fun emptyTrayHasNoPills() {
        val c = Canvas(320, 240, rgb(35, 35, 40))
        c.noise(Random(4), 8)
        assertEquals(0, ClassicPillEngine.detect(c.px, c.w, c.h).size)
    }

    @Test
    fun mixedSizesAreNotOverCounted() {
        // 같은 흰색이지만 크기가 3배 다른 알약: 큰 알약을 여러 개로 쪼개면 안 됨
        val c = Canvas(520, 380, rgb(30, 60, 120))
        for (i in 0 until 10) c.circle(40 + (i % 5) * 100, 50 + (i / 5) * 60, 8, rgb(245, 245, 240))
        for (i in 0 until 6) c.circle(60 + (i % 3) * 160, 230 + (i / 3) * 90, 26, rgb(245, 245, 240))
        c.noise(Random(5), 5)
        assertEquals(16, ClassicPillEngine.detect(c.px, c.w, c.h).size)
    }

    @Test
    fun handlesUnevenLighting() {
        // 왼쪽 위는 밝고 오른쪽 아래는 어두운 트레이 (조명 그라데이션)
        val c = Canvas(480, 360, 0)
        for (y in 0 until c.h) for (x in 0 until c.w) {
            val v = (200 - (x + y) * 110 / (c.w + c.h))
            c.px[y * c.w + x] = rgb(v, v, v + 10)
        }
        for (i in 0 until 12) c.circle(50 + (i % 6) * 75, 70 + (i / 6) * 160, 15, rgb(240, 200, 30))
        c.noise(Random(6), 5)
        assertEquals(12, ClassicPillEngine.detect(c.px, c.w, c.h).size)
    }

    @Test
    fun ignoresObjectsAtEdgesAndLines() {
        val c = Canvas(480, 360, rgb(30, 32, 40))
        for (i in 0 until 8) c.circle(80 + (i % 4) * 100, 110 + (i / 4) * 120, 16, rgb(240, 240, 235))
        // 가장자리에 걸친 트레이 테두리/손가락
        for (y in 0 until 360) for (x in 0 until 25) c.px[y * c.w + x] = rgb(200, 160, 140)
        c.circle(470, 180, 40, rgb(200, 160, 140))
        // 가늘고 긴 선 (그림자 경계, 케이블)
        for (x in 60 until 420) for (y in 300 until 305) c.px[y * c.w + x] = rgb(220, 220, 220)
        c.noise(Random(7), 5)
        assertEquals(8, ClassicPillEngine.detect(c.px, c.w, c.h).size)
    }

    @Test
    fun clutteredSceneIsRejectedWithWarning() {
        // 단색 트레이가 아닌 어지러운 장면 (책상, 방 등)
        val rnd = Random(8)
        val c = Canvas(320, 240, 0)
        for (by in 0 until 240 step 20) for (bx in 0 until 320 step 20) {
            val col = rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            for (y in by until by + 20) for (x in bx until bx + 20) c.px[y * c.w + x] = col
        }
        val res = ClassicPillEngine.analyze(c.px, c.w, c.h)
        assertEquals(0, res.pills.size)
        assert(res.warning != null)
    }

    @Test
    fun unitAreaEstimateHandlesClumps() {
        val areas = intArrayOf(800, 820, 790, 810, 1600, 1620, 2400, 805, 795)
        val u = ClassicPillEngine.estimateUnitArea(areas)
        assert(u in 700.0..900.0) { "unit=$u" }
    }
}
