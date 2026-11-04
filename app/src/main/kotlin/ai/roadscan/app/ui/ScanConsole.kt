package ai.roadscan.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roadscan.core.model.DefectType
import ai.roadscan.core.model.ReviewDecision
import kotlin.math.max

/**
 * Консоль сканирования: слева статистика, в центре — кадр с находками,
 * справа — инспектор дефекта и валидация оператором.
 *
 * Раскладка повторяет привычную оператору панель разметки: цифры слева
 * всегда на виду, находки подписаны прямо на кадре, а решение по каждой
 * находке принимается в одном месте.
 */

/** Заголовок блока в панели — как в рабочей консоли разметчика. */
@Composable
private fun PanelLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Верхняя панель: кто, где, когда и с какой скоростью едем. */
@Composable
fun RsTopBar(
    time: String,
    date: String,
    speedKmh: Float,
    latitude: Double,
    longitude: Double,
    project: String,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalRsSpacing.current
    Row(
        modifier
            .fillMaxWidth()
            .background(RsTokens.panelBg)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "ROAD SCANNER AI",
                style = MaterialTheme.typography.labelMedium,
                color = RsTokens.accent,
            )
            Text(
                project.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "$date · $time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (latitude.isNaN()) "— —" else
                    "%.5f, %.5f".format(latitude, longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(spacing.md))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "%.0f".format(speedKmh),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text("км/год", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Левая панель: состояние покрытия, счётчики дефектов, качество модели. */
@Composable
fun RsStatsPanel(
    state: UiState,
    modifier: Modifier = Modifier,
    /** Дополнительный блок внизу панели (карточка готовности). */
    footer: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalRsSpacing.current
    val defects = state.defects
    val confirmed = defects.count { it.effectiveConfirmed }
    val rejected = defects.count { it.review == ReviewDecision.REJECTED }
    val pending = defects.count { it.review == ReviewDecision.UNREVIEWED && !it.confirmed }

    Column(
        modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
    ) {
        PanelLabel("ДОРОЖНЕ ПОКРИТТЯ")
        Text(
            "Асфальт 100%",
            style = MaterialTheme.typography.titleMedium,
            color = RsTokens.ok,
        )
        Spacer(Modifier.height(spacing.md))

        PanelLabel("ДЕФЕКТИ")
        Text(
            "${defects.size}",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.xs))
        CounterRow("Підтверджено", confirmed, RsTokens.ok)
        CounterRow("Очікують", pending, RsTokens.warn)
        CounterRow("Відхилено", rejected, RsTokens.bad)
        Spacer(Modifier.height(spacing.md))

        if (state.summary.isNotEmpty()) {
            PanelLabel("ЗА ТИПОМ")
            state.summary.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(defectColor(item.type)),
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Text(
                        "${item.count} ${defectTypeName(item.type)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%.1f%%".format(item.avgConfidence * 100),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(spacing.md))
        }

        PanelLabel("ПОШКОДЖЕНА ПЛОЩА")
        Text(
            "%.1f м²".format(defects.filter { it.effectiveConfirmed }.sumOf { it.estimatedAreaM2 }),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.md))

        PanelLabel("КАДРИ")
        Row(Modifier.fillMaxWidth()) {
            StatCell("оброблено", "${state.frames}", Modifier.weight(1f))
            StatCell("затримка", "%.0f мс".format(state.latencyMs), Modifier.weight(1f))
        }
        Spacer(Modifier.height(spacing.md))

        PanelLabel("ЯКІСТЬ МОДЕЛІ")
        PerformanceChart(
            values = state.performance,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            if (state.performance.isEmpty()) "очікує кадрів" else
                "сер. впевненість %.1f%%".format(
                    state.performance.average().toDouble() * 100,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.md))

        PanelLabel("СИСТЕМА")
        StatusRow("Батарея", "${state.batteryPercent}%",
            if (state.batteryPercent <= 20) RsTokens.bad else null)
        StatusRow(
            "Нагрів",
            if (state.cpuTempC.isNaN()) "—" else "%.0f °C".format(state.cpuTempC),
            if (!state.cpuTempC.isNaN() && state.cpuTempC >= 42f) RsTokens.warn else null,
        )
        StatusRow(
            "GPS",
            if (state.gpsAccuracyM.isNaN()) "—" else "±%.0f м".format(state.gpsAccuracyM),
            null,
        )
        StatusRow("FPS", "${state.targetFps}", null)
        Spacer(Modifier.height(spacing.md))

        PanelLabel("МАРШРУТ")
        MiniMap(state.track, Modifier.fillMaxWidth().height(84.dp))
        if (footer != null) {
            Spacer(Modifier.height(spacing.md))
            footer()
        }
    }
}

/**
 * Компактная строка показателей для узкого экрана.
 *
 * На телефоне три панели не помещаются, но оператору всё равно нужны цифры
 * под рукой: сколько дефектов, сколько подтверждено, какая площадь.
 */
@Composable
fun RsCompactStats(state: UiState, modifier: Modifier = Modifier) {
    val spacing = LocalRsSpacing.current
    val defects = state.defects
    val confirmed = defects.count { it.effectiveConfirmed }
    val rejected = defects.count { it.review == ReviewDecision.REJECTED }
    val area = defects.filter { it.effectiveConfirmed }.sumOf { it.estimatedAreaM2 }

    Row(
        modifier
            .fillMaxWidth()
            .background(RsTokens.panelBg)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactStat("дефектів", "${defects.size}", MaterialTheme.colorScheme.onSurface)
        CompactStat("підтв.", "$confirmed", RsTokens.ok)
        CompactStat("хибні", "$rejected", RsTokens.bad)
        CompactStat("площа", "%.1f м²".format(area), MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        CompactStat("кадрів", "${state.frames}", MaterialTheme.colorScheme.onSurface)
        CompactStat("FPS", "${state.targetFps}", MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CompactStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Правая панель: список находок и инспектор с валидацией. */
@Composable
fun RsInspectorPanel(
    state: UiState,
    onSelect: (String?) -> Unit,
    onReview: (String, ReviewDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalRsSpacing.current
    val selected = state.defects.firstOrNull { it.id == state.selectedDefectId }

    Column(
        modifier
            .fillMaxHeight()
            .padding(spacing.md),
    ) {
        PanelLabel("НАХОДКИ")
        Text(
            "${state.defects.size}",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.sm))

        if (state.defects.isEmpty()) {
            Text(
                "Дефектів ще не знайдено — вони з'являються під час зйомки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.defects, key = { it.id }) { defect ->
                    DefectRow(
                        id = defect.id,
                        type = defect.type,
                        confidence = defect.visionConfidence,
                        severity = defect.severity,
                        review = defect.review,
                        confirmed = defect.effectiveConfirmed,
                        selected = defect.id == state.selectedDefectId,
                        onClick = {
                            onSelect(if (defect.id == state.selectedDefectId) null else defect.id)
                        },
                    )
                }
            }
        }

        if (selected != null) {
            Spacer(Modifier.height(spacing.sm))
            RsDivider()
            Spacer(Modifier.height(spacing.sm))
            PanelLabel("ІНСПЕКТОР")
            InspectorRow("Тип", defectTypeName(selected.type))
            InspectorRow("ID", selected.id)
            InspectorRow("Впевненість", "%.1f%%".format(selected.visionConfidence * 100))
            InspectorRow("Критичність", "${selected.severity} / 5")
            InspectorRow("Площа", "%.2f м²".format(selected.estimatedAreaM2))
            InspectorRow("Глибина", "%.0f мм".format(selected.estimatedDepthMm))
            selected.km?.let { InspectorRow("Км", "%.3f".format(it)) }
            selected.latitude?.let { lat ->
                selected.longitude?.let { lon ->
                    InspectorRow("Координати", "%.5f, %.5f".format(lat, lon))
                }
            }
            Spacer(Modifier.height(spacing.sm))
            PanelLabel("ВАЛІДАЦІЯ")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReviewButton(
                    text = "Очікує",
                    selected = selected.review == ReviewDecision.UNREVIEWED,
                    color = RsTokens.warn,
                    modifier = Modifier.weight(1f),
                    onClick = { onReview(selected.id, ReviewDecision.UNREVIEWED) },
                )
                ReviewButton(
                    text = "Підтв.",
                    selected = selected.review == ReviewDecision.CONFIRMED,
                    color = RsTokens.ok,
                    modifier = Modifier.weight(1f),
                    onClick = { onReview(selected.id, ReviewDecision.CONFIRMED) },
                )
                ReviewButton(
                    text = "Хибне",
                    selected = selected.review == ReviewDecision.REJECTED,
                    color = RsTokens.bad,
                    modifier = Modifier.weight(1f),
                    onClick = { onReview(selected.id, ReviewDecision.REJECTED) },
                )
            }
            Spacer(Modifier.height(spacing.xs))
            Text(
                "Відхилена находка не потрапляє у відомість і кошторис.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DefectRow(
    id: String,
    type: DefectType,
    confidence: Float,
    severity: Int,
    review: ReviewDecision,
    confirmed: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val spacing = LocalRsSpacing.current
    val badge = when (review) {
        ReviewDecision.CONFIRMED -> RsTokens.ok
        ReviewDecision.REJECTED -> RsTokens.bad
        ReviewDecision.UNREVIEWED -> if (confirmed) RsTokens.ok else RsTokens.warn
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) RsTokens.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(defectColor(type)),
        )
        Spacer(Modifier.width(spacing.sm))
        Text(
            id,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$severity",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp),
        )
        Text(
            "%.0f%%".format(confidence * 100),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp),
        )
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(badge),
        )
    }
}

@Composable
private fun ReviewButton(
    text: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (selected) color else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CounterRow(label: String, value: Int, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$value",
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * График качества модели: уверенность детектора по последним кадрам.
 *
 * Помогает оператору увидеть, что модель «поплыла» (например, на мокром
 * асфальте), ещё до того, как это станет видно в ведомости.
 */
@Composable
fun PerformanceChart(values: List<Float>, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF0D1216))
            .border(1.dp, Color(0xFF1E262D), RoundedCornerShape(6.dp)),
    ) {
        if (values.size < 2) return@Canvas
        val min = 0.35f
        val max = 1f
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = size.height * (1f - ((value - min) / (max - min)).coerceIn(0f, 1f))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            RsTokens.accent,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val last = values.last()
        val lastY = size.height * (1f - ((last - min) / (max - min)).coerceIn(0f, 1f))
        drawCircle(RsTokens.ok, radius = 2.5.dp.toPx(), center = Offset(size.width, lastY))
    }
}

/**
 * Подписи находок прямо на кадре: название типа и идентификатор с
 * уверенностью — как на рабочем экране разметчика.
 */
@Composable
fun RsDetectionLabels(
    overlay: List<ai.roadscan.app.perception.OverlayDetection>,
    frameWidth: Int,
    frameHeight: Int,
    modifier: Modifier = Modifier,
) {
    if (frameWidth <= 0 || frameHeight <= 0 || overlay.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val scale = max(widthPx / frameWidth, heightPx / frameHeight)
        val offsetX = (widthPx - frameWidth * scale) / 2f
        val offsetY = (heightPx - frameHeight * scale) / 2f

        overlay.forEach { detection ->
            val color = defectColor(detection.type)
            val left = offsetX + detection.box.left * scale
            val top = offsetY + detection.box.top * scale
            Column(
                Modifier
                    .offset(x = left.dp, y = (top - 34).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.85f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    defectTypeName(detection.type).uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF10141A),
                    maxLines = 1,
                )
                Text(
                    "ID: ${detection.id}, Conf: %.1f%%".format(detection.confidence * 100),
                    fontSize = 8.sp,
                    color = Color(0xFF10141A),
                    maxLines = 1,
                )
            }
        }
    }
}
