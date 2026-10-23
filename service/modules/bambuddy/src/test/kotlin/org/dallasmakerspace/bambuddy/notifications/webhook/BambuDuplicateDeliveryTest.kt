package org.dallasmakerspace.bambuddy.notifications.webhook

import io.ktor.http.HttpStatusCode
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.bambuddy.BambuPrinter
import org.dallasmakerspace.bambuddy.BambuQueueItem
import org.dallasmakerspace.bambuddy.IBambuddyApiClient
import org.dallasmakerspace.bambuddy.notifications.BambuCorrelationService
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotification
import org.dallasmakerspace.bambuddy.notifications.EphemeralNotificationService
import org.dallasmakerspace.bambuddy.notifications.FakeEphemeralNotificationsRepository
import org.dallasmakerspace.bambuddy.notifications.WatchScope
import org.dallasmakerspace.bambuddy.notifications.WatchStatus
import org.dallasmakerspace.bambuddy.notifications.db.PendingOfflineAlertsRepository
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.CreateNotificationRequest
import org.dallasmakerspace.notifications.Notification
import org.dallasmakerspace.notifications.NotificationService
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

/**
 * QA F5: duplicate webhook delivery. Real [BambuCorrelationService] + real
 * [EphemeralNotificationService] over the in-memory fake repository; the same payload is
 * delivered twice (BamBuddy retry / double-send) and exactly one notification is created —
 * the first delivery consumes the watch (claim), the second correlates to no ARMED watch.
 */
class BambuDuplicateDeliveryTest {

  private val createdRequests = mutableListOf<CreateNotificationRequest>()
  private lateinit var handler: BambuddyWebhookHandler

  @Before
  fun setUp() {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    val repository = FakeEphemeralNotificationsRepository(loggerFactory)
    val utc = ZoneId.of("UTC")
    runBlocking {
      repository.insert(
          EphemeralNotification(
              id = 0,
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
              createdAt = LocalDateTime.now(utc),
              expiresAt = LocalDateTime.now(utc).plusHours(1),
              terminalAt = null,
              firedNotificationId = null,
              terminalReason = null))
    }
    val apiClient = mock<IBambuddyApiClient>()
    runBlocking {
      whenever(apiClient.getPrinters()).thenReturn(listOf(BambuPrinter(id = 1, name = "Printer 1")))
      whenever(apiClient.getQueue())
          .thenReturn(
              listOf(BambuQueueItem(id = 10, printerId = 1, status = "completed", archiveName = "Job.3mf")))
    }
    val notificationService = mock<NotificationService>()
    createdRequests.clear()
    runBlocking {
      whenever(notificationService.create(any())).thenAnswer {
        val request = it.getArgument<CreateNotificationRequest>(0)
        createdRequests += request
        val notification = mock<Notification>()
        whenever(notification.id).thenReturn(9000L + createdRequests.size)
        notification
      }
    }
    val watchService = EphemeralNotificationService(repository, apiClient, notificationService, loggerFactory)
    val correlationService = BambuCorrelationService(apiClient, repository, loggerFactory)
    val configOverrideService = mock<ConfigOverrideService>()
    val appConfig = mock<AppConfig>()
    runBlocking {
      whenever(appConfig.getStringProperty(eq("app.bambuddy.webhook-secret"), any()))
          .thenReturn("test-secret")
      whenever(appConfig.requireBooleanProperty("ktor.development")).thenReturn(false)
      whenever(configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED))
          .thenReturn(FeatureFlagValue(enabled = true))
    }
    handler =
        BambuddyWebhookHandler(
            correlationService,
            watchService,
            mock<PendingOfflineAlertsRepository>(),
            configOverrideService,
            appConfig,
            loggerFactory)
  }

  @Test
  fun `the same payload delivered twice creates exactly one notification`() = runBlocking {
    val body = """{"event":"print_complete","printer":"Printer 1","filename":"Job.3mf"}"""

    assertEquals(HttpStatusCode.OK, handler.handle("Bearer test-secret", body))
    assertEquals(HttpStatusCode.OK, handler.handle("Bearer test-secret", body))

    assertEquals(1, createdRequests.size)
    assertEquals("bambu:1:10:print_complete:1", createdRequests.single().dedupeKey)
  }
}
