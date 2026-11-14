package com.jeongpharm.pillcounter.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** CameraX 미리보기 + 고해상도 촬영 + 실시간 분석 프레임을 묶어 관리한다. */
class PillCamera(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var released = false

    val hasFlash: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true

    fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        isLiveEnabled: () -> Boolean,
        onFrame: (Bitmap) -> Unit,
        onBound: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        if (!released && isLiveEnabled()) onFrame(proxy.toUprightBitmap())
                    } catch (t: Throwable) {
                        Log.w(TAG, "live frame failed", t)
                    } finally {
                        proxy.close()
                    }
                }
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(capture)
                    .addUseCase(analysis)
                    .apply { previewView.viewPort?.let { setViewPort(it) } }
                    .build()
                p.unbindAll()
                camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                imageCapture = capture
                onBound()
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setTorch(on: Boolean) {
        camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(on) }
    }

    /** 촬영 결과(정방향 Bitmap)를 백그라운드 스레드에서 전달한다. */
    fun takePicture(onBitmap: (Bitmap) -> Unit, onFailure: (Throwable) -> Unit) {
        val cap = imageCapture ?: return onFailure(IllegalStateException("카메라가 준비되지 않았습니다"))
        cap.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = try {
                    image.toUprightBitmap()
                } catch (t: Throwable) {
                    onFailure(t)
                    return
                } finally {
                    image.close()
                }
                onBitmap(bmp)
            }

            override fun onError(exception: ImageCaptureException) = onFailure(exception)
        })
    }

    fun release() {
        released = true
        provider?.unbindAll()
        camera = null
        imageCapture = null
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    private companion object {
        const val TAG = "PillCamera"
    }
}
