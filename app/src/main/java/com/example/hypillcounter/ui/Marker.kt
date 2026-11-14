package com.example.hypillcounter.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** 결과 화면의 알약 마커. 좌표는 이미지 기준 정규화, radius 는 너비 기준 정규화. */
data class Marker(
    val id: Long,
    val x: Float,
    val y: Float,
    val radius: Float,
    val group: String,
    val manual: Boolean = false,
    /** 바운딩 박스 너비(이미지 너비 기준)·높이(이미지 높이 기준). 0 이면 radius 로 계산 */
    val boxW: Float = 0f,
    val boxH: Float = 0f,
) {
    /** YOLO 라벨용 박스 (cx, cy, w, h 모두 0..1). aspect = 이미지 height / width */
    fun yoloBox(aspect: Float): FloatArray {
        val w = if (boxW > 0f) boxW else radius * 2f
        val h = if (boxH > 0f) boxH else radius * 2f / aspect
        return floatArrayOf(x, y, w.coerceIn(0.002f, 1f), h.coerceIn(0.002f, 1f))
    }
}

object GroupPalette {
    const val MANUAL = "수동 추가"
    private val palette = intArrayOf(
        0xFF00E676.toInt(), 0xFF2979FF.toInt(), 0xFFFFC400.toInt(), 0xFFFF4081.toInt(),
        0xFF00E5FF.toInt(), 0xFFD500F9.toInt(), 0xFFFF6D00.toInt(), 0xFF76FF03.toInt(),
    )
    private const val MANUAL_COLOR = 0xFFFF1744.toInt()

    /** 그룹별 개수 (수량 많은 순, 수동 추가는 마지막) */
    fun summary(markers: List<Marker>): List<Pair<String, Int>> =
        markers.groupingBy { it.group }.eachCount().toList()
            .sortedWith(compareBy<Pair<String, Int>>({ it.first == MANUAL }, { -it.second }))

    fun colors(summary: List<Pair<String, Int>>): Map<String, Int> {
        var i = 0
        return summary.associate { (g, _) -> g to if (g == MANUAL) MANUAL_COLOR else palette[i++ % palette.size] }
    }

    fun summaryText(markers: List<Marker>): String =
        summary(markers).joinToString(", ") { (g, c) -> "$g ${c}정" }
}

/** 좌상단→우하단 읽기 순서로 정렬 (행 단위). aspect = height / width */
fun List<Marker>.inReadingOrder(aspect: Float): List<Marker> {
    if (size < 2) return this
    val diamY = map { it.radius * 2f / aspect }.sorted()[size / 2]
    val byY = sortedBy { it.y }
    val rows = mutableListOf<MutableList<Marker>>()
    var rowStart = Float.NEGATIVE_INFINITY
    for (m in byY) {
        if (rows.isEmpty() || m.y - rowStart > diamY * 0.7f) {
            rows += mutableListOf(m)
            rowStart = m.y
        } else {
            rows.last() += m
        }
    }
    return rows.flatMap { row -> row.sortedBy { it.x } }
}

/** 증빙용 마킹 이미지 생성: 상단 정보 띠 + 번호 마커. */
object MarkedImageRenderer {
    fun render(src: Bitmap, markers: List<Marker>, count: Int, target: Int?, timestamp: Long): Bitmap {
        val colors = GroupPalette.colors(GroupPalette.summary(markers))
        val header = max(48, (src.width * 0.08f).toInt())
        val out = Bitmap.createBitmap(src.width, src.height + header, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val status = when {
            target == null -> 0xFF00695C.toInt()
            count == target -> 0xFF2E7D32.toInt()
            else -> 0xFFC62828.toInt()
        }
        canvas.drawColor(status)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = header * 0.42f
            typeface = Typeface.DEFAULT_BOLD
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(timestamp))
        val targetText = target?.let { " / 목표 ${it}정" } ?: ""
        canvas.drawText("총 ${count}정$targetText   $time", header * 0.3f, header * 0.64f, textPaint)
        canvas.drawBitmap(src, 0f, header.toFloat(), null)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, src.width / 400f)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        markers.forEachIndexed { i, m ->
            val c = colors[m.group] ?: 0xFF00E676.toInt()
            val cx = m.x * src.width
            val cy = m.y * src.height + header
            val r = max(6f, m.radius * src.width)
            stroke.color = c
            canvas.drawCircle(cx, cy, r, stroke)
            val badge = max(9f, r * 0.45f)
            fill.color = (c and 0x00FFFFFF) or (0xCC shl 24)
            canvas.drawCircle(cx, cy, badge, fill)
            num.textSize = badge * 1.1f
            canvas.drawText("${i + 1}", cx, cy + num.textSize * 0.36f, num)
        }
        return out
    }
}
