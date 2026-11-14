package com.example.hypillcounter.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hypillcounter.camera.decodeSampled
import com.example.hypillcounter.data.CountRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREA)
private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

@Composable
private fun rememberImage(path: String, maxSide: Int): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, path, maxSide) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, maxSide)?.asImageBitmap() }
    }
    return img
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("← 뒤로") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        action()
    }
}

@Composable
fun HistoryScreen(vm: PillCounterViewModel) {
    BackHandler { vm.backToCamera() }
    val grouped = vm.history.groupBy { dateFmt.format(Date(it.timestamp)) }
    val context = LocalContext.current
    val exportFile = vm.exportFile
    LaunchedEffect(exportFile) {
        if (exportFile != null) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, exportFile.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "학습 데이터 보내기"))
            vm.consumeExport()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        ScreenHeader("계수 이력", vm::backToCamera) {
            TextButton(onClick = vm::exportDataset, enabled = !vm.exporting && vm.history.isNotEmpty()) {
                Text(if (vm.exporting) "내보내는 중…" else "학습 데이터 내보내기")
            }
        }
        if (vm.history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("저장된 계수 기록이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            grouped.forEach { (date, records) ->
                item(key = "h_$date") {
                    Text(
                        "$date · ${records.size}건",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(records, key = { it.id }) { rec -> HistoryRow(rec) { vm.openDetail(rec) } }
            }
        }
    }
}

@Composable
private fun HistoryRow(rec: CountRecord, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val thumb = rememberImage(rec.markedPath, 240)
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray),
            ) {
                if (thumb != null) {
                    Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(timeFmt.format(Date(rec.timestamp)), style = MaterialTheme.typography.labelMedium)
                Text("${rec.count}정", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (rec.groups.isNotBlank()) {
                    Text(
                        rec.groups,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            StatusPill(rec.count, rec.target)
        }
    }
}

@Composable
private fun StatusPill(count: Int, target: Int?) {
    val (text, color) = when {
        target == null -> "목표 없음" to Color.Gray
        count == target -> "일치" to Color(0xFF2E7D32)
        count < target -> "${target - count} 부족" to Color(0xFFE65100)
        else -> "${count - target} 초과" to Color(0xFFC62828)
    }
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun HistoryDetailScreen(vm: PillCounterViewModel, rec: CountRecord) {
    BackHandler { vm.openHistory() }
    var confirmDelete by remember { mutableStateOf(false) }
    val image = rememberImage(rec.markedPath, 2048)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        ScreenHeader("계수 상세", vm::openHistory)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            CountBadge(rec.count, rec.target, detail = null, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            if (image != null) {
                Image(
                    image,
                    contentDescription = "마킹 이미지",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            InfoRow("계수 일시", fullFmt.format(Date(rec.timestamp)))
            InfoRow("인식 수량", "${rec.count}정")
            InfoRow("목표 수량", rec.target?.let { "${it}정" } ?: "미설정")
            InfoRow("자동 인식", "${rec.autoCount}정")
            InfoRow("수동 보정", "추가 +${rec.added} / 제외 −${rec.removed}")
            if (rec.groups.isNotBlank()) InfoRow("종류별", rec.groups)
            InfoRow("엔진", rec.engine)
            InfoRow("학습 라벨", if (rec.markersJson.isBlank()) "없음 (이전 버전 기록)" else "알약 위치 ${vm.markerCount(rec)}개 저장됨")
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("기록 삭제", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("기록 삭제") },
            text = { Text("이 계수 기록과 이미지를 삭제할까요? 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteRecord(rec) }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
