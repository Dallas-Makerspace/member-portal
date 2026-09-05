package org.dallasmakerspace.notifications.channels

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.Notification

private const val SPARKPOST_TRANSMISSIONS_URL = "https://api.sparkpost.com/api/v1/transmissions"
private const val LOGO_URL = "https://dallasmakerspace.org/wp-content/uploads/56_wide2.png"
private const val PROFILE_MANAGEMENT_URL = "https://members.dallasmakerspace.org/profile-me"

/**
 * Wraps a rendered notification body fragment in the shared DMS-branded HTML email shell (logo
 * header, bordered content card, footer pointing members at their profile to manage subscriptions
 * - not a SparkPost-hosted unsubscribe link). Applied once per email at send time so digest emails
 *   (which concatenate several already-rendered fragments) get exactly one shell, not one per item.
 */
private fun wrapHtml(bodyHtml: String): String {
  val year = java.time.Year.now().value
  return """
      <!doctype html>
      <html lang="en">
      <head><meta charset="UTF-8"><title>Dallas Makerspace</title></head>
      <body style="font-family:'Helvetica Neue',Helvetica,Arial,sans-serif;font-size:16px;line-height:22px;background-color:#ffffff;color:#333333;margin:0;padding:0;">
        <div style="margin:0 auto;max-width:600px;padding:30px;">
          <div style="text-align:center;margin-bottom:20px;">
            <img src="$LOGO_URL" alt="Dallas Makerspace" style="width:220px;max-width:60%;">
          </div>
          <div style="background-color:#f6f6f6;padding:20px;border-radius:10px;">
            $bodyHtml
          </div>
          <div style="text-align:center;margin-top:20px;font-size:13px;color:#888888;">
            Manage your notification preferences at
            <a href="$PROFILE_MANAGEMENT_URL" style="color:#888888;">your DMS profile</a>.
          </div>
        </div>
      </body>
      </html>
  """
      .trimIndent()
}

/** Plain-text counterpart to [wrapHtml]'s footer. */
private fun appendTextFooter(text: String): String =
    "$text\n\n--\nManage your notification preferences at $PROFILE_MANAGEMENT_URL"

/**
 * Email notifier that delivers via the SparkPost REST transmissions API. See
 * `service/docs/sparkpost-contract.md` for the validated API contract.
 */
@Singleton
class EmailNotifier
@Inject
constructor(
    appConfig: AppConfig,
    loggerFactory: LoggerFactory,
    @Named("sparkpost") private val client: HttpClient,
) : Notifier {
  private val log = loggerFactory.create(javaClass)
  private val json = Json { ignoreUnknownKeys = true }

  private val apiKey = appConfig.requireStringProperty("app.notifications.sparkpost.api-key")
  private val fromEmail = appConfig.requireStringProperty("app.notifications.sparkpost.from-email")
  private val fromName = appConfig.requireStringProperty("app.notifications.sparkpost.from-name")

  override val channel: Channel = Channel.EMAIL

  @Suppress("TooGenericExceptionCaught") // Catch-all: any provider/network error is transient.
  override suspend fun send(notification: Notification, recipientEmail: String?): SendResult {
    val to =
        recipientEmail
            ?: return SendResult.PermanentFailure(
                "No email address on file for ${notification.memberUsername}"
            )
    return sendEmail(to, notification.subject, notification.body ?: notification.subject)
  }

  override suspend fun sendDigest(
      memberUsername: String,
      recipientEmail: String?,
      subject: String,
      textBody: String,
      htmlBody: String,
  ): SendResult {
    val to =
        recipientEmail
            ?: return SendResult.PermanentFailure("No email address on file for $memberUsername")
    return sendEmail(to, subject, textBody, htmlBody)
  }

  @Suppress("TooGenericExceptionCaught") // Catch-all: any provider/network error is transient.
  private suspend fun sendEmail(
      to: String,
      subject: String,
      text: String,
      html: String = text,
  ): SendResult {
    val request =
        SparkPostTransmission(
            recipients = listOf(SparkPostRecipient(SparkPostAddress(email = to))),
            content =
                SparkPostContent(
                    from = SparkPostFrom(name = fromName, email = fromEmail),
                    subject = subject,
                    text = appendTextFooter(text),
                    html = wrapHtml(html),
                ),
            options =
                SparkPostOptions(sandbox = false, openTracking = false, clickTracking = false),
        )

    return try {
      val response =
          client.post(SPARKPOST_TRANSMISSIONS_URL) {
            header("Authorization", apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SparkPostTransmission.serializer(), request))
          }
      when (response.status) {
        HttpStatusCode.OK -> {
          val result =
              json.decodeFromString(SparkPostTransmissionResult.serializer(), response.bodyAsText())
          if (result.results.totalRejectedRecipients > 0) {
            SendResult.PermanentFailure(
                "SparkPost rejected ${result.results.totalRejectedRecipients} of 1 recipients"
            )
          } else {
            SendResult.Success(result.results.id)
          }
        }
        HttpStatusCode.TooManyRequests -> {
          log.warn("SparkPost rate limited (429)")
          SendResult.TransientFailure("SparkPost rate limited (429)")
        }
        HttpStatusCode.BadRequest,
        HttpStatusCode.Unauthorized,
        HttpStatusCode.Forbidden,
        HttpStatusCode.NotFound,
        HttpStatusCode.UnprocessableEntity,
        ->
            SendResult.PermanentFailure(
                "SparkPost ${response.status.value}: ${truncate(response.bodyAsText())}"
            )
        else ->
            SendResult.TransientFailure(
                "SparkPost ${response.status.value}: ${truncate(response.bodyAsText())}"
            )
      }
    } catch (e: Exception) {
      SendResult.TransientFailure("SparkPost request failed: ${e.message}")
    }
  }

  private fun truncate(text: String, maxLength: Int = 500): String =
      if (text.length <= maxLength) text else text.take(maxLength) + "..."

  companion object {
    private const val REQUEST_TIMEOUT_MS = 30_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L

    /**
     * Builds the shared SparkPost HTTP client (10s connect, 30s request, no body logging - PII).
     */
    fun createClient(): HttpClient =
        HttpClient(DmsHttpEngine) {
          install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
          }
          install(Logging) {
            logger =
                object : Logger {
                  override fun log(message: String) {
                    org.slf4j.LoggerFactory.getLogger("SparkPostClient").info(message)
                  }
                }
            level = LogLevel.INFO
          }
        }
  }
}

@Serializable
private data class SparkPostTransmission(
    val recipients: List<SparkPostRecipient>,
    val content: SparkPostContent,
    val options: SparkPostOptions,
)

@Serializable private data class SparkPostRecipient(val address: SparkPostAddress)

@Serializable private data class SparkPostAddress(val email: String)

@Serializable
private data class SparkPostContent(
    val from: SparkPostFrom,
    val subject: String,
    val text: String,
    val html: String,
)

@Serializable private data class SparkPostFrom(val name: String, val email: String)

@Serializable
private data class SparkPostOptions(
    val sandbox: Boolean = false,
    @SerialName("open_tracking") val openTracking: Boolean = true,
    @SerialName("click_tracking") val clickTracking: Boolean = true,
)

@Serializable
private data class SparkPostTransmissionResult(val results: SparkPostTransmissionResults)

@Serializable
private data class SparkPostTransmissionResults(
    val id: String? = null,
    @SerialName("total_rejected_recipients") val totalRejectedRecipients: Int = 0,
)
