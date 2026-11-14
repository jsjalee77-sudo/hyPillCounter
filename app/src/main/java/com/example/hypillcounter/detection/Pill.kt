package com.example.hypillcounter.detection

import android.graphics.Bitmap

/**
 * 탐지된 알약 1개.
 * @param x 중심 x (이미지 너비 기준 0..1)
 * @param y 중심 y (이미지 높이 기준 0..1)
 * @param radius 반지름 (이미지 너비 기준 정규화)
 * @param color 알약 평균 색상 (ARGB)
 * @param elongation 장축/단축 비 (1 = 원형, 캡슐은 보통 1.6 이상)
 * @param label 분류 모델의 클래스명 (YOLO 다중 클래스 모델 사용 시)
 */
data class Pill(
    val x: Float,
    val y: Float,
    val radius: Float,
    val color: Int,
    val elongation: Float,
    val label: String? = null,
    val confidence: Float = 1f,
    /** 바운딩 박스 크기 (boxW: 너비 기준, boxH: 높이 기준 정규화). 0 이면 radius 로 대체 */
    val boxW: Float = 0f,
    val boxH: Float = 0f,
)

/** 탐지 결과 + 촬영 환경 경고(있을 때만) */
data class Detection(val pills: List<Pill>, val warning: String? = null)

interface PillDetector {
    val name: String

    /** 경고 메시지를 함께 돌려준다. 기본은 경고 없음. */
    fun detectWithInfo(bitmap: Bitmap, sensitivity: Float, maxSide: Int): Detection =
        Detection(detect(bitmap, sensitivity, maxSide))

    /**
     * @param sensitivity 0..1 (높을수록 더 많이 잡음)
     * @param maxSide 분석 시 사용할 최대 해상도(긴 변 픽셀)
     */
    fun detect(bitmap: Bitmap, sensitivity: Float, maxSide: Int): List<Pill>
}
