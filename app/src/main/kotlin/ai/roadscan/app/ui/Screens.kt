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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableStateOf
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

// Палитра HUD вынесена в дизайн-токены (Theme.kt), чтобы экраны не расходились.
private val PanelBg = RsTokens.panelBg
private val PanelBorder = RsTokens.panelBorder
private val Accent = RsTokens.accent
private val Good = RsTokens.ok
private val Warn = RsTokens.warn
private val Bad = RsTokens.bad
private val Muted = RsTokens.muted

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
    var showDefectSheet by rememberSaveable { mutableStateOf(false) }
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

    if (showDefectSheet) {
        DefectSheet(
            state = state,
            onSelect = viewModel::selectDefect,
            onReview = viewModel::setDefectReview,
            onDismiss = { showDefectSheet = false },
        )
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
                onOpenSettings = { tab = 3 },
                onSelectDefect = viewModel::selectDefect,
                onReviewDefect = viewModel::setDefectReview,
                onShowDefects = { showDefectSheet = true },
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
                onPurpose = videoViewModel::setPurpose,
                onAddSurvey = videoViewModel::addToSurvey,
                onRemoveSurvey = videoViewModel::removeFromSurvey,
                onClearSurvey = videoViewModel::clearSurvey,
                onSurvey = videoViewModel::startSurvey,
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
                onRunDiagnostics = viewModel::runDiagnostics,
                onAutoSetup = viewModel::runAutoSetup,
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
    onOpenSettings: () -> Unit,
    onSelectDefect: (String?) -> Unit,
    onReviewDefect: (String, ai.roadscan.core.model.ReviewDecision) -> Unit,
    onShowDefects: () -> Unit,
    padding: PaddingValues,
) {
    val nowMs = rememberClockMs()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(Color.Black),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // На планшете и в альбомной ориентации — полная консоль из трёх
            // панелей, как на рабочем экране разметчика. На телефоне места
            // для трёх панелей нет: камера в центре, показатели сверху, а
            // находки и валидация — в выдвижной шторке.
            val wide = maxWidth >= 600.dp

            Column(Modifier.fillMaxSize()) {
                RsTopBar(
                    time = rememberClockText(nowMs, "HH:mm:ss"),
                    date = rememberClockText(nowMs, "dd.MM.yyyy"),
                    speedKmh = state.speedKmh,
                    latitude = state.latitude,
                    longitude = state.longitude,
                    project = state.meta.headline(),
                )
                if (!wide) {
                    RsCompactStats(state)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (wide) {
                        // Слева — состояние: покрытие, счётчики, качество модели.
                        RsStatsPanel(
                            state = state,
                            modifier = Modifier
                                .width(232.dp)
                                .fillMaxHeight()
                                .background(RsTokens.panelBg),
                            footer = if (!state.running) {
                                { RsReadinessCard(readiness = state.readiness, titles = readinessTitles(), hints = readinessHints(), onStepClick = { onOpenSettings() }) }
                            } else {
                                null
                            },
                        )
                    }
                    // В центре — кадр с подписанными находками.
                    Box(Modifier.weight(1f)) {
                        if (state.running) {
                            CameraPreview(analyzer = analyzer, modifier = Modifier.fillMaxSize())
                        } else {
                            IdleBackdrop(showHint = false)
                        }
                        DetectionOverlay(state)
                        RsDetectionLabels(
                            overlay = state.overlay,
                            frameWidth = state.frameWidth,
                            frameHeight = state.frameHeight,
                        )
                        if (!state.running && !wide) {
                            RsReadinessCard(
                                readiness = state.readiness,
                                titles = readinessTitles(),
                                hints = readinessHints(),
                                onStepClick = { onOpenSettings() },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(12.dp),
                            )
                        }
                    }
                    if (wide) {
                        // Справа — находки и решение оператора по каждой.
                        RsInspectorPanel(
                            state = state,
                            onSelect = onSelectDefect,
                            onReview = onReviewDefect,
                            modifier = Modifier
                                .width(268.dp)
                                .fillMaxHeight()
                                .background(RsTokens.panelBg),
                        )
                    }
                }
                HudFooter(
                    state = state,
                    onStart = onStart,
                    onStop = onStop,
                    onExitBackgroundMode = onExitBackgroundMode,
                    onShowDefects = if (wide) null else onShowDefects,
                )
            }
        }

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
 * Шторка с находками для узкого экрана: список и валидация в одном месте.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefectSheet(
    state: UiState,
    onSelect: (String?) -> Unit,
    onReview: (String, ai.roadscan.core.model.ReviewDecision) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            RsInspectorPanel(
                state = state,
                onSelect = onSelect,
                onReview = onReview,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }
}

