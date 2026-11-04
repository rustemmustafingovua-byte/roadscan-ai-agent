package ai.roadscan.app.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.camera.core.ImageAnalysis
import ai.roadscan.app.R
import ai.roadscan.app.data.SessionEntity
import ai.roadscan.app.report.ReportFormat
import ai.roadscan.core.agent.SessionReport
import ai.roadscan.core.model.AgentState
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.GeoPoint
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// Палитра HUD: тёмное стекло поверх кадра, оранжевый акцент, светофор статусов.
private val PanelBg = Color(0xF50A0E12)
private val PanelBorder = Color(0xFF2B333C)
private val Accent = Color(0xFFFF7A33)
private val Good = Color(0xFF7CD0B0)
private val Warn = Color(0xFFFFC857)
private val Bad = Color(0xFFFF6B6B)
private val Muted = Color(0xFF8A97A3)

@Composable
fun RoadScanRoot(
    viewModel: ScanViewModel,
    incomingVideo: Uri? = null,
    onIncomingVideoHandled: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val videoViewModel: VideoViewModel = viewModel()
    val videoState by videoViewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    // «Поделиться» видео из галереи → сразу в видеостудию.
    LaunchedEffect(incomingVideo) {
        if (incomingVideo != null) {
            tab = 1
            videoViewModel.select(incomingVideo)
            onIncomingVideoHandled()
        }
    }

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
                    label = { Text(stringResource(R.string.nav_scan)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Movie, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_video)) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_report)) },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings)) },
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
                onBackgroundMode = viewModel::enterBackgroundMode,
                onExitBackgroundMode = viewModel::exitBackgroundMode,
                onDismissBatteryOffer = viewModel::dismissBatteryOffer,
                padding = padding,
            )

            1 -> VideoScreen(
                state = videoState,
                onPick = videoViewModel::select,
                onMode = videoViewModel::setMode,
                onStart = videoViewModel::start,
                onCancel = videoViewModel::cancel,
                onShare = videoViewModel::share,
                onReset = videoViewModel::reset,
                onClearMessage = videoViewModel::clearMessage,
                padding = padding,
            )

            2 -> ReportScreen(
                state = state,
                onExport = viewModel::export,
                onOpenSession = viewModel::openSession,
                padding = padding,
            )

            else -> SettingsScreen(
                state = state,
                onSaveCalibration = viewModel::saveCalibration,
                onSaveMeta = viewModel::saveMeta,
                onActivateLicense = viewModel::activateLicense,
                onDeactivateLicense = viewModel::deactivateLicense,
                onLoadDemo = viewModel::loadDemoReport,
                padding = padding,
            )
        }
    }
}

// --- экран сканирования / HUD ---

@Composable
private fun ScanScreen(
    state: UiState,
    analyzer: ImageAnalysis.Analyzer,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onBackgroundMode: () -> Unit,
    onExitBackgroundMode: () -> Unit,
    onDismissBatteryOffer: () -> Unit,
    padding: PaddingValues,
) {
    val nowMs = rememberClockMs()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color.Black),
    ) {
        if (state.running) {
            CameraPreview(analyzer = analyzer, modifier = Modifier.fillMaxSize())
        } else {
            IdleBackdrop()
        }

        DetectionOverlay(state)

        Column(Modifier.fillMaxSize()) {
            HudHeader(state, nowMs)
            Row(Modifier.fillMaxWidth().weight(1f)) {
                HudSidePanel(
                    state = state,
                    modifier = Modifier
                        .width(150.dp)
                        .fillMaxHeight()
                        .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                )
                Spacer(Modifier.weight(1f))
            }
            HudFooter(
                state = state,
                onStart = onStart,
                onStop = onStop,
                onExitBackgroundMode = onExitBackgroundMode,
            )
        }

        // ТЗ п. 3.2: при низком заряде агент предлагает выбор режима.
        if (state.batteryOffer) {
            BatteryOfferDialog(
                percent = state.batteryOfferPercent,
                onBackgroundMode = onBackgroundMode,
                onFinish = onStop,
                onDismiss = onDismissBatteryOffer,
            )
        }
    }
}

