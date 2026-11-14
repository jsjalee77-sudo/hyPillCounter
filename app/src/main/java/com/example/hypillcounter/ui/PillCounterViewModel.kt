package com.example.hypillcounter.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hypillcounter.camera.cropNormalized
import com.example.hypillcounter.camera.loadBitmapFromUri
import com.example.hypillcounter.camera.scaleToMax
import com.example.hypillcounter.data.CountRecord
import com.example.hypillcounter.data.CountRepository
import com.example.hypillcounter.data.DatasetExporter
import com.example.hypillcounter.data.MarkerJson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.hypillcounter.detection.DetectorFactory
import com.example.hypillcounter.detection.Pill
import com.example.hypillcounter.detection.PillDetector
import com.example.hypillcounter.detection.PillGrouping
import com.example.hypillcounter.util.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

sealed interface Screen {
    data object Camera : Screen
    data object Result : Screen
    data object History : Screen
    data class Detail(val record: CountRecord) : Screen
}

data class LiveResult(val pills: List<Pill>)

private sealed interface Edit {
    data class Added(val marker: Marker) : Edit
    data class Removed(val marker: Marker, val index: Int) : Edit
}

class PillCounterViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CountRepository(app)
    private val detector: PillDetector by lazy { DetectorFactory.create(app) }
    private val mainHandler = Handler(Looper.getMainLooper())

    val engineName: String get() = detector.name

    var screen by mutableStateOf<Screen>(Screen.Camera)
    var message by mutableStateOf<String?>(null)

    // ── 촬영 화면 ──
    var targetText by mutableStateOf("")
    val target: Int? get() = targetText.toIntOrNull()?.takeIf { it > 0 }
    var sensitivity by mutableFloatStateOf(0.5f)
    var liveEnabled by mutableStateOf(true)
    var torchOn by mutableStateOf(false)
    var liveResult by mutableStateOf<LiveResult?>(null)
        private set
    var capturing by mutableStateOf(false)
        private set

    /** 미리보기 화면 기준 가이드 박스(정규화). 분석 스레드에서 읽는다. */
    @Volatile var guideRect: RectF = RectF(0.05f, 0.2f, 0.95f, 0.75f)

    // ── 결과/보정 화면 ──
    var sessionBitmap by mutableStateOf<Bitmap?>(null)
        private set
    val markers = mutableStateListOf<Marker>()
    private var autoMarkers: List<Marker> = emptyList()
    var autoCount by mutableIntStateOf(0)
        private set
    var analyzing by mutableStateOf(false)
        private set
    var elapsedMs by mutableLongStateOf(0L)
        private set
    /** 분석 완료 시 증가 (화면 플래시 트리거용) */
    var analysisSerial by mutableIntStateOf(0)
        private set
    var saving by mutableStateOf(false)
        private set
    private val undoStack = mutableStateListOf<Edit>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    private var nextId = 1L
    private var analysisToken = 0

    val addedCount: Int get() = markers.count { it.manual }
    val removedCount: Int get() = (autoCount - markers.count { !it.manual }).coerceAtLeast(0)

    // ── 이력 ──
    var history by mutableStateOf<List<CountRecord>>(emptyList())
        private set

    // ───────────────────────────── 촬영 ─────────────────────────────

    /** CameraX 분석 스레드에서 호출된다. */
    fun onLiveFrame(frame: Bitmap) {
        if (screen != Screen.Camera) return
        val crop = frame.cropNormalized(guideRect)
        val pills = runCatching { detector.detect(crop, sensitivity, LIVE_MAX_SIDE) }.getOrDefault(emptyList())
        mainHandler.post { if (liveEnabled && screen == Screen.Camera) liveResult = LiveResult(pills) }
    }

    fun toggleLive() {
        liveEnabled = !liveEnabled
        if (!liveEnabled) liveResult = null
    }

    fun onCaptureStarted() {
        capturing = true
    }

    /** 촬영 스레드에서 호출된다. */
    fun onCaptured(bitmap: Bitmap) {
        val cropped = bitmap.cropNormalized(guideRect).scaleToMax(STORE_MAX_SIDE)
        mainHandler.post {
            capturing = false
            startSession(cropped)
        }
    }

    fun onCaptureFailed(t: Throwable) {
        mainHandler.post {
            capturing = false
            message = "촬영 실패: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun importImage(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { loadBitmapFromUri(getApplication(), uri, STORE_MAX_SIDE) }
            if (bmp == null) message = "이미지를 불러올 수 없습니다" else startSession(bmp)
        }
    }

    // ───────────────────────────── 분석/보정 ─────────────────────────────

    private fun startSession(bitmap: Bitmap) {
        sessionBitmap = bitmap
        markers.clear()
        undoStack.clear()
        autoCount = 0
        liveResult = null
        screen = Screen.Result
        analyze(bitmap)
    }

    fun reanalyze() {
        val bmp = sessionBitmap ?: return
        if (undoStack.isNotEmpty()) message = "재분석으로 수동 보정 내용이 초기화되었습니다"
        analyze(bmp)
    }

    private fun analyze(bitmap: Bitmap) {
        val token = ++analysisToken
        analyzing = true
        val sens = sensitivity
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val start = System.nanoTime()
                val pills = runCatching { detector.detect(bitmap, sens, ANALYSIS_MAX_SIDE) }
                val ms = (System.nanoTime() - start) / 1_000_000
                pills to ms
            }
            if (token != analysisToken) return@launch
            analyzing = false
            val pills = result.first.getOrElse {
                message = "분석 실패: ${it.message}"
                emptyList()
            }
            elapsedMs = result.second
            val aspect = bitmap.height.toFloat() / bitmap.width
            autoMarkers = pills.map {
                Marker(nextId++, it.x, it.y, it.radius, PillGrouping.groupOf(it), boxW = it.boxW, boxH = it.boxH)
            }.inReadingOrder(aspect)
            autoCount = autoMarkers.size
            markers.clear()
            markers.addAll(autoMarkers)
            undoStack.clear()
            analysisSerial++
            target?.let { Haptics.notify(getApplication(), markers.size == it) }
        }
    }

    /**
     * 결과 이미지 터치: 마커 위면 제외(-1), 빈 곳이면 추가(+1).
     * @param touchRadius 터치 판정 반경 (이미지 너비 기준 정규화)
     */
    fun onImageTap(nx: Float, ny: Float, touchRadius: Float) {
        val bmp = sessionBitmap ?: return
        if (analyzing) return
        val aspect = bmp.height.toFloat() / bmp.width
        var hit = -1
        var hitD = Float.MAX_VALUE
        markers.forEachIndexed { i, m ->
            val dx = m.x - nx
            val dy = (m.y - ny) * aspect
            val d = sqrt(dx * dx + dy * dy)
            if (d < maxOf(m.radius, touchRadius) && d < hitD) {
                hit = i
                hitD = d
            }
        }
        val before = markers.size
        if (hit >= 0) {
            val m = markers.removeAt(hit)
            undoStack += Edit.Removed(m, hit)
        } else {
            fun median(v: List<Float>, def: Float) = v.sorted().let { if (it.isEmpty()) def else it[it.size / 2] }
            val r = median(markers.map { it.radius }, 0.03f)
            val m = Marker(
                nextId++, nx, ny, r, GroupPalette.MANUAL, manual = true,
                boxW = median(markers.filter { it.boxW > 0f }.map { it.boxW }, 0f),
                boxH = median(markers.filter { it.boxH > 0f }.map { it.boxH }, 0f),
            )
            markers += m
            undoStack += Edit.Added(m)
        }
        val t = target
        if (t != null && before != t && markers.size == t) Haptics.notify(getApplication(), true)
    }

    fun undo() {
        when (val e = undoStack.removeLastOrNull() ?: return) {
            is Edit.Added -> markers.removeAll { it.id == e.marker.id }
            is Edit.Removed -> markers.add(e.index.coerceIn(0, markers.size), e.marker)
        }
    }

    fun resetToAuto() {
        markers.clear()
        markers.addAll(autoMarkers)
        undoStack.clear()
    }

    fun retake() {
        analysisToken++
        sessionBitmap = null
        markers.clear()
        undoStack.clear()
        analyzing = false
        screen = Screen.Camera
    }

    fun save() {
        val bmp = sessionBitmap ?: return
        if (saving || analyzing) return
        saving = true
        val snapshot = markers.toList()
        val count = snapshot.size
        val tgt = target
        val ts = System.currentTimeMillis()
        val record = CountRecord(
            timestamp = ts,
            count = count,
            target = tgt,
            autoCount = autoCount,
            added = addedCount,
            removed = removedCount,
            groups = GroupPalette.summaryText(snapshot),
            engine = engineName,
            markersJson = MarkerJson.encode(snapshot),
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val marked = MarkedImageRenderer.render(bmp, snapshot, count, tgt, ts)
                    repository.insert(bmp, marked, record)
                }
            }.onSuccess {
                message = "저장 완료 · 총 ${count}정"
                retake()
            }.onFailure {
                message = "저장 실패: ${it.message}"
            }
            saving = false
        }
    }

    // ───────────────────────────── 이력 ─────────────────────────────

    fun openHistory() {
        screen = Screen.History
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            history = withContext(Dispatchers.IO) { runCatching { repository.all() }.getOrDefault(emptyList()) }
        }
    }

    fun openDetail(record: CountRecord) {
        screen = Screen.Detail(record)
    }

    fun deleteRecord(record: CountRecord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(record) }
            message = "기록을 삭제했습니다"
            screen = Screen.History
            loadHistory()
        }
    }

    var exporting by mutableStateOf(false)
        private set
    /** 공유할 학습 데이터 zip (화면에서 공유 시트를 띄운 뒤 consumeExport() 호출) */
    var exportFile by mutableStateOf<File?>(null)
        private set

    fun exportDataset() {
        if (exporting) return
        exporting = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val records = repository.all().filter { it.markersJson.isNotBlank() }
                    val dir = File(getApplication<Application>().cacheDir, "exports").apply {
                        deleteRecursively(); mkdirs()
                    }
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    DatasetExporter.export(records, File(dir, "pill_dataset_$stamp.zip"))
                }
            }
            exporting = false
            result.onSuccess {
                if (it.images == 0) {
                    message = "내보낼 학습 데이터가 없습니다 (이번 버전 이후 저장한 기록만 포함됩니다)"
                } else {
                    message = "학습 데이터 ${it.images}장 · 알약 ${it.boxes}개"
                    exportFile = it.file
                }
            }.onFailure { message = "내보내기 실패: ${it.message}" }
        }
    }

    fun consumeExport() {
        exportFile = null
    }

    /** 이력 상세에서 저장된 마커 수 확인용 */
    fun markerCount(record: CountRecord): Int =
        runCatching { MarkerJson.decode(record.markersJson).size }.getOrDefault(0)

    fun backToCamera() {
        screen = Screen.Camera
    }

    private companion object {
        const val LIVE_MAX_SIDE = 360
        const val ANALYSIS_MAX_SIDE = 800
        const val STORE_MAX_SIDE = 1600
    }
}
