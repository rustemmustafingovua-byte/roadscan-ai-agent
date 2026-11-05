package ai.roadscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.roadscan.app.R
import ai.roadscan.app.diagnostics.DiagnosticCheck
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
    onRunDiagnostics: () -> Unit,
    onAutoSetup: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: (android.net.Uri) -> Unit = {},
    padding: PaddingValues,
) {
    var legalDoc by remember { mutableStateOf<LegalDoc?>(null) }
    Box(Modifier.fillMaxSize()) {
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
            item { DiagnosticsSection(state.diagnostics, onRunDiagnostics) }
            item { LicenseSection(state, onActivateLicense, onDeactivateLicense) }
            item {
                CalibrationSection(
                    state.calibration,
                    onSaveCalibration,
                    onAutoSetup = onAutoSetup,
                )
            }
            item { DocumentsSection(state.meta, onSaveMeta) }
            item { DataSection(onBackup, onRestore) }
            item { DemoSection(onLoadDemo) }
            item { LegalSection(onOpen = { legalDoc = it }) }
            item {
                Text(
                    stringResource(R.string.settings_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        legalDoc?.let { doc -> LegalDialog(doc) { legalDoc = null } }
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

/**
 * Данные комплекса: резервная копия базы и настроек одним файлом
 * (переезд на новый телефон, переустановка) и восстановление из файла.
 */
@Composable
private fun DataSection(
    onBackup: () -> Unit,
    onRestore: (android.net.Uri) -> Unit,
) {
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onRestore) }

    SectionCard(stringResource(R.string.settings_data)) {
        Text(
            stringResource(R.string.settings_data_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBackup, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_backup), fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.settings_restore), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CalibrationSection(
    profile: CalibrationProfile,
    onSave: (CalibrationProfile) -> Unit,
    onAutoSetup: () -> Unit = {},
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
        // Автоподготовка: оптика из характеристик камеры, наклон по датчику,
        // профиль устройства по замеру инференса. Оператору ничего не измерять.
        Button(
            onClick = onAutoSetup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_autosetup))
        }
        Text(
            stringResource(R.string.settings_autosetup_hint),
            fontSize = 10.sp,
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
    var kmFrom by remember(meta) { mutableStateOf(if (meta.sectionKmFrom > 0) meta.sectionKmFrom.toString() else "") }
    var kmTo by remember(meta) { mutableStateOf(if (meta.sectionKmTo > 0) meta.sectionKmTo.toString() else "") }
    var sectionLat by remember(meta) { mutableStateOf(meta.sectionStartLat?.toString() ?: "") }
    var sectionLon by remember(meta) { mutableStateOf(meta.sectionStartLon?.toString() ?: "") }
    var bearing by remember(meta) { mutableStateOf(if (meta.sectionBearingDeg != 0.0) meta.sectionBearingDeg.toString() else "") }

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
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_meta_section_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(
                stringResource(R.string.settings_meta_section_km_from),
                kmFrom,
                Modifier.weight(1f),
                keyboard = KeyboardType.Decimal,
            ) { kmFrom = it }
            Field(
                stringResource(R.string.settings_meta_section_km_to),
                kmTo,
                Modifier.weight(1f),
                keyboard = KeyboardType.Decimal,
            ) { kmTo = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(
                stringResource(R.string.settings_meta_section_lat),
                sectionLat,
                Modifier.weight(1f),
                keyboard = KeyboardType.Decimal,
            ) { sectionLat = it }
            Field(
                stringResource(R.string.settings_meta_section_lon),
                sectionLon,
                Modifier.weight(1f),
                keyboard = KeyboardType.Decimal,
            ) { sectionLon = it }
        }
        Field(
            stringResource(R.string.settings_meta_section_bearing),
            bearing,
            keyboard = KeyboardType.Decimal,
        ) { bearing = it }
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
                        sectionKmFrom = kmFrom.toDoubleOrNull() ?: 0.0,
                        sectionKmTo = kmTo.toDoubleOrNull() ?: 0.0,
                        sectionStartLat = sectionLat.toDoubleOrNull(),
                        sectionStartLon = sectionLon.toDoubleOrNull(),
                        sectionBearingDeg = bearing.toDoubleOrNull() ?: 0.0,
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

/**
 * Проверка готовности комплекта: модель, камера, датчик, GNSS, место, заряд,
 * лицензия. Оператор запускает её перед выездом — в поле уже не разберёшься.
 */
@Composable
private fun DiagnosticsSection(
    checks: List<DiagnosticCheck>,
    onRun: () -> Unit,
) {
    SectionCard(stringResource(R.string.settings_diagnostics)) {
        Text(
            stringResource(R.string.settings_diagnostics_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_diagnostics_run))
        }
        if (checks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val blocking = checks.count { !it.ok && it.blocking }
            Text(
                if (blocking == 0) {
                    stringResource(R.string.settings_diagnostics_ok)
                } else {
                    stringResource(R.string.settings_diagnostics_fail, blocking)
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (blocking == 0) RsTokens.ok else RsTokens.bad,
            )
            Spacer(Modifier.height(6.dp))
            checks.forEach { check ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (check.ok) "✓" else if (check.blocking) "✕" else "!",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (check.ok) RsTokens.ok else if (check.blocking) RsTokens.bad else RsTokens.warn,
                        modifier = Modifier.width(18.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(check.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            check.detail,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Правовые документы поставляются в APK (assets/legal) и открываются офлайн:
 * у дорожной службы нет гарантированного интернета в поле.
 */
@Composable
private fun LegalSection(onOpen: (LegalDoc) -> Unit) {
    SectionCard(stringResource(R.string.settings_legal)) {
        Text(
            stringResource(R.string.settings_legal_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { onOpen(LegalDoc.Eula) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_legal_eula))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onOpen(LegalDoc.Privacy) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_legal_privacy))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onOpen(LegalDoc.ThirdParty) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_legal_thirdparty))
        }
    }
}

private enum class LegalDoc(val titleRes: Int, val assetBase: String) {
    Eula(R.string.settings_legal_eula, "eula"),
    Privacy(R.string.settings_legal_privacy, "privacy"),
    ThirdParty(R.string.settings_legal_thirdparty, "thirdparty"),
}

@Composable
private fun LegalDialog(doc: LegalDoc, onClose: () -> Unit) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val suffix = if (language == "ru") "ru" else "uk"
    val body = remember(doc, suffix) {
        runCatching {
            context.assets.open("legal/${doc.assetBase}_$suffix.txt")
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("")
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(doc.titleRes), fontWeight = FontWeight.Bold) },
        text = {
            Text(
                body,
                fontSize = 12.sp,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.legal_close)) }
        },
    )
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
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}