/**
 * Предложение при низком заряде: фоновый режим с минимальным
 * энергопотреблением либо сохранение путевого лога и завершение смены.
 */
@Composable
private fun BatteryOfferDialog(
    percent: Int,
    onBackgroundMode: () -> Unit,
    onFinish: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.battery_low_title)) },
        text = { Text(stringResource(R.string.battery_low_message, percent)) },
        confirmButton = {
            Button(
                onClick = onBackgroundMode,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) {
                Text(stringResource(R.string.battery_low_background))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.battery_low_continue))
                }
                TextButton(onClick = onFinish) {
                    Text(
                        stringResource(R.string.battery_low_finish),
                        color = Bad,
                    )
                }
            }
        },
    )
}

@Composable
private fun IdleBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF141A20), Color(0xFF070A0D))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(start = 150.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Videocam,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.camera_off_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.camera_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

/** Часы HUD: обновление раз в секунду, без перерисовки кадра. */
@Composable
private fun rememberClockMs(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return now
}

@Composable
private fun HudHeader(state: UiState, nowMs: Long) {
    val city = state.city.ifBlank { stringResource(R.string.hud_city_unknown) }
        .uppercase(Locale.getDefault())
    val coordinates = if (state.latitude.isNaN() || state.longitude.isNaN()) {
        stringResource(R.string.metric_none)
    } else {
        stringResource(
            R.string.hud_coords,
            abs(state.latitude),
            if (state.latitude >= 0) "N" else "S",
            abs(state.longitude),
            if (state.longitude >= 0) "E" else "W",
        )
    }
    val time = remember(nowMs) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMs)) }
    val date = remember(nowMs) { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(nowMs)) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xF2050709), Color(0x00050709)),
                ),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.running) Good else Warn),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                stringResource(R.string.hud_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = Color.White,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "($city)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Accent,
            )
            Spacer(Modifier.weight(1f))
            StateChip(state.agentState)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.street.ifBlank { stringResource(R.string.hud_street_unknown) },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    coordinates,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Muted,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%.0f".format(Locale.US, state.speedKmh),
                        fontSize = 32.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.hud_speed_unit),
                        fontSize = 10.sp,
                        color = Muted,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                Text(
                    time,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                )
                Text(date, fontSize = 9.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun HudSidePanel(state: UiState, modifier: Modifier) {
    val penalty = state.confirmed * 3 + state.visualOnly
    val score = (100 - penalty).coerceIn(0, 100)
    val qualityColor = when {
        score >= 85 -> Good
        score >= 60 -> Warn
        else -> Bad
    }
    val qualityLabel = stringResource(
        when {
            score >= 85 -> R.string.hud_quality_good
            score >= 60 -> R.string.hud_quality_fair
            else -> R.string.hud_quality_poor
        },
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = PanelBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, PanelBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxHeight().padding(10.dp)) {
            PanelTitle(stringResource(R.string.hud_road_surface))
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.hud_percent, score),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = qualityColor,
            )
            Text(qualityLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = qualityColor)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF232A31)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(score / 100f)
                        .fillMaxHeight()
                        .background(qualityColor),
                )
            }

            Spacer(Modifier.height(10.dp))
            PanelTitle(stringResource(R.string.hud_defects))
            Spacer(Modifier.height(3.dp))
            if (state.breakdown.isEmpty()) {
                Text(
                    stringResource(R.string.hud_no_defects),
                    fontSize = 9.sp,
                    color = Muted,
                )
            } else {
                state.breakdown.forEach { (type, count) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(defectColor(type)),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            defectTypeName(type),
                            fontSize = 10.sp,
                            color = Color(0xFFD7DEE5),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "$count",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = defectColor(type),
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            PanelTitle(stringResource(R.string.hud_system))
            Spacer(Modifier.height(3.dp))
            StatusRow(
                stringResource(R.string.hud_battery),
                stringResource(R.string.metric_percent, state.batteryPercent),
                if (state.batteryPercent <= 20) Bad else null,
            )
            StatusRow(
                stringResource(R.string.hud_temp),
                if (state.cpuTempC.isNaN()) "—" else "%.0f°C".format(Locale.US, state.cpuTempC),
                if (state.cpuTempC >= 42f) Warn else null,
            )
            StatusRow(
                stringResource(R.string.hud_gps),
                if (state.gpsAccuracyM.isNaN()) "—"
                else stringResource(R.string.metric_gps_value, state.gpsAccuracyM),
            )
            StatusRow(
                stringResource(R.string.hud_fps),
                "${state.targetFps}",
            )
            StatusRow(
                stringResource(R.string.hud_backend),
                state.inferenceBackend,
                if (state.inferenceBackend == "NNAPI") Good else null,
            )
            StatusRow(
                stringResource(R.string.hud_latency),
                if (state.latencyMs <= 0.0) "—"
                else stringResource(R.string.hud_latency_value, state.latencyMs),
            )

            Spacer(Modifier.height(8.dp))
            PanelTitle(stringResource(R.string.hud_map))
            Spacer(Modifier.height(4.dp))
            MiniMap(
                track = state.track,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp),
            )
            if (state.track.size < 2) {
                Text(
                    stringResource(R.string.hud_map_empty),
                    fontSize = 8.sp,
                    color = Muted,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = Muted,
    )
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            fontSize = 10.sp,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: Color.White,
            maxLines = 1,
        )
    }
}

