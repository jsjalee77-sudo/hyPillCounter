package com.jeongpharm.pillcounter.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.roundToInt

/** ImageProxy → 화면 방향(정방향) Bitmap. 뷰포트 cropRect 가 적용되지 않은 경우 직접 적용한다. */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val src = toBitmap()
    val c = cropRect
    val needsCrop = !c.isEmpty && c.left >= 0 && c.top >= 0 &&
        c.right <= src.width && c.bottom <= src.height &&
        (c.width() != src.width || c.height() != src.height)
    val cropped = if (needsCrop) Bitmap.createBitmap(src, c.left, c.top, c.width(), c.height()) else src
    return cropped.rotate(imageInfo.rotationDegrees)
}

fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}

/** 정규화 좌표(0..1) 사각형으로 자른다. */
fun Bitmap.cropNormalized(rect: RectF): Bitmap {
    val l = (rect.left * width).roundToInt().coerceIn(0, width - 1)
    val t = (rect.top * height).roundToInt().coerceIn(0, height - 1)
    val r = (rect.right * width).roundToInt().coerceIn(l + 1, width)
    val b = (rect.bottom * height).roundToInt().coerceIn(t + 1, height)
    if (l == 0 && t == 0 && r == width && b == height) return this
    return Bitmap.createBitmap(this, l, t, r - l, b - t)
}

fun Bitmap.scaleToMax(maxSide: Int): Bitmap {
    val longSide = max(width, height)
    if (longSide <= maxSide) return this
    val s = maxSide.toFloat() / longSide
    return Bitmap.createScaledBitmap(
        this,
        (width * s).roundToInt().coerceAtLeast(1),
        (height * s).roundToInt().coerceAtLeast(1),
        true,
    )
}

/** 갤러리 이미지를 EXIF 회전을 반영해 불러온다. */
fun loadBitmapFromUri(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    val cr = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val orientation = cr.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val deg = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
    bmp.rotate(deg).scaleToMax(maxSide)
}.getOrNull()

/** 파일 경로 이미지를 축소 디코딩 (썸네일용). */
fun decodeSampled(path: String, maxSide: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
