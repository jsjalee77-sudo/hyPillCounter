package com.jeongpharm.pillcounter.detection

import android.content.Context
import android.util.Log

object DetectorFactory {
    const val TFLITE_ASSET = "pill_detector.tflite"
    const val ONNX_ASSET = "pill_detector.onnx"
    const val LABELS_ASSET = "pill_labels.txt"

    /**
     * assets 의 YOLO 모델을 우선 사용한다 (tflite → onnx 순).
     * 모델이 없거나 로드에 실패하면 영상처리 엔진으로 동작한다.
     */
    fun create(context: Context): PillDetector {
        val assets = runCatching { context.assets.list("")?.toSet() }.getOrNull().orEmpty()
        val labels = YoloDetector.readLabels(context, LABELS_ASSET)
        if (TFLITE_ASSET in assets) {
            runCatching { return YoloTfliteDetector(context, TFLITE_ASSET, labels) }
                .onFailure { Log.w(TAG, "TFLite 모델 로드 실패", it) }
        }
        if (ONNX_ASSET in assets) {
            runCatching { return YoloOnnxDetector(context, ONNX_ASSET, labels) }
                .onFailure { Log.w(TAG, "ONNX 모델 로드 실패", it) }
        }
        return ClassicPillDetector()
    }

    private const val TAG = "DetectorFactory"
}
