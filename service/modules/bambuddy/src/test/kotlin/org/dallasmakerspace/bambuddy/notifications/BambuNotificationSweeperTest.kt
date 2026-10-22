package org.dallasmakerspace.bambuddy.notifications

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.bambuddy.BambuPrinterStatus
import org.dallasmakerspace.bambuddy.IBambuddyApiClient
import org.dallasmakerspace.bambuddy.notifications.db.BambuCommitteeSubscriptionsRepository
import org.dallasmakerspace.bambuddy.notifications.db.EphemeralNotificationsRepository
import org.dallasmakerspace.bambuddy.notifications.db.PendingOfflineAlertsRepository
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.config.FeatureFlagValue
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.notifications.Channel
import org.dallasmakerspace.notifications.FanOutResult
import org.dallasmakerspace.notifications.NotificationService
import org.dallasmakerspace.notifications.NotificationTopic
import org.dallasmakerspace.notifications.topics.BambuCommitteeAlertsTopic
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

class BambuNotificationSweeperTest {

  private val utc = ZoneId.of("UTC")
  private lateinit var watchRepository: EphemeralNotificationsRepository
  private lateinit var watchService: EphemeralNotificationService
  private lateinit var pendingOfflineRepository: PendingOfflineAlertsRepository
  private lateinit var committeeSubscriptionsRepository: BambuCommitteeSubscriptionsRepository
  private lateinit var apiClient: IBambuddyApiClient
  private lateinit var notificationService: NotificationService
  private lateinit var configOverrideService: ConfigOverrideService
  private lateinit var sweeper: BambuNotificationSweeper

