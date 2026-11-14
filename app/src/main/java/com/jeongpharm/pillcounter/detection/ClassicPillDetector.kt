package com.jeongpharm.pillcounter.detection

import android.graphics.Bitmap
import com.jeongpharm.pillcounter.camera.scaleToMax

class ClassicPillDetector : PillDetector {
    override val name: String = "영상처리 엔진"

    override fun detect(bitmap: Bitmap, sensitivity: Float, maxSide: Int): List<Pill> =
        detectWithInfo(bitmap, sensitivity, maxSide).pills

    override fun detectWithInfo(bitmap: Bitmap, sensitivity: Float, maxSide: Int): Detection {
        val src = bitmap.scaleToMax(maxSide)
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val r = ClassicPillEngine.analyze(px, w, h, sensitivity)
        return Detection(r.pills, r.warning)
    }
}
