package ai.roadscan.app.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ai.roadscan.app.R
import ai.roadscan.app.video.VideoInfo
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.video.MontageMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Видеостудия: выбрать сырое видео, получить готовый ролик с найденными
 * дефектами. Экран показывает прогресс, предпросмотр и отправку результата.
 */
@Composable
fun VideoScreen(
    state: VideoUiState,
    onPick: (Uri) -> Unit,
    onMode: (MontageMode) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    onClearMessage: () -> Unit,
    onPurpose: (VideoMode) -> Unit,
    onAddSurvey: (List<Uri>) -> Unit,
    onRemoveSurvey: (String) -> Unit,
    onClearSurvey: () -> Unit,
    onSurvey: () -> Unit,
    padding: PaddingValues,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onPick) }
    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(6),
    ) { uris -> if (uris.isNotEmpty()) onAddSurvey(uris) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onClearMessage()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(
                        stringResource(R.string.video_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.video_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                PurposeCard(
                    purpose = state.purpose,
                    enabled = !state.busy,
                    onPurpose = onPurpose,
                )
            }

            if (state.purpose == VideoMode.SURVEY) {
                item {
                    SurveyCard(
                        state = state,
                        onAdd = {
                            multiLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                            )
                        },
                        onRemove = onRemoveSurvey,
                        onClear = onClearSurvey,
                        onSurvey = onSurvey,
                    )
                }
            } else {
                item {
                    VideoSourceCard(
                        state = state,
                        onPick = {
                            launcher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                            )
                        },
                    )
                }
            }

            if (state.purpose == VideoMode.MONTAGE && state.info != null) {
                item {
                    ModeCard(
                        mode = state.mode,
                        enabled = !state.busy,
                        onMode = onMode,
                    )
                }
            }

            if (state.busy) {
                item { ProgressCard(state, onCancel) }
            } else if (state.purpose == VideoMode.MONTAGE && state.result != null && state.summary != null) {
                item { ResultCard(state, onShare, onReset) }
            } else if (state.purpose == VideoMode.MONTAGE && state.info != null) {
                item {
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.Movie, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.video_start),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.video_offline_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Переключатель режима видеоконтура: ролик-обзор или обследование участка. */
@Composable
private fun PurposeCard(
    purpose: VideoMode,
    enabled: Boolean,
    onPurpose: (VideoMode) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    VideoMode.MONTAGE to R.string.video_purpose_montage,
                    VideoMode.SURVEY to R.string.video_purpose_survey,
                ).forEach { (mode, label) ->
                    if (purpose == mode) {
                        Button(onClick = { onPurpose(mode) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(label), fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onPurpose(mode) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(label), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Очередь обследования: готовые ролики из галереи -> дефекты -> ведомость.
 */
@Composable
private fun SurveyCard(
    state: VideoUiState,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onSurvey: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.video_survey_queue),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.video_survey_hint),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.video_survey_section_hint),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (state.surveyQueue.isEmpty()) {
                Text(
                    stringResource(R.string.video_survey_empty),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.surveyQueue.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when (item.state) {
                                    SurveyItemState.WAITING ->
                                        item.info?.let { "${it.durationLabel} · ${it.resolutionLabel}" } ?: "…"

                                    SurveyItemState.ANALYZING -> stringResource(R.string.video_survey_running)
                                    SurveyItemState.DONE ->
                                        stringResource(R.string.video_survey_done_item, item.defects, item.confirmed)

                                    SurveyItemState.FAILED ->
                                        item.error ?: stringResource(R.string.video_survey_failed)
                                },
                                fontSize = 10.sp,
                                color = if (item.state == SurveyItemState.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (!state.busy) {
                            TextButton(onClick = { onRemove(item.id) }) {
                                Text("✕", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAdd, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.video_survey_add), fontSize = 12.sp)
                }
                if (state.surveyQueue.isNotEmpty()) {
                    OutlinedButton(onClick = onClear, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.video_survey_clear), fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSurvey,
                enabled = !state.busy && state.surveyQueue.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Movie, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.video_survey_run), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun VideoSourceCard(state: VideoUiState, onPick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (state.info == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.video_no_video),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.video_no_video_hint),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.video_pick))
                }
                return@Column
            }

            val info = state.info
            Text(
                info.displayName,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            InfoRow(
                stringResource(R.string.video_duration_row),
                info.durationLabel,
            )
            InfoRow(
                stringResource(R.string.video_resolution_row),
                info.resolutionLabel,
            )
            if (state.demoWatermark) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.video_watermark_demo),
                    fontSize = 11.sp,
                    color = Color(0xFFFFC857),
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.video_change))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
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
private fun ModeCard(
    mode: MontageMode,
    enabled: Boolean,
    onMode: (MontageMode) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.video_mode),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == MontageMode.HIGHLIGHTS,
                    onClick = { onMode(MontageMode.HIGHLIGHTS) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.video_mode_highlights), fontSize = 12.sp) },
                )
                FilterChip(
                    selected = mode == MontageMode.FULL,
                    onClick = { onMode(MontageMode.FULL) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.video_mode_full), fontSize = 12.sp) },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    if (mode == MontageMode.HIGHLIGHTS) {
                        R.string.video_mode_highlights_hint
                    } else {
                        R.string.video_mode_full_hint
                    },
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressCard(state: VideoUiState, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    state.stageLabel.ifBlank { stringResource(R.string.video_stage_prepare) },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.video_keep_open),
                fontSize = 11.sp,
                color = Color(0xFFFFC857),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.video_cancel))
            }
        }
    }
}

