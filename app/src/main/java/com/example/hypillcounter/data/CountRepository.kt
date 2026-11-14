package com.example.hypillcounter.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

data class CountRecord(
    val id: Long = 0,
    val timestamp: Long,
    val count: Int,
    val target: Int?,
    val autoCount: Int,
    val added: Int,
    val removed: Int,
    val groups: String,
    val engine: String,
    val imagePath: String = "",
    val markedPath: String = "",
    /** 보정 완료된 마커 목록 (JSON). 학습 데이터 내보내기에 사용 */
    val markersJson: String = "",
)

/** 계수 이력: SQLite(메타데이터) + 앱 내부 저장소(원본/마킹 이미지). */
class CountRepository(context: Context) {

    private val helper = DbHelper(context.applicationContext)
    private val dir = File(context.applicationContext.filesDir, "records").apply { mkdirs() }

    fun insert(original: Bitmap, marked: Bitmap, record: CountRecord): CountRecord {
        val base = "count_${record.timestamp}"
        val orig = File(dir, "${base}_orig.jpg").also { writeJpeg(original, it) }
        val mark = File(dir, "${base}_marked.jpg").also { writeJpeg(marked, it) }
        val saved = record.copy(imagePath = orig.absolutePath, markedPath = mark.absolutePath)
        val id = helper.writableDatabase.insertOrThrow(TABLE, null, saved.toValues())
        return saved.copy(id = id)
    }

    fun all(): List<CountRecord> =
        helper.readableDatabase.query(TABLE, null, null, null, null, null, "timestamp DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.toRecord()) }
        }

    fun delete(record: CountRecord) {
        helper.writableDatabase.delete(TABLE, "id = ?", arrayOf(record.id.toString()))
        File(record.imagePath).delete()
        File(record.markedPath).delete()
    }

    private fun writeJpeg(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun CountRecord.toValues() = ContentValues().apply {
        put("timestamp", timestamp)
        put("count", count)
        if (target != null) put("target", target) else putNull("target")
        put("auto_count", autoCount)
        put("added", added)
        put("removed", removed)
        put("groups", groups)
        put("engine", engine)
        put("image_path", imagePath)
        put("marked_path", markedPath)
        put("markers", markersJson)
    }

    private fun Cursor.toRecord(): CountRecord {
        val targetIdx = getColumnIndexOrThrow("target")
        return CountRecord(
            id = getLong(getColumnIndexOrThrow("id")),
            timestamp = getLong(getColumnIndexOrThrow("timestamp")),
            count = getInt(getColumnIndexOrThrow("count")),
            target = if (isNull(targetIdx)) null else getInt(targetIdx),
            autoCount = getInt(getColumnIndexOrThrow("auto_count")),
            added = getInt(getColumnIndexOrThrow("added")),
            removed = getInt(getColumnIndexOrThrow("removed")),
            groups = getString(getColumnIndexOrThrow("groups")),
            engine = getString(getColumnIndexOrThrow("engine")),
            imagePath = getString(getColumnIndexOrThrow("image_path")),
            markedPath = getString(getColumnIndexOrThrow("marked_path")),
            markersJson = getString(getColumnIndexOrThrow("markers")) ?: "",
        )
    }

    private class DbHelper(context: Context) : SQLiteOpenHelper(context, "pillcounter.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    count INTEGER NOT NULL,
                    target INTEGER,
                    auto_count INTEGER NOT NULL,
                    added INTEGER NOT NULL,
                    removed INTEGER NOT NULL,
                    groups TEXT NOT NULL,
                    engine TEXT NOT NULL,
                    image_path TEXT NOT NULL,
                    marked_path TEXT NOT NULL,
                    markers TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE $TABLE ADD COLUMN markers TEXT NOT NULL DEFAULT ''")
        }
    }

    private companion object {
        const val TABLE = "records"
    }
}