/** Мини-карта трека смены: последние ~2 км нормализуются в рамку панели. */
@Composable
private fun MiniMap(track: List<GeoPoint>, modifier: Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D1216))
            .border(1.dp, Color(0xFF1E262D), RoundedCornerShape(6.dp))
            .padding(4.dp),
    ) {
        val grid = Color(0xFF1B232A)
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (track.size < 2) return@Canvas

        val minLat = track.minOf { it.lat }
        val maxLat = track.maxOf { it.lat }
        val minLon = track.minOf { it.lon }
        val maxLon = track.maxOf { it.lon }
        val spanLat = (maxLat - minLat).coerceAtLeast(1e-5)
        val spanLon = (maxLon - minLon).coerceAtLeast(1e-5)

        val path = Path()
        var lastX = 0f
        var lastY = 0f
        track.forEachIndexed { index, point ->
            val x = ((point.lon - minLon) / spanLon * size.width).toFloat()
            val y = (size.height - (point.lat - minLat) / spanLat * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            lastX = x
            lastY = y
        }
        drawPath(
            path,
            Good,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(Accent, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
    }
}

/**
 * Рамки дефектов поверх превью. Кадр анализатора повёрнут в портрет и
 * масштабируется как PreviewView.FILL_CENTER (масштаб по большей стороне,
 * центрирование) — поэтому координаты пересчитываются той же формулой.
 */
@Composable
private fun DetectionOverlay(state: UiState) {
    val frameWidth = state.frameWidth
    val frameHeight = state.frameHeight
    if (!state.running || frameWidth <= 0 || frameHeight <= 0 || state.overlay.isEmpty()) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val scale = max(widthPx / frameWidth, heightPx / frameHeight)
        val offsetX = (widthPx - frameWidth * scale) / 2f
        val offsetY = (heightPx - frameHeight * scale) / 2f
        val labelHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }

        Canvas(Modifier.fillMaxSize()) {
            state.overlay.forEach { detection ->
                val color = defectColor(detection.type)
                val left = offsetX + detection.box.left * scale
                val top = offsetY + detection.box.top * scale
                val width = detection.box.width * scale
                val height = detection.box.height * scale
                drawRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    style = Stroke(width = 2.dp.toPx()),
                )
                // Уголки: рамка читается на любом фоне.
                val corner = 10.dp.toPx()
                val stroke = 3.dp.toPx()
                listOf(
                    Offset(left, top) to Offset(left + corner, top),
                    Offset(left, top) to Offset(left, top + corner),
                    Offset(left + width, top) to Offset(left + width - corner, top),
                    Offset(left + width, top) to Offset(left + width, top + corner),
                    Offset(left, top + height) to Offset(left + corner, top + height),
                    Offset(left, top + height) to Offset(left, top + height - corner),
                    Offset(left + width, top + height) to Offset(left + width - corner, top + height),
                    Offset(left + width, top + height) to Offset(left + width, top + height - corner),
                ).forEach { (start, end) ->
                    drawLine(color, start, end, strokeWidth = stroke)
                }
            }
        }

        state.overlay.forEach { detection ->
            val color = defectColor(detection.type)
            val left = offsetX + detection.box.left * scale
            val top = offsetY + detection.box.top * scale
            val labelY = (top - labelHeightPx).coerceAtLeast(0f)
            Text(
                "${detection.id} · ${
                    "%.0f".format(Locale.US, detection.confidence * 100)
                }%",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), labelY.roundToInt()) }
                    .background(color, RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun HudFooter(
    state: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExitBackgroundMode: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x00050709), Color(0xF5050709)),
                ),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (state.backgroundMode) {
            AssistChip(
                onClick = onExitBackgroundMode,
                label = { Text(stringResource(R.string.hud_background_mode)) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = Warn,
                    containerColor = Warn.copy(alpha = 0.12f),
                ),
            )
            Spacer(Modifier.height(6.dp))
        }
        if (state.events.isNotEmpty()) {
            Text(
                "▸ ${state.events.first()}",
                fontSize = 11.sp,
                color = Warn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
        }
        Text(
            stringResource(R.string.hud_counters, state.frames, state.confirmed, state.visualOnly),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Muted,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = if (state.running) onStop else onStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.running) Bad else Accent,
                contentColor = if (state.running) Color.White else Color(0xFF1A0C04),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                if (state.running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (state.running) R.string.action_stop else R.string.action_start),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun StateChip(state: AgentState) {
    val (labelRes, color) = when (state) {
        AgentState.IDLE -> R.string.state_idle to Muted
        AgentState.CALIBRATING -> R.string.state_calibrating to Warn
        AgentState.SCANNING -> R.string.state_scanning to Good
        AgentState.THROTTLED -> R.string.state_throttled to Color(0xFFFF9F43)
        AgentState.POST_PROCESSING -> R.string.state_post_processing to Color(0xFF6BA8FF)
        AgentState.ERROR -> R.string.state_error to Bad
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(labelRes),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun defectColor(type: DefectType): Color = when (type) {
    DefectType.POTHOLE -> Color(0xFFFF6B4A)
    DefectType.CRACK_NETWORK -> Warn
    DefectType.RUTTING -> Color(0xFF5AA9FF)
    DefectType.PATCH -> Good
    DefectType.MANHOLE_COVER -> Color(0xFFC08BFF)
    DefectType.UNKNOWN -> Color(0xFF9AA6B2)
}

@Composable
private fun defectTypeName(type: DefectType): String = stringResource(
    when (type) {
        DefectType.POTHOLE -> R.string.type_pothole
        DefectType.CRACK_NETWORK -> R.string.type_crack
        DefectType.RUTTING -> R.string.type_rutting
        DefectType.PATCH -> R.string.type_patch
        DefectType.MANHOLE_COVER -> R.string.type_manhole
        DefectType.UNKNOWN -> R.string.type_unknown
    },
)

// --- отчёт ---

@Composable
private fun ReportScreen(
    state: UiState,
    onExport: (ReportFormat) -> Unit,
    onOpenSession: (SessionEntity) -> Unit,
    padding: PaddingValues,
) {
    val report = state.report
    val currency = state.currencySymbol

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    stringResource(R.string.report_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.report_subtitle),
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
                        Text(
                            stringResource(R.string.report_empty_title),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.report_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            item {
                ReportBanner(
                    synthetic = report.sessionId.startsWith("DEMO"),
                    watermarked = report.meta.demo,
                )
            }
            item { SummaryCards(report, currency) }
            item { ExportRow(busy = state.busy, onExport = onExport) }
            item { EstimateCard(report, currency) }
            item { ClusterCard(report) }
            item { StatsCard(report) }
        }

        item {
            Text(
                stringResource(R.string.report_sessions, state.sessions.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(state.sessions, key = { it.id }) { session ->
            SessionRow(session, currency, onClick = { onOpenSession(session) })
        }
    }
}

@Composable
private fun ReportBanner(synthetic: Boolean, watermarked: Boolean) {
    if (!synthetic && !watermarked) return
    val syntheticText = stringResource(R.string.report_demo_synthetic)
    val watermarkText = stringResource(R.string.report_demo_watermark)
    val text = buildString {
        if (synthetic) append(syntheticText)
        if (watermarked) {
            if (isNotEmpty()) append(' ')
            append(watermarkText)
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x22FFC857)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = Warn,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SummaryCards(report: SessionReport, currency: String) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi(
                stringResource(R.string.report_kpi_area),
                stringResource(R.string.report_area_value, report.estimate.totalAreaM2),
                Modifier.weight(1f),
            )
            Kpi(
                stringResource(R.string.report_kpi_milling),
                stringResource(R.string.report_milling_value, report.estimate.totalMillingVolumeM3),
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi(
                stringResource(R.string.report_kpi_asphalt),
                stringResource(R.string.report_asphalt_value, report.estimate.totalAsphaltTons),
                Modifier.weight(1f),
            )
            Kpi(
                stringResource(R.string.report_kpi_estimate),
                stringResource(R.string.report_money_value, report.estimate.total, currency),
                Modifier.weight(1f),
            )
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
                Text(
                    stringResource(R.string.report_export_busy),
                    style = MaterialTheme.typography.bodySmall,
                )
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
private fun EstimateCard(report: SessionReport, currency: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.report_kpi_estimate),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            report.estimate.lines.forEach { line ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(
                                R.string.report_line_detail,
                                line.quantity,
                                line.unit,
                                line.unitPrice,
                                currency,
                            ),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(R.string.report_money_value, line.total, currency),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.report_total_with_vat, report.estimate.total, currency),
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
            Text(
                stringResource(R.string.report_clusters_title, clusters.size),
                fontWeight = FontWeight.Bold,
            )
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
                            .clip(CircleShape)
                            .background(if (cluster.confirmed) Good else Warn),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(defectTypeName(cluster.type), fontSize = 12.sp)
                        Text(
                            stringResource(
                                R.string.report_cluster_detail,
                                cluster.areaM2,
                                cluster.maxDepthMm,
                                cluster.maxSeverity,
                            ),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(
                            if (cluster.confirmed) R.string.status_confirmed else R.string.status_visual,
                        ),
                        fontSize = 10.sp,
                        color = if (cluster.confirmed) Good else Warn,
                    )
                }
            }
            if (clusters.size > 12) {
                Text(
                    stringResource(R.string.report_more, clusters.size - 12),
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
            Text(
                stringResource(R.string.report_metrics_title),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            StatRow(
                stringResource(R.string.report_metric_duration),
                stringResource(R.string.report_metric_duration_value, report.durationMs / 1000 / 60),
            )
            StatRow(
                stringResource(R.string.report_metric_frames),
                "${stats.framesAnalyzed}",
            )
            StatRow(
                stringResource(R.string.report_metric_false),
                "${stats.rejectedAsFalsePositive}",
            )
            StatRow(
                stringResource(R.string.report_metric_dupes),
                "${stats.duplicatesMerged}",
            )
            StatRow(
                stringResource(R.string.report_metric_latency),
                stringResource(R.string.report_metric_latency_value, stats.averageLatencyMs),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SessionRow(session: SessionEntity, currency: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(session.id, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                        .format(Date(session.startedAtMs)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.report_money_value, session.total, currency),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
