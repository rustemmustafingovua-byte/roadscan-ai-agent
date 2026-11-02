package ai.roadscan.core.license

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ai.roadscan.core.i18n.CoreMessages
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Офлайн-лицензия коммерческой поставки.
 *
 * Формат ключа: `RSL1.<base64url(payload JSON)>.<base64url(ECDSA P-256 SHA-256)>`.
 * Подпись проверяется по встроенному публичному ключу, закрытый ключ хранится
 * только у поставщика и в приложение не попадает. Сеть для активации не нужна —
 * это требование дорожных служб, работающих в поле без покрытия.
 */
@Serializable
data class LicensePayload(
    val product: String = "roadscan-ai",
    val licenseId: String = "",
    val organization: String = "",
    val tier: String = "PRO",
    val issuedAtMs: Long = 0,
    /** 0 — бессрочная лицензия. */
    val expiresAtMs: Long = 0,
    /** 0 — без ограничения числа смен. */
    val maxSessions: Int = 0,
    val seats: Int = 1,
    val features: List<String> = listOf("pdf", "xlsx", "geojson"),
) {
    fun hasFeature(feature: String): Boolean = features.contains(feature)

    fun isExpired(nowMs: Long): Boolean = expiresAtMs > 0 && nowMs > expiresAtMs
}

sealed interface LicenseCheck {
    data class Valid(val payload: LicensePayload) : LicenseCheck
    data class Expired(val payload: LicensePayload) : LicenseCheck

    /**
     * [code] — машинный код ошибки (FORMAT, SIGNATURE_MISMATCH, ...),
     * локализуется в UI через [ai.roadscan.core.i18n.CoreMessages.licenseError].
     */
    data class Invalid(val code: String) : LicenseCheck

    val payloadOrNull: LicensePayload?
        get() = when (this) {
            is Valid -> payload
            is Expired -> payload
            is Invalid -> null
        }

    val isUsable: Boolean get() = this is Valid
}

object LicenseVerifier {

    const val PRODUCT = "roadscan-ai"
    private const val PREFIX = "RSL1"

    /**
     * Публичный ключ поставщика (ECDSA P-256, X.509 SubjectPublicKeyInfo).
     * Переопределяется buildConfig-ом для white-label поставок.
     */
    var publicKeyBase64: String = DEFAULT_PUBLIC_KEY

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun verify(code: String, nowMs: Long = System.currentTimeMillis()): LicenseCheck {
        val trimmed = code.trim().replace("\n", "").replace("\r", "")
        val parts = trimmed.split('.')
        if (parts.size != 3 || parts[0] != PREFIX) {
            return LicenseCheck.Invalid("FORMAT")
        }
        val payloadBytes = runCatching { decoder.decode(parts[1]) }.getOrNull()
            ?: return LicenseCheck.Invalid("PAYLOAD")
        val signatureBytes = runCatching { decoder.decode(parts[2]) }.getOrNull()
            ?: return LicenseCheck.Invalid("SIGNATURE")

        val key = runCatching { loadPublicKey(publicKeyBase64) }.getOrElse {
            return LicenseCheck.Invalid("PUBLIC_KEY")
        }
        val signatureValid = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(payloadBytes)
                verify(signatureBytes)
            }
        }.getOrDefault(false)
        if (!signatureValid) return LicenseCheck.Invalid("SIGNATURE_MISMATCH")

        val payload = runCatching { json.decodeFromString<LicensePayload>(String(payloadBytes)) }
            .getOrElse { return LicenseCheck.Invalid("UNREADABLE") }

        if (payload.product != PRODUCT) return LicenseCheck.Invalid("WRONG_PRODUCT")
        if (payload.isExpired(nowMs)) return LicenseCheck.Expired(payload)
        return LicenseCheck.Valid(payload)
    }

    /** Короткое представление ключа для UI: первые и последние символы. */
    fun fingerprint(code: String): String {
        val payload = code.split('.').getOrNull(1) ?: return "—"
        return if (payload.length <= 12) payload else "${payload.take(6)}…${payload.takeLast(6)}"
    }

    private fun loadPublicKey(base64: String): PublicKey {
        val der = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    // Поставщицкий ключ RoadScan AI (P-256). Закрытая часть — вне репозитория.
    private const val DEFAULT_PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0PwHLcm4/dAtQHw2qAtqouaAJ+uQZZLMkW6gIfapgQYYxnqE7tjt0+7mBVvzN+l3oVwoQW3NUq/efntgnZJaMQ=="
}

/**
 * Коммерческие редакции продукта. Пилот — для согласования методики на объекте,
 * PRO — бригада/подрядчик, ENTERPRISE — дорожная служба с ГИС-интеграцией.
 * Названия редакций локализуются в UI через ресурсы приложения.
 */
enum class Edition(
    val trialDays: Int,
    val trialSessions: Int,
    val features: Set<String>,
) {
    TRIAL(trialDays = 14, trialSessions = 5, features = setOf("pdf", "xlsx", "geojson")),
    PILOT(trialDays = 0, trialSessions = 0, features = setOf("pdf", "xlsx", "geojson")),
    PRO(trialDays = 0, trialSessions = 0, features = setOf("pdf", "xlsx", "geojson", "gis")),
    ENTERPRISE(
        trialDays = 0,
        trialSessions = 0,
        features = setOf("pdf", "xlsx", "geojson", "gis", "api", "white_label"),
    ),
}

/**
 * Состояние лицензии на устройстве: пробный период, активированный ключ или
 * истёкший ключ. Единая точка правды для UI и для гейтинга экспорта.
 */
data class LicenseState(
    val edition: Edition = Edition.TRIAL,
    val payload: LicensePayload? = null,
    val trialStartedAtMs: Long = 0,
    val sessionsUsed: Int = 0,
) {
    fun trialDaysLeft(nowMs: Long): Int {
        if (trialStartedAtMs <= 0) return 0
        val elapsedDays = ((nowMs - trialStartedAtMs) / 86_400_000L).toInt()
        return (Edition.TRIAL.trialDays - elapsedDays).coerceAtLeast(0)
    }

    fun trialSessionsLeft(): Int =
        (Edition.TRIAL.trialSessions - sessionsUsed).coerceAtLeast(0)

    fun isTrialActive(nowMs: Long): Boolean =
        payload == null && trialDaysLeft(nowMs) > 0 && trialSessionsLeft() > 0

    fun isLicensed(nowMs: Long = System.currentTimeMillis()): Boolean =
        payload != null && !payload.isExpired(nowMs)

    fun demoWatermark(nowMs: Long = System.currentTimeMillis()): Boolean = !isLicensed(nowMs)

    fun canExport(nowMs: Long = System.currentTimeMillis()): Boolean =
        isLicensed(nowMs) || isTrialActive(nowMs)

    fun hasFeature(feature: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isLicensed(nowMs)) {
            return payload?.hasFeature(feature) == true
        }
        return isTrialActive(nowMs) && feature in Edition.TRIAL.features
    }

    fun summary(nowMs: Long, messages: CoreMessages = CoreMessages.DEFAULT): String = when {
        isLicensed(nowMs) -> {
            val p = payload!!
            val until = if (p.expiresAtMs == 0L) messages.forever else formatDate(p.expiresAtMs)
            messages.licenseValid(
                organization = p.organization.ifBlank { messages.licenseFallbackOrganization },
                tier = messages.edition(edition),
                until = until,
            )
        }

        isTrialActive(nowMs) -> messages.trialActive(trialSessionsLeft(), trialDaysLeft(nowMs))

        else -> messages.trialExpired
    }

    private fun formatDate(ms: Long): String =
        java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))
}
