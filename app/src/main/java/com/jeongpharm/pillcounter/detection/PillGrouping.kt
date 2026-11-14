package com.jeongpharm.pillcounter.detection

/** 색상·모양 기반 알약 그룹명 (다중 종류 분류 카운팅용). 순수 Kotlin. */
object PillGrouping {

    fun groupOf(p: Pill): String = p.label ?: "${colorName(p.color)} ${shapeName(p.elongation)}"

    fun shapeName(elongation: Float): String = if (elongation >= 1.55f) "장형" else "원형"

    fun colorName(color: Int): String {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val v = max
        val s = if (max == 0f) 0f else d / max
        var hue = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (hue < 0f) hue += 360f
        if (v < 0.2f) return "검은색"
        if (s < 0.2f || d < 0.12f) {
            return when {
                v > 0.7f -> "흰색"
                v < 0.25f -> "검은색"
                else -> "회색"
            }
        }
        return when {
            hue < 15f || hue >= 340f -> when {
                v < 0.5f -> "갈색"
                s < 0.45f -> "분홍색"
                else -> "빨간색"
            }
            hue < 45f -> if (v < 0.55f) "갈색" else "주황색"
            hue < 70f -> "노란색"
            hue < 160f -> "초록색"
            hue < 200f -> "청록색"
            hue < 255f -> "파란색"
            hue < 290f -> "보라색"
            else -> "분홍색"
        }
    }
}
