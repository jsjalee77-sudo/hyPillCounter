package com.example.hypillcounter.data

import android.graphics.BitmapFactory
import com.example.hypillcounter.ui.Marker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MarkerJson {
    fun encode(markers: List<Marker>): String = JSONArray().apply {
        markers.forEach { m ->
            put(
                JSONObject()
                    .put("x", m.x.toDouble()).put("y", m.y.toDouble())
                    .put("r", m.radius.toDouble())
                    .put("bw", m.boxW.toDouble()).put("bh", m.boxH.toDouble())
                    .put("g", m.group).put("manual", m.manual),
            )
        }
    }.toString()

    fun decode(json: String): List<Marker> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Marker(
                id = i.toLong(),
                x = o.getDouble("x").toFloat(),
                y = o.getDouble("y").toFloat(),
                radius = o.getDouble("r").toFloat(),
                group = o.optString("g"),
                manual = o.optBoolean("manual"),
                boxW = o.optDouble("bw", 0.0).toFloat(),
                boxH = o.optDouble("bh", 0.0).toFloat(),
            )
        }
    }
}

/**
 * 저장된(사람이 확인·보정한) 계수 기록을 YOLO 학습 데이터셋 zip 으로 내보낸다.
 *
 * zip 구조:
 *   images/<name>.jpg   원본 이미지
 *   labels/<name>.txt   "0 cx cy w h" (클래스 0 = pill, 0..1 정규화)
 *   classes.txt         pill
 *   dataset_info.json   기록별 수량/목표/종류(그룹) 메타데이터
 */
object DatasetExporter {

    data class Result(val file: File, val images: Int, val boxes: Int)

    fun export(records: List<CountRecord>, outFile: File): Result {
        outFile.parentFile?.mkdirs()
        var images = 0
        var boxes = 0
        val info = JSONArray()
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            for (rec in records) {
                val img = File(rec.imagePath)
                if (rec.markersJson.isBlank() || !img.exists()) continue
                val markers = runCatching { MarkerJson.decode(rec.markersJson) }.getOrNull() ?: continue
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(img.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                val aspect = bounds.outHeight.toFloat() / bounds.outWidth

                val name = "pill_${rec.timestamp}"
                zip.putNextEntry(ZipEntry("images/$name.jpg"))
                img.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                val label = markers.joinToString("\n") { m ->
                    val b = m.yoloBox(aspect)
                    String.format(Locale.US, "0 %.6f %.6f %.6f %.6f", b[0], b[1], b[2], b[3])
                }
                zip.putNextEntry(ZipEntry("labels/$name.txt"))
                zip.write(label.toByteArray())
                zip.closeEntry()

                info.put(
                    JSONObject()
                        .put("file", "$name.jpg")
                        .put("width", bounds.outWidth).put("height", bounds.outHeight)
                        .put("count", markers.size)
                        .put("target", rec.target ?: JSONObject.NULL)
                        .put("auto_count", rec.autoCount)
                        .put("added", rec.added).put("removed", rec.removed)
                        .put("groups", JSONArray(markers.map { it.group })),
                )
                images++
                boxes += markers.size
            }
            zip.putNextEntry(ZipEntry("classes.txt"))
            zip.write("pill\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("dataset_info.json"))
            zip.write(JSONObject().put("source", "PillCounter AI").put("records", info).toString(2).toByteArray())
            zip.closeEntry()
        }
        return Result(outFile, images, boxes)
    }
}