  @Before
  fun setUp() {
    val loggerFactory = mock<LoggerFactory>()
    whenever(loggerFactory.create(any<Class<*>>())).thenReturn(mock<Logger>())
    watchRepository = mock()
    watchService = mock()
    pendingOfflineRepository = mock()
    committeeSubscriptionsRepository = mock()
    apiClient = mock()
    notificationService = mock()
    configOverrideService = mock()
    runBlocking {
      whenever(configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED))
          .thenReturn(FeatureFlagValue(enabled = true))
      whenever(
              configOverrideService.get(
                  ConfigRegistry.BAMBU_NOTIFICATIONS_TOMBSTONE_RETENTION_DAYS))
          .thenReturn(7)
      whenever(watchRepository.findDueArmed(any(), any())).thenReturn(emptyList())
      whenever(watchService.reconcileTerminalWatches(any())).thenReturn(0)
      whenever(pendingOfflineRepository.claimProcessing(any())).thenReturn(true)
      whenever(pendingOfflineRepository.findDuePending(any(), any())).thenReturn(emptyList())
    }
    sweeper =
        BambuNotificationSweeper(
            watchRepository,
            watchService,
            pendingOfflineRepository,
            committeeSubscriptionsRepository,
            apiClient,
            notificationService,
            configOverrideService,
            loggerFactory)
  }

  private fun alert(id: Long = 7, printerId: Int = 3) =
      PendingOfflineAlert(
          id = id,
          printerId = printerId,
          printerName = "Printer 3",
          offlineSince = LocalDateTime.now(utc).minusMinutes(6),
          releaseAt = LocalDateTime.now(utc).minusMinutes(1),
          status = PendingOfflineStatus.PENDING,
          resolvedAt = null,
          notificationsCreated = null)

  private fun stubDueAlert(alert: PendingOfflineAlert) = runBlocking {
    whenever(pendingOfflineRepository.findDuePending(any(), any())).thenReturn(listOf(alert))
  }

  @Test
  fun `still offline at release fans out to subscribers with per-member dedupe keys`() =
      runBlocking {
        val alert = alert()
        stubDueAlert(alert)
        // connected = false folds to OFFLINE regardless of the stale state value.
        whenever(apiClient.getPrinterStatus(3))
            .thenReturn(
                BambuPrinterStatus(id = 3, name = "Printer 3", connected = false, state = "IDLE"))
        whenever(committeeSubscriptionsRepository.getSubscribers())
            .thenReturn(listOf("asmith", "jdoe"))
        whenever(
                notificationService.createForMembers(
                    any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(FanOutResult(created = 2, duplicates = 0, failed = 0))
        whenever(pendingOfflineRepository.claimSent(eq(7L), any(), eq(2))).thenReturn(true)

        val summary = sweeper.releaseDueOfflineAlerts()

        assertTrue(summary.contains("sent=1"), summary)
        val membersCaptor = argumentCaptor<List<String>>()
        val topicCaptor = argumentCaptor<NotificationTopic>()
        val dedupeCaptor = argumentCaptor<(String) -> String?>()
        val consentCaptor = argumentCaptor<Boolean>()
        verify(notificationService)
            .createForMembers(
                membersCaptor.capture(),
                topicCaptor.capture(),
                eq("bambu-printer-offline"),
                any(),
                eq(Channel.EMAIL),
                dedupeCaptor.capture(),
                consentCaptor.capture())
        assertEquals(listOf("asmith", "jdoe"), membersCaptor.firstValue)
        assertEquals(BambuCommitteeAlertsTopic, topicCaptor.firstValue)
        // One episode = one alert per subscriber; keys are per-member by construction.
        assertEquals("bambu:offline:3:7:asmith", dedupeCaptor.firstValue("asmith"))
        assertEquals("bambu:offline:3:7:jdoe", dedupeCaptor.firstValue("jdoe"))
        // Standing subscription: normal preference rules apply, no consent override.
        assertEquals(false, consentCaptor.firstValue)
        verify(pendingOfflineRepository).claimSent(eq(7L), any(), eq(2))
          Unit
  }

  @Test
  fun `back online at release suppresses the alert and creates nothing`() = runBlocking {
    val alert = alert()
    stubDueAlert(alert)
    whenever(apiClient.getPrinterStatus(3))
        .thenReturn(BambuPrinterStatus(id = 3, name = "Printer 3", connected = true, state = "IDLE"))
    whenever(pendingOfflineRepository.claimSuppressed(eq(7L), any())).thenReturn(true)

    val summary = sweeper.releaseDueOfflineAlerts()

    assertTrue(summary.contains("suppressed=1"), summary)
    verify(notificationService, never()).createForMembers(any(), any(), any(), any(), any(), any(), any())
    verify(pendingOfflineRepository).claimSuppressed(eq(7L), any())
      Unit
  }

  @Test
  fun `a failed re-check folds to offline and the alert is still sent`() = runBlocking {
    val alert = alert()
    stubDueAlert(alert)
    whenever(apiClient.getPrinterStatus(3)).thenThrow(RuntimeException("timeout"))
    whenever(committeeSubscriptionsRepository.getSubscribers()).thenReturn(listOf("jdoe"))
    whenever(notificationService.createForMembers(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(FanOutResult(created = 1, duplicates = 0, failed = 0))
    whenever(pendingOfflineRepository.claimSent(eq(7L), any(), eq(1))).thenReturn(true)

    val summary = sweeper.releaseDueOfflineAlerts()

    assertTrue(summary.contains("sent=1"), summary)
  }

  @Test
  fun `zero subscribers still resolves the episode as sent with zero created`() = runBlocking {
    val alert = alert()
    stubDueAlert(alert)
    whenever(apiClient.getPrinterStatus(3)).thenReturn(null) // unreachable folds to OFFLINE
    whenever(committeeSubscriptionsRepository.getSubscribers()).thenReturn(emptyList())
    whenever(notificationService.createForMembers(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(FanOutResult(created = 0, duplicates = 0, failed = 0))
    whenever(pendingOfflineRepository.claimSent(eq(7L), any(), eq(0))).thenReturn(true)

    val summary = sweeper.releaseDueOfflineAlerts()

    assertTrue(summary.contains("sent=1"), summary)
    verify(pendingOfflineRepository).claimSent(eq(7L), any(), eq(0))
      Unit
  }

  @Test
  fun `due watches are handed to the watch service one by one`() = runBlocking {
    val watch =
        EphemeralNotification(
            id = 5,
            memberUsername = "jdoe",
            scope = WatchScope.OWN_JOB,
            printerId = 1,
            printerName = "Printer 1",
            queueId = 10,
            filename = "Job.3mf",
            jobName = "Job.3mf",
            triggerEvents = listOf("print_complete"),
            status = WatchStatus.ARMED,
            rearmCount = 0,
            createdAt = LocalDateTime.now(utc).minusHours(2),
            expiresAt = LocalDateTime.now(utc).minusMinutes(1),
            terminalAt = null,
            firedNotificationId = null,
            terminalReason = null)
    whenever(watchRepository.findDueArmed(any(), any())).thenReturn(listOf(watch))
    whenever(watchService.processExpiredWatch(eq(watch), any())).thenReturn(ExpiryOutcome.FIRED)

    val summary = sweeper.processDueWatches()

    assertTrue(summary.contains("fired=1"), summary)
    verify(watchService).processExpiredWatch(eq(watch), any())
      Unit
  }

  @Test
  fun `purge passes a cutoff of now minus the configured retention to both repositories`() =
      runBlocking {
        whenever(watchRepository.purgeTombstones(any())).thenReturn(3)
        whenever(pendingOfflineRepository.purgeResolved(any())).thenReturn(2)

        val before = LocalDateTime.now(utc)
        val summary = sweeper.purgeTombstones()
        val after = LocalDateTime.now(utc)

        assertTrue(summary.contains("watches=3"), summary)
        assertTrue(summary.contains("offlineAlerts=2"), summary)
        val watchCutoff = argumentCaptor<LocalDateTime>()
        verify(watchRepository).purgeTombstones(watchCutoff.capture())
        assertTrue(watchCutoff.firstValue.isAfter(before.minusDays(7).minusMinutes(1)))
        assertTrue(watchCutoff.firstValue.isBefore(after.minusDays(7).plusMinutes(1)))
      }

  @Test
  fun `passes run in a pilot rollout (enabled false with pilot usernames)`() = runBlocking {
    // QA F4: "any rollout state" gates the sweeper too — a pilot list alone enables passes.
    whenever(configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED))
        .thenReturn(FeatureFlagValue(enabled = false, enabledUsernames = setOf("jdoe")))

    val summary = sweeper.processDueWatches()

    assertTrue(!summary.contains("disabled=true"), summary)
    verify(watchRepository).findDueArmed(any(), any())
      Unit
  }

  @Test
  fun `an episode whose PROCESSING claim is lost is not fanned out by this sweeper`() = runBlocking {
    val alert = alert()
    stubDueAlert(alert)
    whenever(pendingOfflineRepository.claimProcessing(eq(7L))).thenReturn(false)

    val summary = sweeper.releaseDueOfflineAlerts()

    assertTrue(summary.contains("sent=0"), summary)
    verify(notificationService, never()).createForMembers(any(), any(), any(), any(), any(), any(), any())
    verify(pendingOfflineRepository, never()).claimSent(any(), any(), any())
      Unit
  }

  @Test
  fun `every pass no-ops when the feature is fully off`() = runBlocking {
    whenever(configOverrideService.get(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED))
        .thenReturn(FeatureFlagValue(enabled = false))

    assertTrue(sweeper.processDueWatches().contains("disabled=true"))
    assertTrue(sweeper.releaseDueOfflineAlerts().contains("disabled=true"))
    assertTrue(sweeper.purgeTombstones().contains("disabled=true"))
    verify(watchRepository, never()).findDueArmed(any(), any())
    verify(pendingOfflineRepository, never()).findDuePending(any(), any())
      Unit
  }
}
