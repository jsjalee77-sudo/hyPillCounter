package com.example.hypillcounter

import com.example.hypillcounter.detection.ClassicPillEngine
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * YOLO 형식 데이터셋(images/, labels/)으로 영상처리 엔진의 계수 정확도를 측정한다.
 * 환경변수 PILL_EVAL_DIR 에 데이터셋 폴더를 지정했을 때만 실행된다.
 *
 *   PILL_EVAL_DIR=ml/datasets/pills ./gradlew testDebugUnitTest --tests '*ClassicEngineEvalTest*' -i
 */
class ClassicEngineEvalTest {

    @Test
    fun countAccuracyOnDataset() {
        val dir = System.getenv("PILL_EVAL_DIR")?.let(::File)
        assumeTrue("PILL_EVAL_DIR 미지정 → 건너뜀", dir != null && dir.isDirectory)
        val images = File(dir, "images").walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            .sortedBy { it.path }.toList()
        var exact = 0
        var errSum = 0
        val lines = StringBuilder()
        for (img in images) {
            val rel = img.relativeTo(File(dir, "images")).path
            val label = File(File(dir, "labels"), rel.substringBeforeLast('.') + ".txt")
            val truth = if (label.exists()) label.readLines().count { it.isNotBlank() } else 0
            val bi = ImageIO.read(img) ?: continue
            val w = bi.width
            val h = bi.height
            val px = bi.getRGB(0, 0, w, h, null, 0, w)
            val pills = ClassicPillEngine.detect(px, w, h)
            val got = pills.size
            System.getenv("PILL_DEBUG_OUT")?.let { out ->
                // 인식 위치를 빨간 원으로 그린 디버그 이미지 저장
                val g = bi.createGraphics()
                g.color = java.awt.Color.RED
                g.stroke = java.awt.BasicStroke(2f)
                for (p in pills) {
                    val rr = (p.radius * w).toInt().coerceAtLeast(3)
                    g.drawOval((p.x * w).toInt() - rr, (p.y * h).toInt() - rr, rr * 2, rr * 2)
                }
                g.dispose()
                File(out).mkdirs()
                ImageIO.write(bi, "png", File(out, img.nameWithoutExtension + ".png"))
            }
            if (got == truth) exact++
            errSum += abs(got - truth)
            lines.append("  $rel: 정답 $truth / 인식 $got${if (got != truth) "  ✗" else ""}\n")
        }
        println(lines)
        println("[영상처리 엔진] ${images.size}장 중 개수 일치 $exact (${"%.1f".format(exact * 100.0 / images.size)}%), 평균 오차 ${"%.2f".format(errSum.toDouble() / images.size)}정")
    }
}
