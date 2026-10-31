package ai.roadscan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.camera.core.ImageAnalysis
import ai.roadscan.app.data.SessionEntity
import ai.roadscan.app.report.ReportFormat
import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.model.AgentState
import ai.roadscan.core.model.ConfirmationStatus
import ai.roadscan.core.model.DefectType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RoadScanRoot(viewModel: ScanViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF12161B)) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
                    label = { Text("Сканирование") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    label = { Text("Отчёт") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> ScanScreen(
                state = state,
                analyzer = viewModel.analyzer,
                onStart = viewModel::startSession,
                onStop = viewModel::stopSession,
                padding = padding,
            )

            else -> ReportScreen(
                state = state,
                onExport = viewModel::export,
                onOpenSession = viewModel::openSession,
                padding = padding,
            )
        }
    }
}

@Composable
private fun ScanScreen(
    state: UiState,
    analyzer: ImageAnalysis.Analyzer,
    onStart: () -> Unit,
    onStop: () -> Unit,
    padding: PaddingValues,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color.Black),
    ) {
        if (state.running) {
            CameraPreview(analyzer = analyzer, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF141A20), Color(0xFF0B0E11)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Камера выключена", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Начните смену — агент проверит GPS, диск и температуру",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xCC05070A),
                        0.25f to Color.Transparent,
                        0.65f to Color.Transparent,
                        1f to Color(0xE605070A),
                    ),
                )
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateChip(state.agentState)
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            if (state.modelReady) "YOLOv8" else "модель не найдена",
                            fontSize = 11.sp,
                        )
                    },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "%.0f км/ч".format(Locale.US, state.speedKmh),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }

            Spacer(Modifier.weight(1f))

            if (state.events.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC12161B)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        state.events.take(3).forEach { event ->
                            Text(
                                "• $event",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            MetricsPanel(state)
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = if (state.running) onStop else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.running) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(if (state.running) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.running) "Завершить и рассчитать" else "Начать смену",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StateChip(state: AgentState) {
    val (label, color) = when (state) {
        AgentState.IDLE -> "Ожидание" to Color(0xFF8693A0)
        AgentState.CALIBRATING -> "Калибровка" to Color(0xFFFFC857)
        AgentState.SCANNING -> "Сканирование" to Color(0xFF7CD0B0)
        AgentState.THROTTLED -> "Троттлинг" to Color(0xFFFF9F43)
        AgentState.POST_PROCESSING -> "Обработка" to Color(0xFF6BA8FF)
        AgentState.ERROR -> "Ошибка" to Color(0xFFFF6B6B)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MetricsPanel(state: UiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xD912161B)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Metric("FPS", "${state.targetFps}", Modifier.weight(1f))
                Metric("Кадры", "${state.frames}", Modifier.weight(1f))
                Metric("Подтв.", "${state.confirmed}", Modifier.weight(1f), Color(0xFF7CD0B0))
                Metric("Visual", "${state.visualOnly}", Modifier.weight(1f), Color(0xFFFFC857))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Metric(
                    "Батарея",
                    "${state.batteryPercent}%",
                    Modifier.weight(1f),
                    if (state.batteryPercent <= 20) Color(0xFFFF6B6B) else Color.Unspecified,
                )
                Metric(
                    "SoC",
                    if (state.cpuTempC.isNaN()) "—" else "%.0f°C".format(Locale.US, state.cpuTempC),
                    Modifier.weight(1f),
                    if (state.cpuTempC >= 42f) Color(0xFFFF9F43) else Color.Unspecified,
                )
                Metric(
                    "GPS",
                    if (state.gpsAccuracyM.isNaN()) "нет" else "±%.0f м".format(Locale.US, state.gpsAccuracyM),
                    Modifier.weight(1f),
                )
                Metric("Диск", "${state.freeDiskMb} МБ", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        )
    }
}

@Composable
private fun ReportScreen(
    state: UiState,
    onExport: (ReportFormat) -> Unit,
    onOpenSession: (SessionEntity) -> Unit,
    padding: PaddingValues,
) {
    val report = state.report
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("Отчёт", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Офлайн-аналитика: кластеры, смета, GeoJSON",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (report == null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Пока нет активного отчёта", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Завершите смену или откройте сохранённую сессию ниже.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            item { SummaryCards(report) }
            item { ExportRow(busy = state.busy, onExport = onExport) }
            item { EstimateCard(report) }
            item { ClusterCard(report) }
            item { StatsCard(report) }
        }

        item {
            Text(
                "Сохранённые сессии (${state.sessions.size})",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(state.sessions, key = { it.id }) { session ->
            SessionRow(session, onClick = { onOpenSession(session) })
        }
    }
}

@Composable
private fun SummaryCards(report: SessionReport) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Площадь", "%.1f м²".format(Locale.US, report.estimate.totalAreaM2), Modifier.weight(1f))
            Kpi("Фрезеровка", "%.2f м³".format(Locale.US, report.estimate.totalMillingVolumeM3), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Асфальт", "%.2f т".format(Locale.US, report.estimate.totalAsphaltTons), Modifier.weight(1f))
            Kpi("Смета", "%.0f ₽".format(Locale.US, report.estimate.totalRub), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ExportRow(busy: Boolean, onExport: (ReportFormat) -> Unit) {
    Column {
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Формирую документы…", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportButton("PDF", Icons.Filled.PictureAsPdf, Modifier.weight(1f)) { onExport(ReportFormat.PDF) }
            ExportButton("Excel", Icons.Filled.TableChart, Modifier.weight(1f)) { onExport(ReportFormat.XLSX) }
            ExportButton("GeoJSON", Icons.Filled.Map, Modifier.weight(1f)) { onExport(ReportFormat.GEOJSON) }
        }
    }
}

@Composable
private fun ExportButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun EstimateCard(report: SessionReport) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Смета", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            report.estimate.lines.forEach { line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "%.2f %s × %.2f ₽".format(Locale.US, line.quantity, line.unit, line.unitPriceRub),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "%.0f ₽".format(Locale.US, line.totalRub),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "ВСЕГО: %.0f ₽ (с НДС)".format(Locale.US, report.estimate.totalRub),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ClusterCard(report: SessionReport) {
    val clusters = report.clusters + report.visualOnlyClusters
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Кластеры (${clusters.size})", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            clusters.take(12).forEach { cluster ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (cluster.confirmed) Color(0xFF7CD0B0) else Color(0xFFFFC857),
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(typeName(cluster.type), fontSize = 12.sp)
                        Text(
                            "%.2f м² · %.0f мм · severity %d".format(
                                Locale.US,
                                cluster.areaM2,
                                cluster.maxDepthMm,
                                cluster.maxSeverity,
                            ),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (cluster.status == ConfirmationStatus.CONFIRMED_PHYSICAL) "confirmed" else "visual",
                        fontSize = 10.sp,
                        color = if (cluster.confirmed) Color(0xFF7CD0B0) else Color(0xFFFFC857),
                    )
                }
            }
            if (clusters.size > 12) {
                Text(
                    "… ещё ${clusters.size - 12}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(report: SessionReport) {
    val stats = report.stats
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Метрики", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            StatRow("Время смены", "${report.durationMs / 1000 / 60} мин")
            StatRow("Кадров обработано", "${stats.framesAnalyzed}")
            StatRow("Отфильтровано ложных", "${stats.rejectedAsFalsePositive}")
            StatRow("Дубликатов слито", "${stats.duplicatesMerged}")
            StatRow("Задержка кадра", "%.1f мс".format(Locale.US, stats.averageLatencyMs))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SessionRow(session: SessionEntity, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(session.id, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(session.startedAtMs)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "%.0f ₽".format(Locale.US, session.totalRub),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun typeName(type: DefectType): String = when (type) {
    DefectType.POTHOLE -> "Яма"
    DefectType.CRACK_NETWORK -> "Сеть трещин"
    DefectType.RUTTING -> "Колея"
    DefectType.PATCH -> "Заплатка"
    DefectType.MANHOLE_COVER -> "Люк"
    DefectType.UNKNOWN -> "Неизвестно"
}
