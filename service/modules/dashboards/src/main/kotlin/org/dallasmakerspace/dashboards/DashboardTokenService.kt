package org.dallasmakerspace.dashboards

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig

/** What a valid dashboard token vouches for. */
data class DashboardTokenClaims(
    val targetId: Int,
    val committeeId: Int,
    val generation: Int,
    val expiresAtMs: Long,
)

/**
 * Signs and checks the device tokens a TV's receiver uses to read its committee's dashboard.
 * A token is `base64url(payload).base64url(HMAC-SHA256(payload))`, payload `t.c.g.exp`. Revoking a
 * TV bumps its generation, which [DashboardService] compares on every verified request.
 */
@Singleton
class DashboardTokenService
internal constructor(
    private val secretValue: () -> String,
    private val clock: Clock,
) {
  @Inject
  constructor(
      appConfig: AppConfig
  ) : this({ appConfig.getStringProperty("app.dashboard.token-secret", "") }, Clock.systemUTC())

  /** Null until DASHBOARD_TOKEN_SECRET is set, so a missing secret never blocks startup. */
  private val secret: ByteArray? by lazy {
    secretValue().takeIf { it.length >= MIN_SECRET_BYTES }?.toByteArray(StandardCharsets.UTF_8)
  }

  val isConfigured: Boolean
    get() = secret != null

  fun issue(targetId: Int, committeeId: Int, generation: Int): TokenResponse {
    check(isConfigured) { "app.dashboard.token-secret is not configured" }
    val expiresAt = clock.millis() + TOKEN_LIFETIME_MS
    val payload = "$targetId.$committeeId.$generation.$expiresAt"
    val body = encode(payload.toByteArray(StandardCharsets.UTF_8))
    return TokenResponse("$body.${encode(sign(body))}", expiresAt)
  }

  /** The claims of a well-formed, correctly signed, unexpired token; null otherwise. */
  @Suppress("ReturnCount")
  fun verify(token: String?): DashboardTokenClaims? {
    if (!isConfigured) return null
    val parts = token?.split('.') ?: return null
    if (parts.size != 2) return null
    val (body, signature) = parts
    val expected = sign(body)
    val given = runCatching { Base64.getUrlDecoder().decode(signature) }.getOrNull() ?: return null
    if (!MessageDigest.isEqual(expected, given)) return null
    val payload =
        runCatching { String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8) }
            .getOrNull() ?: return null
    val fields = payload.split('.').map { it.toLongOrNull() ?: return null }
    if (fields.size != PAYLOAD_FIELDS) return null
    val claims =
        DashboardTokenClaims(
            fields[0].toInt(), fields[1].toInt(), fields[2].toInt(), expiresAtMs = fields[3])
    return claims.takeIf { it.expiresAtMs > clock.millis() }
  }

  private fun sign(body: String): ByteArray =
      Mac.getInstance(HMAC).run {
        init(SecretKeySpec(checkNotNull(secret), HMAC))
        doFinal(body.toByteArray(StandardCharsets.UTF_8))
      }

  private fun encode(bytes: ByteArray): String =
      Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

  companion object {
    private const val HMAC = "HmacSHA256"
    private const val PAYLOAD_FIELDS = 4
    private const val MIN_SECRET_BYTES = 16
    const val TOKEN_LIFETIME_MS = 24L * 60 * 60 * 1000
  }
}
