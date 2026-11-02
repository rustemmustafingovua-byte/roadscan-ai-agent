package ai.roadscan.app.settings

import android.content.Context
import ai.roadscan.core.calibration.CalibrationProfile
import ai.roadscan.core.license.Edition
import ai.roadscan.core.license.LicenseCheck
import ai.roadscan.core.license.LicensePayload
import ai.roadscan.core.license.LicenseState
import ai.roadscan.core.license.LicenseVerifier
import ai.roadscan.core.report.ReportMeta

/**
 * Локальное хранилище настроек комплекса: калибровка, реквизиты документов и
 * лицензия. Всё офлайн, в приватном каталоге приложения.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("roadscan.settings", Context.MODE_PRIVATE)

    // --- калибровка ---

    fun calibration(): CalibrationProfile {
        val raw = prefs.getString(KEY_CALIBRATION, null) ?: return CalibrationProfile.DEFAULT
        return CalibrationProfile.fromJson(raw)
    }

    fun saveCalibration(profile: CalibrationProfile) {
        prefs.edit()
            .putString(KEY_CALIBRATION, CalibrationProfile.toJson(profile))
            .apply()
    }

    // --- реквизиты документов ---

    fun reportMeta(): ReportMeta {
        val raw = prefs.getString(KEY_META, null) ?: return ReportMeta.EMPTY
        return ReportMeta.fromJson(raw)
    }

    fun saveReportMeta(meta: ReportMeta) {
        prefs.edit().putString(KEY_META, ReportMeta.toJson(meta)).apply()
    }

    // --- лицензия ---

    fun licenseKey(): String? = prefs.getString(KEY_LICENSE, null)?.takeIf { it.isNotBlank() }

    /**
     * Текущее состояние лицензии. Пробный период стартует при первом обращении
     * и не сбрасывается переустановкой данных приложения без очистки prefs.
     */
    fun licenseState(nowMs: Long = System.currentTimeMillis()): LicenseState {
        val trialStart = ensureTrialStarted(nowMs)
        val used = prefs.getInt(KEY_SESSIONS_USED, 0)
        val key = licenseKey()
            ?: return LicenseState(
                edition = Edition.TRIAL,
                trialStartedAtMs = trialStart,
                sessionsUsed = used,
            )

        return when (val check = LicenseVerifier.verify(key, nowMs)) {
            is LicenseCheck.Valid -> LicenseState(
                edition = editionOf(check.payload.tier),
                payload = check.payload,
                trialStartedAtMs = trialStart,
                sessionsUsed = used,
            )

            is LicenseCheck.Expired -> LicenseState(
                edition = Edition.TRIAL,
                payload = null,
                trialStartedAtMs = 0,
                sessionsUsed = Edition.TRIAL.trialSessions,
            )

            is LicenseCheck.Invalid -> LicenseState(
                edition = Edition.TRIAL,
                trialStartedAtMs = trialStart,
                sessionsUsed = used,
            )
        }
    }

    /** Активация ключа. Возвращает null при успехе или текст ошибки. */
    fun activate(key: String, nowMs: Long = System.currentTimeMillis()): String? {
        val check = LicenseVerifier.verify(key, nowMs)
        return when (check) {
            is LicenseCheck.Valid -> {
                prefs.edit().putString(KEY_LICENSE, key.trim()).apply()
                null
            }

            is LicenseCheck.Expired -> "EXPIRED"
            is LicenseCheck.Invalid -> check.code
        }
    }

    fun deactivate() {
        prefs.edit().remove(KEY_LICENSE).apply()
    }

    /** Учёт израсходованных смен пробного периода. */
    fun registerSession() {
        val current = prefs.getInt(KEY_SESSIONS_USED, 0)
        prefs.edit()
            .putInt(KEY_SESSIONS_USED, current + 1)
            .putInt(KEY_SESSIONS_TOTAL, sessionsTotal() + 1)
            .apply()
    }

    /** Всего смен на устройстве — для лимита лицензии maxSessions. */
    fun sessionsTotal(): Int = prefs.getInt(KEY_SESSIONS_TOTAL, 0)

    fun firstRunMs(): Long = prefs.getLong(KEY_FIRST_RUN, 0L)

    private fun ensureTrialStarted(nowMs: Long): Long {
        val existing = prefs.getLong(KEY_TRIAL_START, 0L)
        if (existing > 0) return existing
        prefs.edit().putLong(KEY_TRIAL_START, nowMs).putLong(KEY_FIRST_RUN, nowMs).apply()
        return nowMs
    }

    private fun editionOf(tier: String): Edition = when (tier.uppercase()) {
        "ENTERPRISE" -> Edition.ENTERPRISE
        "PILOT" -> Edition.PILOT
        else -> Edition.PRO
    }

    private companion object {
        const val KEY_CALIBRATION = "calibration"
        const val KEY_META = "report_meta"
        const val KEY_LICENSE = "license_key"
        const val KEY_TRIAL_START = "trial_started_ms"
        const val KEY_SESSIONS_USED = "trial_sessions_used"
        const val KEY_SESSIONS_TOTAL = "sessions_total"
        const val KEY_FIRST_RUN = "first_run_ms"
    }
}
