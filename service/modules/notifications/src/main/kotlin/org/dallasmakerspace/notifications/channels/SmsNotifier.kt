package org.dallasmakerspace.notifications.channels

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.DmsHttpEngine
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.Channel

private const val TWILIO_API_BASE_URL = "https://api.twilio.com/2010-04-01"
private const val BODY_PREFIX = "Dallas Makerspace: "
private const val MAX_BODY_LENGTH = 300
private const val ELLIPSIS = "..."
private const val NANP_COUNTRY_CODE = 1
private val CLIENT_ERROR_STATUSES =
    HttpStatusCode.BadRequest.value..<HttpStatusCode.InternalServerError.value

/** Normalizes a directory phone number to E.164; null unless it is a valid US/Canada number. */
internal fun toE164(raw: String?): String? {
  if (raw.isNullOrBlank() || raw == "null") return null
  val util = PhoneNumberUtil.getInstance()
  return try {
    val number = util.parse(raw, "US")
    if (number.countryCode == NANP_COUNTRY_CODE && util.isValidNumber(number)) {
      util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
    } else {
      null
    }
  } catch (_: NumberParseException) {
    null
  }
}

/** The text sent for [message]: the subject (the short-channel body), branded and length-capped. */
internal fun smsBody(message: OutgoingMessage): String {
  val body = BODY_PREFIX + message.subject.trim()
  return if (body.length <= MAX_BODY_LENGTH) {
    body
  } else {
    body.take(MAX_BODY_LENGTH - ELLIPSIS.length) + ELLIPSIS
  }
}

/**
 * SMS notifier that delivers via the Twilio Messages REST API. Resolves the recipient's phone from
 * Active Directory at send time, and only texts members [ConfigRegistry.NOTIFICATIONS_SMS_ENABLED]
 * is on for. Sends from a Messaging Service when one is configured, else from a phone number.
 */
@Singleton
class SmsNotifier
@Inject
constructor(
    appConfig: AppConfig,
    loggerFactory: LoggerFactory,
    @Named("twilio") private val client: HttpClient,
    private val activeDirectoryService: IActiveDirectoryService,
    private val configOverrideService: ConfigOverrideService,
) : Notifier {
  private val log = loggerFactory.create(javaClass)
  private val json = Json { ignoreUnknownKeys = true }

  private val accountSid = appConfig.getStringProperty("app.notifications.twilio.account-sid", "")
  private val authToken = appConfig.getStringProperty("app.notifications.twilio.auth-token", "")
  private val fromNumber = appConfig.getStringProperty("app.notifications.twilio.from-number", "")
  private val messagingServiceSid =
      appConfig.getStringProperty("app.notifications.twilio.messaging-service-sid", "")

  private val configured =
      accountSid.isNotBlank() &&
          authToken.isNotBlank() &&
          (fromNumber.isNotBlank() || messagingServiceSid.isNotBlank())

  init {
    if (!configured) log.warn("Twilio is not configured; SMS notifications will fail")
  }

  override val channel: Channel = Channel.SMS

  override suspend fun send(memberUsername: String, message: OutgoingMessage): SendResult {
    val member = if (configured) lookUp(memberUsername) else null
    val to = toE164(member?.telephoneNumber)
    return when {
      !configured -> SendResult.PermanentFailure("SMS provider is not configured")
      !smsEnabledFor(memberUsername, member) ->
          SendResult.PermanentFailure("SMS notifications are not enabled for $memberUsername")
      to == null ->
          SendResult.PermanentFailure("No valid US phone number on file for $memberUsername")
      else -> sendSms(to, smsBody(message))
    }
  }

  private suspend fun lookUp(memberUsername: String): ADUser? =
      withContext(Dispatchers.IO) {
        activeDirectoryService.getMembersByUsernameList(listOf(memberUsername))[memberUsername]
      }

  private suspend fun smsEnabledFor(memberUsername: String, member: ADUser?): Boolean =
      configOverrideService.isFeatureEnabledFor(
          ConfigRegistry.NOTIFICATIONS_SMS_ENABLED,
          memberUsername,
          member?.groups?.map { it.cn }.orEmpty())

  @Suppress("TooGenericExceptionCaught") // Catch-all: any provider/network error is transient.
  private suspend fun sendSms(to: String, body: String): SendResult =
      try {
        val response =
            client.submitForm(
                url = "$TWILIO_API_BASE_URL/Accounts/$accountSid/Messages.json",
                formParameters =
                    parameters {
                      append("To", to)
                      append("Body", body)
                      if (messagingServiceSid.isNotBlank()) {
                        append("MessagingServiceSid", messagingServiceSid)
                      } else {
                        append("From", fromNumber)
                      }
                    }) {
                  basicAuth(accountSid, authToken)
                }
        val text = response.bodyAsText()
        when {
          response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK ->
              SendResult.Success(
                  runCatching { json.decodeFromString(TwilioMessage.serializer(), text).sid }
                      .getOrNull())
          response.status == HttpStatusCode.TooManyRequests -> {
            log.warn("Twilio rate limited (429)")
            SendResult.TransientFailure("Twilio rate limited (429)")
          }
          response.status.value in CLIENT_ERROR_STATUSES ->
              SendResult.PermanentFailure(describeError(response.status, text))
          else -> SendResult.TransientFailure(describeError(response.status, text))
        }
      } catch (e: Exception) {
        SendResult.TransientFailure("Twilio request failed: ${e.message}")
      }

  private fun describeError(status: HttpStatusCode, text: String): String {
    val error = runCatching { json.decodeFromString(TwilioError.serializer(), text) }.getOrNull()
    val code = error?.code?.let { " ($it)" } ?: ""
    val detail = error?.message ?: text.take(MAX_ERROR_LENGTH)
    return "Twilio ${status.value}$code: $detail"
  }

  companion object {
    private const val REQUEST_TIMEOUT_MS = 30_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val MAX_ERROR_LENGTH = 500

    /** Builds the shared Twilio HTTP client (10s connect, 30s request, no body logging - PII). */
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
                    org.slf4j.LoggerFactory.getLogger("TwilioClient").info(message)
                  }
                }
            level = LogLevel.INFO
          }
        }
  }
}

@Serializable private data class TwilioMessage(val sid: String? = null)

@Serializable private data class TwilioError(val code: Int? = null, val message: String? = null)
