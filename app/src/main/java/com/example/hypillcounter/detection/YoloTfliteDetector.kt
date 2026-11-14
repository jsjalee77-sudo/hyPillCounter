package com.example.hypillcounter.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ultralytics YOLOv8 / YOLO11 / YOLO26 탐지 모델 공통 처리 (레터박스 → 추론 → 디코딩 → 중복 제거).
 *
 * 지원 출력: [1, 4+nc, N] / [1, N, 4+nc] (xywh, NMS 미적용) 또는 [1, N<=300, 6] (end2end: x1,y1,x2,y2,conf,cls)
 * 좌표가 0..1 정규화이든 입력 픽셀 단위이든 자동 판별한다.
 */
abstract class YoloDetector(private val labels: List<String>) : PillDetector {

    protected abstract val inW: Int
    protected abstract val inH: Int
    /** 입력 텐서가 [1,3,H,W] 이면 true, [1,H,W,3] 이면 false */
    protected abstract val nchw: Boolean

    /** 입력(RGB 0..1, nchw 여부에 맞춘 배치)을 받아 (평탄화된 출력, 출력 shape) 을 반환 */
    protected abstract fun infer(input: FloatArray): Pair<FloatArray, IntArray>

    @Synchronized
    override fun detect(bitmap: Bitmap, sensitivity: Float, maxSide: Int): List<Pill> {
        val bw = bitmap.width
        val bh = bitmap.height
        val scale = min(inW.toFloat() / bw, inH.toFloat() / bh)
        val sw = (bw * scale).roundToInt().coerceAtLeast(1)
        val sh = (bh * scale).roundToInt().coerceAtLeast(1)
        val padX = (inW - sw) / 2f
        val padY = (inH - sh) / 2f
        val letter = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
        Canvas(letter).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(Bitmap.createScaledBitmap(bitmap, sw, sh, true), padX, padY, null)
        }
        val px = IntArray(inW * inH)
        letter.getPixels(px, 0, inW, 0, 0, inW, inH)
        val plane = inW * inH
        val input = FloatArray(plane * 3)
        for (i in 0 until plane) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            if (nchw) {
                input[i] = r; input[plane + i] = g; input[2 * plane + i] = b
            } else {
                input[i * 3] = r; input[i * 3 + 1] = g; input[i * 3 + 2] = b
            }
        }

        val (out, shape) = infer(input)
        require(shape.size == 3) { "지원하지 않는 출력 형태: ${shape.contentToString()}" }
        val rows = shape[1]
        val cols = shape[2]
        val end2end = cols == 6 && rows <= 300
        val transposed = end2end || rows > cols // [1, N, C]
        val ch = if (transposed) cols else rows
        val num = if (transposed) rows else cols
        fun v(c: Int, i: Int) = if (transposed) out[i * cols + c] else out[c * cols + i]

        var maxCoord = 0f
        for (i in 0 until num) maxCoord = max(maxCoord, max(v(0, i), v(2, i)))
        val sx = if (maxCoord <= 2f) inW.toFloat() else 1f
        val sy = if (maxCoord <= 2f) inH.toFloat() else 1f

        val confTh = (0.6f - 0.4f * sensitivity).coerceIn(0.1f, 0.85f)
        val boxes = ArrayList<Box>()
        for (i in 0 until num) {
            var conf = 0f
            var cls = 0
            val x1: Float; val y1: Float; val x2: Float; val y2: Float
            if (end2end) {
                conf = v(4, i)
                cls = v(5, i).toInt()
                x1 = v(0, i) * sx; y1 = v(1, i) * sy; x2 = v(2, i) * sx; y2 = v(3, i) * sy
            } else {
                for (c in 4 until ch) {
                    val sc = v(c, i)
                    if (sc > conf) { conf = sc; cls = c - 4 }
                }
                val cx = v(0, i) * sx
                val cy = v(1, i) * sy
                val ww = v(2, i) * sx
                val hh = v(3, i) * sy
                x1 = cx - ww / 2; y1 = cy - hh / 2; x2 = cx + ww / 2; y2 = cy + hh / 2
            }
            if (conf < confTh) continue
            boxes += Box(
                (x1 - padX) / scale, (y1 - padY) / scale,
                (x2 - padX) / scale, (y2 - padY) / scale, conf, cls,
            )
        }
        return suppress(boxes).map { b ->
            val cx = (b.x1 + b.x2) / 2
            val cy = (b.y1 + b.y2) / 2
            val bwBox = (b.x2 - b.x1).coerceAtLeast(1f)
            val bhBox = (b.y2 - b.y1).coerceAtLeast(1f)
            Pill(
                x = (cx / bw).coerceIn(0f, 1f),
                y = (cy / bh).coerceIn(0f, 1f),
                radius = min(bwBox, bhBox) / 2f / bw,
                color = sampleColor(bitmap, cx, cy, bwBox, bhBox),
                elongation = max(bwBox, bhBox) / min(bwBox, bhBox),
                label = if (labels.size > 1) labels.getOrNull(b.cls) else null,
                confidence = b.conf,
                boxW = (bwBox / bw).coerceIn(0f, 1f),
                boxH = (bhBox / bh).coerceIn(0f, 1f),
            )
        }
    }

    protected data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val conf: Float, val cls: Int) {
        val area get() = (x2 - x1).coerceAtLeast(0f) * (y2 - y1).coerceAtLeast(0f)
    }

    /**
     * 계수용 중복 제거: 일반 NMS(IoU) + 포함 관계 제거(작은 박스 면적의 70% 이상이 겹치면 중복).
     * 큰 박스 안에 작은 박스가 겹쳐 나오는 경우(학습 부족 모델에서 흔함) 이중 계수를 막는다.
     */
    private fun suppress(boxes: List<Box>): List<Box> {
        val kept = ArrayList<Box>()
        for (b in boxes.sortedByDescending { it.conf }) {
            if (kept.none { overlap(it, b) }) kept += b
        }
        return kept
    }

    private fun overlap(a: Box, b: Box): Boolean {
        val ix = (min(a.x2, b.x2) - max(a.x1, b.x1)).coerceAtLeast(0f)
        val iy = (min(a.y2, b.y2) - max(a.y1, b.y1)).coerceAtLeast(0f)
        val inter = ix * iy
        if (inter <= 0f) return false
        val union = a.area + b.area - inter
        val iou = if (union <= 0f) 0f else inter / union
        val ios = inter / min(a.area, b.area).coerceAtLeast(1e-6f)
        return iou > IOU_TH || ios > IOS_TH
    }

    private fun sampleColor(bmp: Bitmap, cx: Float, cy: Float, w: Float, h: Float): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (dy in -2..2) for (dx in -2..2) {
            val x = (cx + dx * w * 0.08f).roundToInt().coerceIn(0, bmp.width - 1)
            val y = (cy + dy * h * 0.08f).roundToInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(x, y)
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    companion object {
        const val IOU_TH = 0.5f
        const val IOS_TH = 0.7f

        fun readLabels(context: Context, asset: String): List<String> = runCatching {
            context.assets.open(asset).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}

/** LiteRT/TFLite (float32) 모델. Colab(Linux) 에서 `format=tflite` 로 변환한 파일. */
class YoloTfliteDetector(context: Context, modelAsset: String, labels: List<String>) : YoloDetector(labels) {

    override val name: String = "YOLO AI 엔진 (TFLite)"
    override val inW: Int
    override val inH: Int
    override val nchw: Boolean
    private val interpreter: Interpreter

    init {
        val fd = context.assets.openFd(modelAsset)
        val buffer = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        interpreter = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(4) })
        val inT = interpreter.getInputTensor(0)
        require(inT.dataType() == DataType.FLOAT32) { "float32 입력 모델만 지원합니다" }
        val s = inT.shape()
        require(s.size == 4) { "4차원 입력 모델만 지원합니다" }
        nchw = s[1] == 3 && s[3] != 3
        inH = if (nchw) s[2] else s[1]
        inW = if (nchw) s[3] else s[2]
    }

    override fun infer(input: FloatArray): Pair<FloatArray, IntArray> {
        val inBuf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        inBuf.asFloatBuffer().put(input)
        val shape = interpreter.getOutputTensor(0).shape()
        val outBuf = ByteBuffer.allocateDirect(shape.fold(1) { a, b -> a * b } * 4).order(ByteOrder.nativeOrder())
        interpreter.run(inBuf, outBuf)
        outBuf.rewind()
        val out = FloatArray(outBuf.capacity() / 4)
        outBuf.asFloatBuffer().get(out)
        return out to shape
    }
}

