package ai.roadscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roadscan.app.R
import ai.roadscan.core.calibration.CalibrationProfile
import ai.roadscan.core.license.Edition
import ai.roadscan.core.report.ReportMeta
import java.util.Locale

/**
 * Настройки комплекса: лицензия, калибровка под автомобиль и реквизиты
 * документов. Всё, что нужно заполнить один раз перед коммерческой эксплуатацией.
 */
@Composable
fun SettingsScreen(
    state: UiState,
    onSaveCalibration: (CalibrationProfile) -> Unit,
    onSaveMeta: (ReportMeta) -> Unit,
    onActivateLicense: (String) -> Unit,
    onDeactivateLicense: () -> Unit,
    onLoadDemo: () -> Unit,
    padding: PaddingValues,
) {
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
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { LicenseSection(state, onActivateLicense, onDeactivateLicense) }
        item { CalibrationSection(state.calibration, onSaveCalibration) }
        item { DocumentsSection(state.meta, onSaveMeta) }
        item { DemoSection(onLoadDemo) }
        item {
            Text(
                stringResource(R.string.settings_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun editionName(edition: Edition): String = stringResource(
    when (edition) {
        Edition.TRIAL -> R.string.edition_trial
        Edition.PILOT -> R.string.edition_pilot
        Edition.PRO -> R.string.edition_pro
        Edition.ENTERPRISE -> R.string.edition_enterprise
    },
)

@Composable
private fun LicenseSection(
    state: UiState,
    onActivate: (String) -> Unit,
    onDeactivate: () -> Unit,
) {
    val license = state.license
    var key by remember { mutableStateOf("") }
    val active = license.isLicensed(System.currentTimeMillis())
    SectionCard(stringResource(R.string.settings_license)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        editionName(license.edition),
                        fontSize = 11.sp,
                        color = if (active) Color(0xFF7CD0B0) else Color(0xFFFFC857),
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (active) R.string.settings_license_active else R.string.settings_license_trial,
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(state.licenseSummary, fontSize = 12.sp)
        if (license.demoWatermark()) {
            Text(
                stringResource(R.string.settings_license_demo_hint),
                fontSize = 11.sp,
                color = Color(0xFFFFC857),
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.settings_license_key_label)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onActivate(key) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_license_activate))
            }
            if (active) {
                OutlinedButton(onClick = onDeactivate, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_license_remove))
                }
            }
        }
    }
}

