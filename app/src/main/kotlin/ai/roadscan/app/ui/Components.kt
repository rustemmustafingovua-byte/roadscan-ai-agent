package ai.roadscan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import ai.roadscan.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Общие элементы интерфейса.
 *
 * Раньше каждый экран собирал карточки и кнопки вручную, из-за чего размеры
 * и цвета разъезжались. Здесь — единые карточка, плитка показателя, строка
 * проверки и пустое состояние.
 */

/** Карточка в стиле приложения. */
@Composable
fun RsCard(
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val spacing = LocalRsSpacing.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(spacing.cardRadius),
        modifier = modifier,
    ) {
        Column(Modifier.padding(spacing.lg), content = content)
    }
}

/** Заголовок раздела с подписью. */
@Composable
fun RsSectionTitle(title: String, subtitle: String? = null) {
    val spacing = LocalRsSpacing.current
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (subtitle != null) {
        Spacer(Modifier.height(spacing.xs))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Плитка показателя (число + подпись). */
@Composable
fun RsStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val spacing = LocalRsSpacing.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(spacing.cardRadius),
        modifier = modifier,
    ) {
        Column(Modifier.padding(spacing.md)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = accent ?: MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Строка проверки готовности: точка состояния, название и подсказка.
 *
 * @param done выполнено
 * @param blocking без этого смена невозможна (красная точка)
 */
@Composable
fun RsCheckRow(
    title: String,
    hint: String,
    done: Boolean,
    blocking: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val spacing = LocalRsSpacing.current
    val color = when {
        done -> RsTokens.ok
        blocking -> RsTokens.bad
        else -> RsTokens.warn
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Пустое состояние: иконка, заголовок, пояснение и действие. */
@Composable
fun RsEmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalRsSpacing.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(spacing.md))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(spacing.xs))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(spacing.md))
            action()
        }
    }
}

/** Основная кнопка. */
@Composable
fun RsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(LocalRsSpacing.current.chipRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier.height(52.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(LocalRsSpacing.current.sm))
        }
        Text(text, fontWeight = FontWeight.Bold)
    }
}

/** Вторичная кнопка. */
@Composable
fun RsSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(LocalRsSpacing.current.chipRadius),
        modifier = modifier.height(52.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Разделительная линия в стиле приложения. */
@Composable
fun RsDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * Карточка готовности к смене: что уже сделано и что ещё нужно оператору.
 *
 * Показывается на главном экране до старта смены — новичок не должен
 * узнавать о пропущенной калибровке уже в поле.
 */
@Composable
fun RsReadinessCard(
    readiness: ai.roadscan.core.readiness.Readiness,
    titles: Map<ai.roadscan.core.readiness.ReadinessStepId, String>,
    hints: Map<ai.roadscan.core.readiness.ReadinessStepId, String>,
    modifier: Modifier = Modifier,
    onStepClick: ((ai.roadscan.core.readiness.ReadinessStepId) -> Unit)? = null,
) {
    val spacing = LocalRsSpacing.current
    RsCard(modifier.heightIn(max = 320.dp)) {
        Column(
            Modifier
                .heightIn(max = 288.dp)
                .verticalScroll(rememberScrollState()),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.readiness_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (readiness.ready) {
                        stringResource(R.string.readiness_ready)
                    } else {
                        stringResource(
                            R.string.readiness_progress,
                            readiness.steps.count { it.done },
                            readiness.steps.size,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (readiness.ready) {
                        RsTokens.ok
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (readiness.ready) {
                            RsTokens.ok.copy(alpha = 0.16f)
                        } else {
                            RsTokens.warn.copy(alpha = 0.16f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${readiness.progress}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (readiness.ready) RsTokens.ok else RsTokens.warn,
                )
            }
        }
        Spacer(Modifier.height(spacing.md))
        readiness.steps.forEach { step ->
            RsCheckRow(
                title = titles[step.id].orEmpty(),
                hint = if (step.done) "" else hints[step.id].orEmpty(),
                done = step.done,
                blocking = step.blocking,
                onClick = onStepClick?.let { click -> { click(step.id) } },
            )
        }
        }
    }
}

/** Обрезка длинного текста в одну строку. */
@Composable
fun RsOneLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Отступ для содержимого списка. */
@Composable
fun rsSpacing(): RsSpacing = LocalRsSpacing.current

/** Рамка вокруг выделенного элемента. */
@Composable
fun Modifier.rsOutline(color: Color = MaterialTheme.colorScheme.outline): Modifier =
    this.border(1.dp, color, RoundedCornerShape(12.dp))

/** Минимальная высота блока (для пустых состояний). */
@Composable
fun Modifier.rsMinHeight(dp: androidx.compose.ui.unit.Dp): Modifier = this.heightIn(min = dp)

/** Ровные интервалы между элементами списка. */
val rsListSpacing: Arrangement.Horizontal = Arrangement.spacedBy(8.dp)
