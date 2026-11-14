package com.example.hypillcounter.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.hypillcounter.camera.PillCamera
import kotlin.math.max
import kotlin.math.min

@Composable
fun CameraScreen(vm: PillCounterViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importImage(uri)
    }

    val camera = remember { PillCamera(context) }
    DisposableEffect(camera) { onDispose { camera.release() } }
    var cameraReady by remember { mutableStateOf(false) }
    LaunchedEffect(vm.torchOn, cameraReady) { if (cameraReady) camera.setTorch(vm.torchOn) }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val guide = remember(containerSize) { guideRectFor(containerSize) }
    SideEffect { vm.guideRect = guide }

    val target = vm.target
    val live = vm.liveResult?.takeIf { vm.liveEnabled }
    val liveCount = live?.pills?.size
    val liveColor = statusColor(liveCount, target)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it },
    ) {
        if (hasPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        doOnLayout {
                            camera.bind(
                                owner = lifecycleOwner,
                                previewView = this,
                                isLiveEnabled = { vm.liveEnabled },
                                onFrame = vm::onLiveFrame,
                                onBound = { cameraReady = true },
                                onError = { vm.message = "카메라를 시작할 수 없습니다: ${it.message}" },
                            )
                        }
                    }
                },
            )
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "알약 촬영을 위해 카메라 권한이 필요합니다.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("권한 허용") }
            }
        }

        GuideOverlay(guide, live, liveColor, Modifier.fillMaxSize())

        // ── 상단: 목표 수량 / 실시간 개수 ──
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = vm.targetText,
                        onValueChange = { v -> vm.targetText = v.filter(Char::isDigit).take(4) },
                        label = { Text("목표 수량(정)") },
                        placeholder = { Text("예: 30") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.width(150.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(
                            if (vm.liveEnabled) "실시간 계수" else "실시간 꺼짐",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = liveCount?.let { "${it}정" } ?: "—",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (liveCount == null) MaterialTheme.colorScheme.onSurface else liveColor,
                        )
                    }
                }
            }
        }

        // ── 하단: 옵션 + 촬영 버튼 ──
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val warning = live?.warning
            Text(
                warning ?: "트레이를 가이드 박스 안에 맞추고 알약이 겹치지 않게 펼쳐주세요",
                color = if (warning != null) Color(0xFFFFB74D) else Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = vm.liveEnabled,
                    onClick = vm::toggleLive,
                    label = { Text("실시간 계수") },
                    colors = chipColors(),
                )
                FilterChip(
                    selected = vm.torchOn,
                    onClick = { vm.torchOn = !vm.torchOn },
                    label = { Text("조명") },
                    colors = chipColors(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = vm::openHistory, modifier = Modifier.width(88.dp)) {
                    Text("이력", color = Color.White, fontSize = 16.sp)
                }
                ShutterButton(
                    busy = vm.capturing,
                    enabled = hasPermission && cameraReady && !vm.capturing,
                    onClick = {
                        focusManager.clearFocus()
                        vm.onCaptureStarted()
                        camera.takePicture(onBitmap = vm::onCaptured, onFailure = vm::onCaptureFailed)
                    },
                )
                TextButton(
                    onClick = {
                        pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.width(88.dp),
                ) {
                    Text("갤러리", color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Black.copy(alpha = 0.4f),
    labelColor = Color.White,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun ShutterButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(80.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun GuideOverlay(guide: RectF, live: LiveResult?, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        val l = guide.left * size.width
        val t = guide.top * size.height
        val r = guide.right * size.width
        val b = guide.bottom * size.height
        val dim = Color.Black.copy(alpha = 0.45f)
        drawRect(dim, Offset.Zero, Size(size.width, t))
        drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(dim, Offset(0f, t), Size(l, b - t))
        drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
        drawRoundRect(
            color = if (live == null) Color.White else accent,
            topLeft = Offset(l, t),
            size = Size(r - l, b - t),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(3.dp.toPx()),
        )
        live?.pills?.forEach { p ->
            drawCircle(
                color = Color(0xFF00E5FF),
                radius = max(3.dp.toPx(), p.radius * (r - l) * 0.3f),
                center = Offset(l + p.x * (r - l), t + p.y * (b - t)),
            )
        }
    }
}

/** 화면 크기에 맞춘 가이드 박스 (정규화 좌표) */
private fun guideRectFor(size: IntSize): RectF {
    if (size.width == 0 || size.height == 0) return RectF(0.05f, 0.2f, 0.95f, 0.75f)
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val gw = w * 0.9f
    val gh = min(h * 0.56f, gw * 1.25f)
    val cx = w / 2f
    val cy = h * 0.5f
    return RectF((cx - gw / 2) / w, (cy - gh / 2) / h, (cx + gw / 2) / w, (cy + gh / 2) / h)
}

fun statusColor(count: Int?, target: Int?): Color = when {
    count == null || target == null -> Color(0xFF00E676)
    count == target -> Color(0xFF00C853)
    count < target -> Color(0xFFFF9100)
    else -> Color(0xFFFF1744)
}
