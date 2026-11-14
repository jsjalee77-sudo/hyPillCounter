package com.example.hypillcounter.detection

import android.graphics.Bitmap
import com.example.hypillcounter.camera.scaleToMax

class ClassicPillDetector : PillDetector {
    override val name: String = "영상처리 엔진"

    override fun detect(bitmap: Bitmap, sensitivity: Float, maxSide: Int): List<Pill> {
        val src = bitmap.scaleToMax(maxSide)
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        return ClassicPillEngine.detect(px, w, h, sensitivity)
    }
}