/** Подписи пунктов готовности к смене. */
@Composable
private fun readinessTitles() = mapOf(
    ai.roadscan.core.readiness.ReadinessStepId.PERMISSIONS to
        stringResource(R.string.readiness_step_permissions),
    ai.roadscan.core.readiness.ReadinessStepId.LICENSE to
        stringResource(R.string.readiness_step_license),
    ai.roadscan.core.readiness.ReadinessStepId.CALIBRATION to
        stringResource(R.string.readiness_step_calibration),
    ai.roadscan.core.readiness.ReadinessStepId.DOCUMENTS to
        stringResource(R.string.readiness_step_documents),
    ai.roadscan.core.readiness.ReadinessStepId.EQUIPMENT to
        stringResource(R.string.readiness_step_equipment),
)

/** Подсказки по незакрытым пунктам готовности. */
@Composable
private fun readinessHints() = mapOf(
    ai.roadscan.core.readiness.ReadinessStepId.PERMISSIONS to
        stringResource(R.string.readiness_hint_permissions),
    ai.roadscan.core.readiness.ReadinessStepId.LICENSE to
        stringResource(R.string.readiness_hint_license),
    ai.roadscan.core.readiness.ReadinessStepId.CALIBRATION to
        stringResource(R.string.readiness_hint_calibration),
    ai.roadscan.core.readiness.ReadinessStepId.DOCUMENTS to
        stringResource(R.string.readiness_hint_documents),
    ai.roadscan.core.readiness.ReadinessStepId.EQUIPMENT to
        stringResource(R.string.readiness_hint_equipment),
)

/** Форматированные часы/дата в шапке консоли. */
@Composable
private fun rememberClockText(nowMs: Long, pattern: String): String =
    remember(nowMs, pattern) {
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(nowMs))
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
                colors = ButtonDefaults.buttonColors(containerColor = RsTokens.accent),
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
                        color = RsTokens.bad,
                    )
                }
            }
        },
    )
}

/**
 * Фон экрана до старта смены.
 *
 * @param showHint показывать ли подсказку «Камеру вимкнено»: перед сменой
 *   место занимает карточка готовности, и подсказка с ней конфликтует.
 */
@Composable
private fun IdleBackdrop(showHint: Boolean = true) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF141A20), Color(0xFF070A0D))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!showHint) return@Box
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
internal fun StatusRow(label: String, value: String, valueColor: Color? = null) {
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
internal fun MiniMap(track: List<GeoPoint>, modifier: Modifier) {
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
    onShowDefects: (() -> Unit)? = null,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.hud_counters, state.frames, state.confirmed, state.visualOnly),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            if (onShowDefects != null) {
                OutlinedButton(
                    onClick = onShowDefects,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    Text(
                        stringResource(R.string.hud_defects_button),
                        fontSize = 11.sp,
                    )
                }
            }
        }
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

internal fun defectColor(type: DefectType): Color = when (type) {
    DefectType.POTHOLE -> Color(0xFFFF6B4A)
    DefectType.CRACK_NETWORK -> Warn
    DefectType.RUTTING -> Color(0xFF5AA9FF)
    DefectType.PATCH -> Good
    DefectType.MANHOLE_COVER -> Color(0xFFC08BFF)
    DefectType.UNKNOWN -> Color(0xFF9AA6B2)
}

@Composable
internal fun defectTypeName(type: DefectType): String = stringResource(
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
        Spacer(Modifier.height(8.dp))
        // Комплект для передачи заказчику: всё одним архивом + манифест.
        OutlinedButton(
            onClick = { onExport(ReportFormat.PACKAGE) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.report_package), fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.report_package_hint),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            // Валидация оператора — прямое измерение точности модели в поле.
            val operatorConfirmed = report.defects.count {
                it.review == ai.roadscan.core.model.ReviewDecision.CONFIRMED
            }
            val operatorRejected = report.defects.count {
                it.review == ai.roadscan.core.model.ReviewDecision.REJECTED
            }
            StatRow(
                stringResource(R.string.report_metric_operator_confirmed),
                "$operatorConfirmed",
            )
            StatRow(
                stringResource(R.string.report_metric_operator_rejected),
                "$operatorRejected",
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
                    if (session.endedAtMs > 0L) {
                        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                            .format(Date(session.startedAtMs))
                    } else {
                        stringResource(R.string.report_session_in_progress)
                    },
                    fontSize = 10.sp,
                    color = if (session.endedAtMs > 0L) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Warn
                    },
                )
            }
            Text(
                if (session.endedAtMs > 0L) {
                    stringResource(R.string.report_money_value, session.total, currency)
                } else {
                    stringResource(R.string.report_session_in_progress_short)
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