@Composable
private fun ResultCard(
    state: VideoUiState,
    onShare: () -> Unit,
    onReset: () -> Unit,
) {
    val summary = state.summary ?: return
    val file = state.result ?: return
    val duration = VideoInfo.formatDuration(summary.durationMs)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.video_result_title),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(
                        pluralStringResource(
                            R.plurals.video_result_defects,
                            summary.defects,
                            summary.defects,
                        ),
                    )
                    append(" · ")
                    append(
                        pluralStringResource(
                            R.plurals.video_result_segments,
                            summary.segments,
                            summary.segments,
                        ),
                    )
                    append(" · ")
                    append(duration)
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.byType.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                summary.byType.forEach { (type, count) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(videoDefectColor(type)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            videoTypeName(type),
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "× $count",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = videoDefectColor(type),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.video_preview),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            VideoPreview(file)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.video_share))
                }
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.video_again), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun VideoPreview(file: File) {
    // Пока зритель не нажал «играть», показываем реальный кадр из ролика:
    // VideoView в паузе на части устройств остаётся чёрным квадратом.
    var poster by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    var playing by remember(file.path) { mutableStateOf(false) }

    LaunchedEffect(file.path) {
        // Кадр берём не из титула (он тёмный и выглядит как «чёрное видео»),
        // а из живой части — на 15% длительности: там уже дорога с разметкой.
        poster = withContext(Dispatchers.IO) { posterFrame(file) }?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoPath(file.absolutePath)
                        setOnPreparedListener { it.start() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.stopPlayback() },
            )
        } else {
            poster?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC000000))
                    .clickable { playing = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/**
 * Кадр-заставка из готового ролика, уменьшенный до 720 px.
 *
 * Ролик начинается с тёмного титула (2,6 с) — кадр из него выглядит
 * как «чёрное видео». Поэтому берём кадр на 15% длительности, но не
 * раньше 4 с: там уже видна дорога с рамками дефектов.
 */
private fun posterFrame(file: File): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val atMs = (durationMs * 15 / 100).coerceIn(4_000L, durationMs.coerceAtLeast(4_000L))
        val frame = retriever.getFrameAtTime(
            atMs * 1_000L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: retriever.getFrameAtTime(
            800_000L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: return null
        val width = frame.width.coerceAtLeast(1)
        if (width <= 720) {
            frame
        } else {
            val scaled = Bitmap.createScaledBitmap(
                frame,
                720,
                (frame.height * 720f / width).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== frame) frame.recycle()
            scaled
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

@Composable
private fun videoTypeName(type: DefectType): String = stringResource(
    when (type) {
        DefectType.POTHOLE -> R.string.type_pothole
        DefectType.CRACK_NETWORK -> R.string.type_crack
        DefectType.RUTTING -> R.string.type_rutting
        DefectType.PATCH -> R.string.type_patch
        DefectType.MANHOLE_COVER -> R.string.type_manhole
        DefectType.UNKNOWN -> R.string.type_unknown
    },
)

private fun videoDefectColor(type: DefectType): Color = when (type) {
    DefectType.POTHOLE -> Color(0xFFFF6B4A)
    DefectType.CRACK_NETWORK -> Color(0xFFFFC857)
    DefectType.RUTTING -> Color(0xFF5AA9FF)
    DefectType.PATCH -> Color(0xFF7CD0B0)
    DefectType.MANHOLE_COVER -> Color(0xFFC08BFF)
    DefectType.UNKNOWN -> Color(0xFF9AA6B2)
}
