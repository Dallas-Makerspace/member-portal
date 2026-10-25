package org.dallasmakerspace.bambuddy.notifications.webhook

import io.ktor.http.HttpStatusCode
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.bambuddy.notifications.BambuCorrelationService
import org.dallasmakerspace.bambuddy.notifications.BambuWakeSignal
import org.dallasmakerspace.bambuddy.notifications.CorrelationMode
import org.dallasmakerspace.bambuddy.notifications.CorrelationResult
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotification
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotificationService
import org.dallasmakerspace.bambuddy.notifications.PendingOfflineAlert
import org.dallasmakerspace.bambuddy.notifications.PendingOfflineStatus
import org.dallasmakerspace.bambuddy.notifications.WatchScope
import org.dallasmakerspace.bambuddy.notifications.WatchStatus
import org.dallasmakerspace.bambuddy.notifications.db.PendingOfflineAlertsRepository
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class BambuddyWebhookHandlerTest {

  private lateinit var correlationService: BambuCorrelationService
  private lateinit var watchService: EphemeralNotificationService
  private lateinit var pendingOfflineRepository: PendingOfflineAlertsRepository
  private lateinit var configOverrideService: ConfigOverrideService
  private lateinit var appConfig: AppConfig
  private lateinit var wakeSignal: BambuWakeSignal
  private lateinit var handler: BambuddyWebhookHandler

  @Before
  fun setUp() {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    correlationService = mock()
    watchService = mock()
    pendingOfflineRepository = mock()
    configOverrideService = mock()
    appConfig = mock()
    wakeSignal = mock()
    runBlocking {
      whenever(appConfig.getStringProperty(eq("app.bambuddy.webhook-secret"), any()))
          .thenReturn("test-secret")
      whenever(appConfig.requireBooleanProperty("ktor.development")).thenReturn(false)
      whenever(configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED))
          .thenReturn(FeatureFlagValue(enabled = true))
      whenever(configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_OFFLINE_HOLD_MINUTES))
          .thenReturn(5)
    }
    handler =
        BambuddyWebhookHandler(
            correlationService,
            watchService,
            pendingOfflineRepository,
            configOverrideService,
            appConfig,
            wakeSignal,
            loggerFactory)
  }

  private fun printPayload(event: String = "print_complete") =
      """{"event":"$event","printer":"Printer 1","filename":"Job.3mf","source":"Bambuddy"}"""

  private fun watch(id: Long) =
      EphemeralNotification(
          id = id,
          memberUsername = "jdoe",
          scope = WatchScope.OWN_JOB,
          printerId = 1,
          printerName = "Printer 1",
          queueId = 10,
          filename = "Job.3mf",
          jobName = "Job.3mf",
          triggerEvents = listOf("print_complete", "print_failed", "print_stopped"),
          status = WatchStatus.ARMED,
          rearmCount = 0,
          createdAt = LocalDateTime.now(ZoneId.of("UTC")),
          expiresAt = LocalDateTime.now(ZoneId.of("UTC")).plusHours(1),
          terminalAt = null,
          firedNotificationId = null,
          terminalReason = null)

  @Test
  fun `valid Bearer secret and an unknown event are accepted and ignored`() = runBlocking {
    assertEquals(HttpStatusCode.OK, handler.handle("Bearer test-secret", printPayload("print_start")))
  }

  @Test
  fun `the bare secret without a Bearer prefix is also accepted`() = runBlocking {
    assertEquals(HttpStatusCode.OK, handler.handle("test-secret", printPayload("print_start")))
  }

  @Test
  fun `wrong or missing secret is 401`() = runBlocking {
    assertEquals(HttpStatusCode.Unauthorized, handler.handle("Bearer wrong", printPayload()))
    assertEquals(HttpStatusCode.Unauthorized, handler.handle(null, printPayload()))
    assertEquals(HttpStatusCode.Unauthorized, handler.handle("", printPayload()))
  }

  @Test
  fun `no configured secret fails closed with 401 even for a well-formed call`() = runBlocking {
    whenever(appConfig.getStringProperty(eq("app.bambuddy.webhook-secret"), any())).thenReturn("")

    assertEquals(HttpStatusCode.Unauthorized, handler.handle("Bearer test-secret", printPayload()))
  }

  @Test
  fun `the dev placeholder secret is refused outside development`() = runBlocking {
    whenever(appConfig.getStringProperty(eq("app.bambuddy.webhook-secret"), any()))
        .thenReturn("dev-only-placeholder")

    assertEquals(
        HttpStatusCode.Unauthorized,
        handler.handle("Bearer dev-only-placeholder", printPayload("print_start")))

    whenever(appConfig.requireBooleanProperty("ktor.development")).thenReturn(true)
    assertEquals(
        HttpStatusCode.OK, handler.handle("Bearer dev-only-placeholder", printPayload("print_start")))
  }

  @Test
  fun `the env template secret is refused in every mode`() = runBlocking {
    whenever(appConfig.getStringProperty(eq("app.bambuddy.webhook-secret"), any()))
        .thenReturn("your-bambuddy-webhook-secret-here")
    val header = "Bearer your-bambuddy-webhook-secret-here"

    assertEquals(HttpStatusCode.Unauthorized, handler.handle(header, printPayload("print_start")))

    whenever(appConfig.requireBooleanProperty("ktor.development")).thenReturn(true)
    assertEquals(HttpStatusCode.Unauthorized, handler.handle(header, printPayload("print_start")))
  }

  @Test
  fun `malformed JSON is 400`() = runBlocking {
    assertEquals(HttpStatusCode.BadRequest, handler.handle("Bearer test-secret", "{not json"))
    assertEquals(HttpStatusCode.BadRequest, handler.handle("Bearer test-secret", """{"printer":"x"}"""))
  }

  @Test
  fun `auth is checked before the body is parsed`() = runBlocking {
    assertEquals(HttpStatusCode.Unauthorized, handler.handle("Bearer wrong", "{not json"))
  }

  @Test
  fun `feature fully off still answers 200 but processes nothing`() = runBlocking {
    whenever(configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED))
        .thenReturn(FeatureFlagValue(enabled = false))

    assertEquals(HttpStatusCode.OK, handler.handle("Bearer test-secret", printPayload()))

    verify(correlationService, never()).correlatePrintEvent(any())
      Unit
  }

  @Test
  fun `a print event fires every correlated watch`() = runBlocking {
    val w = watch(7)
    whenever(correlationService.correlatePrintEvent(any()))
        .thenReturn(CorrelationResult(CorrelationMode.FIRED, 1, listOf(w)))
    whenever(watchService.fireWatch(any(), any(), any(), any())).thenReturn(true)

    assertEquals(HttpStatusCode.OK, handler.handle("Bearer test-secret", printPayload()))

    verify(watchService).fireWatch(eq(w), eq("print_complete"), any(), any())
      Unit
  }

  @Test
  fun `one failing watch does not stop the rest, and the event answers 500 afterwards`() =
      runBlocking {
        val first = watch(7)
        val second = watch(8)
        whenever(correlationService.correlatePrintEvent(any()))
            .thenReturn(CorrelationResult(CorrelationMode.FIRED, 1, listOf(first, second)))
        whenever(watchService.fireWatch(eq(first), any(), any(), any()))
            .thenThrow(RuntimeException("notification store unavailable"))
        whenever(watchService.fireWatch(eq(second), any(), any(), any())).thenReturn(true)

        assertEquals(
            HttpStatusCode.InternalServerError, handler.handle("Bearer test-secret", printPayload()))

        verify(watchService).fireWatch(eq(first), eq("print_complete"), any(), any())
        verify(watchService).fireWatch(eq(second), eq("print_complete"), any(), any())
          Unit
      }

  @Test
  fun `a print event with no matching watch is still 200`() = runBlocking {
    whenever(correlationService.correlatePrintEvent(any()))
        .thenReturn(CorrelationResult(CorrelationMode.NO_WATCH, 1))

    assertEquals(HttpStatusCode.OK, handler.handle("Bearer test-secret", printPayload()))
    verify(watchService, never()).fireWatch(any(), any(), any(), any())
      Unit
  }

  @Test
  fun `a print event missing the printer field is 200 without correlation`() = runBlocking {
    assertEquals(
        HttpStatusCode.OK,
        handler.handle("Bearer test-secret", """{"event":"print_complete","filename":"Job.3mf"}"""))
    verify(correlationService, never()).correlatePrintEvent(any())
      Unit
  }

  @Test
  fun `an internal failure surfaces as 500 so it shows in Bambuddy's provider log`() = runBlocking {
    whenever(correlationService.correlatePrintEvent(any()))
        .thenThrow(RuntimeException("db down"))

    assertEquals(
        HttpStatusCode.InternalServerError, handler.handle("Bearer test-secret", printPayload()))
  }

  @Test
  fun `printer_offline inserts one held episode with release after the configured hold`() =
      runBlocking {
        whenever(correlationService.resolvePrinterId("Printer 1")).thenReturn(1)
        whenever(pendingOfflineRepository.insertIfNoPending(any()))
            .thenAnswer { it.getArgument<PendingOfflineAlert>(0).copy(id = 9) }

        val before = LocalDateTime.now(ZoneId.of("UTC"))
        val code =
            handler.handle(
                "Bearer test-secret", """{"event":"printer_offline","printer":"Printer 1"}""")
        assertEquals(HttpStatusCode.OK, code)

        val captor = argumentCaptor<PendingOfflineAlert>()
        verify(pendingOfflineRepository).insertIfNoPending(captor.capture())
        val inserted = captor.firstValue
        assertEquals(1, inserted.printerId)
        assertEquals(PendingOfflineStatus.PENDING, inserted.status)
        assertTrue(!inserted.offlineSince.isBefore(before))
        assertEquals(
            inserted.offlineSince.plusMinutes(5), inserted.releaseAt, "release = receipt + hold")
        verify(wakeSignal).wake()
      }

  @Test
  fun `a duplicate printer_offline during the hold does not insert a second episode`() =
      runBlocking {
        whenever(correlationService.resolvePrinterId("Printer 1")).thenReturn(1)
        // An episode is already pending: the atomic insert-if-absent inserts nothing.
        whenever(pendingOfflineRepository.insertIfNoPending(any())).thenReturn(null)

        assertEquals(
            HttpStatusCode.OK,
            handler.handle("Bearer test-secret", """{"event":"printer_offline","printer":"Printer 1"}"""))
        verify(pendingOfflineRepository).insertIfNoPending(any())
        verify(wakeSignal, never()).wake()
          Unit
  }

  @Test
  fun `a full realistic generic payload parses and its fields reach fireWatch`() = runBlocking {
    // QA F1: every field BamBuddy's generic format sends (plus unknown extras and an image
    // string, which must be ignored, not decoded) parses without failing the event.
    val w = watch(7)
    whenever(correlationService.correlatePrintEvent(any()))
        .thenReturn(CorrelationResult(CorrelationMode.FIRED, 1, listOf(w)))
    whenever(watchService.fireWatch(any(), any(), any(), any())).thenReturn(true)
    val body =
        """{"event":"print_failed","printer":"Printer 1","filename":"Job.3mf",""" +
            """"title":"Print failed on Printer 1","message":"Job.3mf failed",""" +
            """"timestamp":"2026-10-22T15:42:00","source":"Bambuddy",""" +
            """"duration":"1h 5m","reason":"Filament runout","filament_grams":12.5,""" +
            """"progress":87.0,"finish_photo_url":"https://example.test/photo.jpg",""" +
            """"filament_details":"PLA","unknown_extra":{"nested":true},""" +
            """"image":"aGVsbG8gd29ybGQ="}"""

    assertEquals(HttpStatusCode.OK, handler.handle("Bearer test-secret", body))

    val payloadCaptor = argumentCaptor<BambuddyWebhookPayload>()
    verify(watchService).fireWatch(eq(w), eq("print_failed"), payloadCaptor.capture(), any())
    val parsed = payloadCaptor.firstValue
    assertEquals("Filament runout", parsed.reason)
    assertEquals("1h 5m", parsed.duration)
    assertEquals(12.5, parsed.filamentGrams)
    assertEquals(87.0, parsed.progress)
    assertEquals("https://example.test/photo.jpg", parsed.finishPhotoUrl)
    assertEquals("Print failed on Printer 1", parsed.title)
      Unit
  }

  @Test
  fun `pilot rollout (enabled false with pilot usernames) still processes events`() = runBlocking {
    // QA F4: receiver gating is "any rollout state", so a pilot list alone enables processing.
    whenever(configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED))
        .thenReturn(FeatureFlagValue(enabled = false, enabledUsernames = setOf("jdoe")))
    val w = watch(7)
    whenever(correlationService.correlatePrintEvent(any()))
        .thenReturn(CorrelationResult(CorrelationMode.FIRED, 1, listOf(w)))
    whenever(watchService.fireWatch(any(), any(), any(), any())).thenReturn(true)

    assertEquals(HttpStatusCode.OK, handler.handle("Bearer test-secret", printPayload()))
    verify(watchService).fireWatch(eq(w), eq("print_complete"), any(), any())
      Unit
  }

  @Test
  fun `empty body is 400, an empty event is 200 ignored, a blank printer_offline is 200`() =
      runBlocking {
        assertEquals(HttpStatusCode.BadRequest, handler.handle("Bearer test-secret", ""))
        assertEquals(HttpStatusCode.OK, handler.handle("Bearer test-secret", """{"event":""}"""))
        assertEquals(
            HttpStatusCode.OK,
            handler.handle("Bearer test-secret", """{"event":"printer_offline","printer":"  "}"""))
        verify(pendingOfflineRepository, never()).insertIfNoPending(any())
        verify(correlationService, never()).correlatePrintEvent(any())
          Unit
      }

  @Test
  fun `printer_offline for an unknown printer is 200 and inserts nothing`() = runBlocking {
    whenever(correlationService.resolvePrinterId("Printer 1")).thenReturn(null)

    assertEquals(
        HttpStatusCode.OK,
        handler.handle("Bearer test-secret", """{"event":"printer_offline","printer":"Printer 1"}"""))
    verify(pendingOfflineRepository, never()).insertIfNoPending(any())
      Unit
  }
}