/** ONNX 모델. Windows 에서도 `format=onnx` 로 바로 변환 가능. */
class YoloOnnxDetector(context: Context, modelAsset: String, labels: List<String>) : YoloDetector(labels) {

    override val name: String = "YOLO AI 엔진 (ONNX)"
    override val inW: Int
    override val inH: Int
    override val nchw: Boolean = true
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val bytes = context.assets.open(modelAsset).use { it.readBytes() }
        session = env.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
        inputName = session.inputNames.first()
        val info = session.inputInfo.getValue(inputName).info as ai.onnxruntime.TensorInfo
        val s = info.shape // [1,3,H,W]
        require(s.size == 4 && s[1] == 3L) { "[1,3,H,W] 입력 ONNX 모델만 지원합니다" }
        inH = if (s[2] > 0) s[2].toInt() else 640
        inW = if (s[3] > 0) s[3].toInt() else 640
    }

    override fun infer(input: FloatArray): Pair<FloatArray, IntArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, inH.toLong(), inW.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val outT = res.get(0) as OnnxTensor
                val shape = outT.info.shape.map { it.toInt() }.toIntArray()
                val fb = outT.floatBuffer
                val out = FloatArray(fb.remaining())
                fb.get(out)
                return out to shape
            }
        }
    }
}
