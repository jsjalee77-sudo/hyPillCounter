package com.example.hypillcounter.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

@Composable
fun ResultScreen(vm: PillCounterViewModel) {
    val bitmap = vm.sessionBitmap ?: return
    BackHandler { vm.retake() }

    val markers = vm.markers.toList()
    val summary = GroupPalette.summary(markers)
    val colors = GroupPalette.colors(summary)
    val count = markers.size
    val target = vm.target

    // 분석 완료 시 목표 일치/불일치 화면 플래시
    val flash = remember { Animatable(0f) }
    LaunchedEffect(vm.analysisSerial) {
        if (vm.analysisSerial > 0 && target != null) {
            flash.snapTo(0.45f)
            flash.animateTo(0f, tween(900))
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = vm::retake) { Text("← 다시 촬영") }
                Spacer(Modifier.weight(1f))
                Text(
                    if (vm.analyzing) "분석 중…" else "${vm.engineName} · ${vm.elapsedMs}ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CountBadge(
                    count = count,
                    target = target,
                    detail = "자동 ${vm.autoCount} · 추가 +${vm.addedCount} · 제외 −${vm.removedCount}",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = vm.targetText,
                    onValueChange = { v -> vm.targetText = v.filter(Char::isDigit).take(4) },
                    label = { Text("목표") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }

            if (summary.size > 1 || summary.any { it.first == GroupPalette.MANUAL }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    summary.forEach { (group, n) ->
                        AssistChip(
                            onClick = {},
                            label = { Text("$group ${n}정") },
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .background(Color(colors[group] ?: 0), CircleShape),
                                )
                            },
                        )
                    }
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
                MarkerImage(bitmap, markers, colors, vm::onImageTap, Modifier.fillMaxSize())
                if (vm.analyzing) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            Text(
                "알약을 터치하면 제외(−1), 빈 곳을 터치하면 추가(+1) · 두 손가락으로 확대",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("감도", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = vm.sensitivity,
                    onValueChange = { vm.sensitivity = it },
                    onValueChangeFinished = vm::reanalyze,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("${(vm.sensitivity * 100).toInt()}", style = MaterialTheme.typography.labelLarge)
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = vm::undo, enabled = vm.canUndo) { Text("되돌리기") }
                OutlinedButton(onClick = vm::resetToAuto, enabled = vm.canUndo) { Text("초기화") }
                Button(
                    onClick = vm::save,
                    enabled = !vm.saving && !vm.analyzing,
                    modifier = Modifier.weight(1f),
                ) { Text(if (vm.saving) "저장 중…" else "저장 및 완료") }
            }
        }

        if (flash.value > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(statusColor(count, target).copy(alpha = flash.value)),
            )
        }
    }
}

@Composable
fun CountBadge(count: Int, target: Int?, detail: String?, modifier: Modifier = Modifier) {
    val bg = when {
        target == null -> MaterialTheme.colorScheme.primary
        count == target -> Color(0xFF2E7D32)
        count < target -> Color(0xFFE65100)
        else -> Color(0xFFC62828)
    }
    val sub = when {
        target == null -> "목표 수량 미설정"
        count == target -> "목표 ${target}정 일치 ✓"
        count < target -> "목표 ${target}정 · ${target - count}정 부족"
        else -> "목표 ${target}정 · ${count - target}정 초과"
    }
    Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("총 ${count}정", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = Color.White, style = MaterialTheme.typography.titleSmall)
            if (detail != null) {
                Text(detail, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 확대/이동 가능한 이미지 + 번호 마커 + 터치 보정 */
@Composable
private fun MarkerImage(
    bitmap: Bitmap,
    markers: List<Marker>,
    colors: Map<String, Int>,
    onTap: (Float, Float, Float) -> Unit,
    modifier: Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val measurer = rememberTextMeasurer()
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier.clipToBounds().background(Color.Black)) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val fit = min(cw / bitmap.width, ch / bitmap.height)
        val dw = bitmap.width * fit
        val dh = bitmap.height * fit
        val ox = (cw - dw) / 2f
        val oy = (ch - dh) / 2f
        val touchPx = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(bitmap, cw, ch) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(1f, 6f)
                        val maxX = (cw * ns - cw) / 2f
                        val maxY = (ch * ns - ch) / 2f
                        val o = offset + pan
                        scale = ns
                        offset = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                    }
                }
                .pointerInput(bitmap, cw, ch) {
                    detectTapGestures { p ->
                        val c = Offset(cw / 2f, ch / 2f)
                        val content = (p - c - offset) / scale + c
                        val nx = (content.x - ox) / dw
                        val ny = (content.y - oy) / dh
                        if (nx in 0f..1f && ny in 0f..1f) onTap(nx, ny, touchPx / scale / dw)
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                Image(image, contentDescription = "촬영 이미지", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    markers.forEachIndexed { i, m ->
                        val col = Color(colors[m.group] ?: 0xFF00E676.toInt())
                        val center = Offset(ox + m.x * dw, oy + m.y * dh)
                        val r = max(4.dp.toPx(), m.radius * dw)
                        drawCircle(col, r, center, style = Stroke(max(1.5f, 2.dp.toPx() / scale)))
                        val badge = max(7.dp.toPx() / scale, r * 0.5f)
                        drawCircle(col.copy(alpha = 0.8f), badge, center)
                        val style = TextStyle(
                            color = Color.White,
                            fontSize = (badge * 1.05f / density / fontScale).sp,
                            fontWeight = FontWeight.Bold,
                        )
                        val layout = measurer.measure("${i + 1}", style)
                        drawText(
                            layout,
                            topLeft = Offset(
                                center.x - layout.size.width / 2f,
                                center.y - layout.size.height / 2f,
                            ),
                        )
                    }
                }
            }
        }

        if (scale > 1.01f) {
            TextButton(
                onClick = { scale = 1f; offset = Offset.Zero },
                modifier = Modifier.align(Alignment.TopEnd),
            ) { Text("확대 해제", color = Color.White) }
        }
    }
}