@Composable
private fun CalibrationSection(
    profile: CalibrationProfile,
    onSave: (CalibrationProfile) -> Unit,
) {
    var height by remember(profile) { mutableStateOf("%.2f".format(Locale.US, profile.cameraHeightM)) }
    var pitch by remember(profile) { mutableStateOf("%.1f".format(Locale.US, profile.cameraPitchDeg)) }
    var focal by remember(profile) { mutableStateOf(profile.focalLengthPx?.let { "%.0f".format(Locale.US, it) } ?: "") }
    var name by remember(profile) { mutableStateOf(profile.name) }
    var vehicle by remember(profile) { mutableStateOf(profile.vehicle) }
    var depthBase by remember(profile) { mutableStateOf("%.1f".format(Locale.US, profile.depthBaseMm)) }
    var depthPerG by remember(profile) { mutableStateOf("%.1f".format(Locale.US, profile.depthMmPerG)) }

    var refWidth by remember { mutableStateOf("1.57") }
    var refDistance by remember { mutableStateOf("10.0") }
    var refPx by remember { mutableStateOf("480") }

    SectionCard(stringResource(R.string.settings_calibration)) {
        Text(
            stringResource(R.string.settings_calibration_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Field(stringResource(R.string.settings_calibration_profile), name) { name = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.settings_calibration_height), height, Modifier.weight(1f)) { height = it }
            Field(stringResource(R.string.settings_calibration_pitch), pitch, Modifier.weight(1f)) { pitch = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.settings_calibration_focal), focal, Modifier.weight(1f)) { focal = it }
            Field(stringResource(R.string.settings_calibration_vehicle), vehicle, Modifier.weight(1f)) { vehicle = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(
                stringResource(R.string.settings_calibration_depth_base),
                depthBase,
                Modifier.weight(1f),
            ) { depthBase = it }
            Field(
                stringResource(R.string.settings_calibration_depth_per_g),
                depthPerG,
                Modifier.weight(1f),
            ) { depthPerG = it }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.settings_calibration_focal_title),
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        Text(
            stringResource(R.string.settings_calibration_focal_hint),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.settings_calibration_width), refWidth, Modifier.weight(1f)) { refWidth = it }
            Field(
                stringResource(R.string.settings_calibration_distance),
                refDistance,
                Modifier.weight(1f),
            ) { refDistance = it }
            Field(stringResource(R.string.settings_calibration_pixels), refPx, Modifier.weight(1f)) { refPx = it }
        }
        val computed = CalibrationProfile.estimateFocalLengthPx(
            refWidth.toFloatOrNull() ?: 0f,
            refDistance.toFloatOrNull() ?: 0f,
            refPx.toFloatOrNull() ?: 0f,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                computed?.let { stringResource(R.string.settings_calibration_focal_value, it) }
                    ?: stringResource(R.string.settings_calibration_focal_invalid),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { computed?.let { focal = "%.0f".format(Locale.US, it) } },
                enabled = computed != null,
            ) { Text(stringResource(R.string.settings_calibration_apply)) }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                onSave(
                    profile.copy(
                        name = name,
                        cameraHeightM = height.toFloatOrNull() ?: profile.cameraHeightM,
                        cameraPitchDeg = pitch.toFloatOrNull() ?: profile.cameraPitchDeg,
                        focalLengthPx = focal.toFloatOrNull(),
                        vehicle = vehicle,
                        depthBaseMm = depthBase.toDoubleOrNull() ?: profile.depthBaseMm,
                        depthMmPerG = depthPerG.toDoubleOrNull() ?: profile.depthMmPerG,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_calibration_save)) }
    }
}

@Composable
private fun DocumentsSection(meta: ReportMeta, onSave: (ReportMeta) -> Unit) {
    var objectName by remember(meta) { mutableStateOf(meta.objectName) }
    var road by remember(meta) { mutableStateOf(meta.roadName) }
    var customer by remember(meta) { mutableStateOf(meta.customer) }
    var contractor by remember(meta) { mutableStateOf(meta.contractor) }
    var contract by remember(meta) { mutableStateOf(meta.contractNumber) }
    var operator by remember(meta) { mutableStateOf(meta.operatorName) }
    var vehicle by remember(meta) { mutableStateOf(meta.vehicle) }
    var weather by remember(meta) { mutableStateOf(meta.weather) }

    SectionCard(stringResource(R.string.settings_meta)) {
        Text(
            stringResource(R.string.settings_meta_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Field(stringResource(R.string.settings_meta_object), objectName) { objectName = it }
        Field(stringResource(R.string.settings_meta_road), road) { road = it }
        Field(stringResource(R.string.settings_meta_customer), customer) { customer = it }
        Field(stringResource(R.string.settings_meta_contractor), contractor) { contractor = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.settings_meta_contract), contract, Modifier.weight(1f)) { contract = it }
            Field(stringResource(R.string.settings_meta_operator), operator, Modifier.weight(1f)) { operator = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.settings_meta_vehicle), vehicle, Modifier.weight(1f)) { vehicle = it }
            Field(stringResource(R.string.settings_meta_weather), weather, Modifier.weight(1f)) { weather = it }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                onSave(
                    meta.copy(
                        objectName = objectName,
                        roadName = road,
                        customer = customer,
                        contractor = contractor,
                        contractNumber = contract,
                        operatorName = operator,
                        vehicle = vehicle,
                        weather = weather,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_meta_save)) }
    }
}

/**
 * Демонстрационный отчёт: полный пакет документов без выезда.
 * Нужен тендерному отделу, обучению операторов и проверке экспорта после
 * обновления — данные детерминированные и всегда помечены как демо.
 */
@Composable
private fun DemoSection(onLoadDemo: () -> Unit) {
    SectionCard(stringResource(R.string.settings_demo)) {
        Text(
            stringResource(R.string.settings_demo_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onLoadDemo, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_demo_action))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
    )
}
