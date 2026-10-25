package org.dallasmakerspace.notifications.templates

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import org.dallasmakerspace.notifications.di.NotificationsModule
import org.dallasmakerspace.notifications.topics.BambuCommitteeAlertsTopic
import org.dallasmakerspace.notifications.topics.BambuPrintActionRequiredTopic
import org.dallasmakerspace.notifications.topics.BambuPrintFinishedTopic
import org.dallasmakerspace.notifications.topics.BambuPrinterAvailableTopic
import org.dallasmakerspace.notifications.topics.BambuWatchTopic
import org.junit.Test

/**
 * The Bambu templates (design §4): exact subjects, the job name carried in the body, and hostile
 * (member-controlled) job names HTML-escaped in the HTML rendering.
 */
class BambuTemplatesTest {

  private val hostileJob = "<script>alert(1)</script> & \"co\""

  @Test
  fun `finished template subject and body carry printer and job name`() {
    val template = BambuPrintFinishedTemplate()
    val data =
        BambuPrintFinishedData(
            printerName = "Printer 1",
            jobName = "Bracket v3",
            completedAtText = "Thu, Oct 22 at 3:42 PM CT",
            durationText = "2h 14m",
            filamentGramsText = "23.5 g",
            statusPageUrl = "https://members.dallasmakerspace.org/committees/3d-fab/printers")

    assertEquals("Your print finished on Printer 1", template.renderSubject(data))
    assertEquals(BambuPrintFinishedTopic, template.topic)
    val text = template.renderText(data)
    assertTrue(text.contains("Bracket v3"))
    assertTrue(text.contains("2h 14m"))
    assertTrue(text.contains("23.5 g"))
    assertTrue(template.renderHtml(data).contains("Bracket v3"))
  }

  @Test
  fun `finished template escapes a hostile job name in html`() {
    val template = BambuPrintFinishedTemplate()
    val data = BambuPrintFinishedData(printerName = "Printer 1", jobName = hostileJob)

    val html = template.renderHtml(data)
    assertFalse(html.contains("<script>alert(1)</script>"))
    assertTrue(html.contains("&lt;script&gt;"))
  }

  @Test
  fun `action-required template covers failed and stopped with the reason`() {
    val template = BambuPrintActionRequiredTemplate()
    assertEquals(BambuPrintActionRequiredTopic, template.topic)

    val failed =
        BambuPrintActionRequiredData(
            printerName = "Printer 2",
            jobName = "Enclosure lid",
            eventLabel = "failed",
            reason = "Filament runout",
            occurredAtText = "Thu, Oct 22 at 4:01 PM CT",
            statusPageUrl = "https://example.test/status")
    assertEquals("Your print needs attention on Printer 2", template.renderSubject(failed))
    val failedText = template.renderText(failed)
    assertTrue(failedText.contains("Enclosure lid"))
    assertTrue(failedText.contains("failed"))
    assertTrue(failedText.contains("Filament runout"))

    val stopped = failed.copy(eventLabel = "was stopped", reason = null)
    assertTrue(template.renderText(stopped).contains("was stopped"))

    val hostile = failed.copy(jobName = hostileJob)
    assertFalse(template.renderHtml(hostile).contains("<script>alert(1)</script>"))
  }

  @Test
  fun `printer-available template names the printer and the freed-by job`() {
    val template = BambuPrinterAvailableTemplate()
    val data =
        BambuPrinterAvailableData(
            printerName = "Printer 3",
            freedByJobName = "Test cube",
            occurredAtText = "Thu, Oct 22 at 5:20 PM CT",
            statusPageUrl = "https://example.test/status")

    assertEquals("Printer 3 is available", template.renderSubject(data))
    assertEquals(BambuPrinterAvailableTopic, template.topic)
    assertTrue(template.renderText(data).contains("Test cube"))
  }

  @Test
  fun `watch overdue and expired templates share the watch topic and carry the job name`() {
    val data =
        BambuWatchNoticeData(
            printerName = "Printer 1",
            jobName = "Bracket v3",
            scopeLabel = "your print",
            statusPageUrl = "https://example.test/status")

    val overdue = BambuWatchOverdueTemplate()
    assertEquals(BambuWatchTopic, overdue.topic)
    assertEquals(
        "On Printer 1, your print is taking longer than expected", overdue.renderSubject(data))
    assertTrue(overdue.renderText(data).contains("Bracket v3"))
    // The scope label is honoured, not hardcoded to "your print": an available-watch's
    // overdue notice talks about the print the member was waiting on.
    val availableData = data.copy(scopeLabel = "the print you were waiting on")
    assertEquals(
        "On Printer 1, the print you were waiting on is taking longer than expected",
        overdue.renderSubject(availableData))
    assertTrue(overdue.renderText(availableData).contains("the print you were waiting on"))
    assertTrue(overdue.renderHtml(availableData).contains("the print you were waiting on"))

    val expired = BambuWatchExpiredTemplate()
    assertEquals(BambuWatchTopic, expired.topic)
    assertEquals("Please check your print on Printer 1", expired.renderSubject(data))
    assertTrue(expired.renderText(data).contains("check"))
  }

  @Test
  fun `printer offline template is the committee topic with the offline subject`() {
    val template = BambuPrinterOfflineTemplate()
    val data =
        BambuPrinterOfflineData(
            printerName = "Printer 4",
            offlineSinceText = "Thu, Oct 22 at 6:00 PM CT",
            statusPageUrl = "https://example.test/status")

    assertEquals(BambuCommitteeAlertsTopic, template.topic)
    assertEquals("3D printer offline: Printer 4", template.renderSubject(data))
    assertTrue(template.renderText(data).contains("Printer 4"))
  }

  @Test
  fun `printer offline template states the configured hold, with a generic fallback`() {
    val template = BambuPrinterOfflineTemplate()
    val withHold = BambuPrinterOfflineData(printerName = "Printer 4", holdMinutes = 12)
    assertTrue(template.renderText(withHold).contains("checked again 12 minutes later"))
    assertTrue(template.renderHtml(withHold).contains("checked again 12 minutes later"))
    assertTrue(
        template
            .renderText(BambuPrinterOfflineData(printerName = "Printer 4", holdMinutes = 1))
            .contains("checked again 1 minute later"))
    assertTrue(
        template
            .renderText(BambuPrinterOfflineData(printerName = "Printer 4"))
            .contains("a few minutes later"))
  }

  @Test
  fun `printer-available template warns the last print may still be on the bed`() {
    val template = BambuPrinterAvailableTemplate()
    val data = BambuPrinterAvailableData(printerName = "Printer 3")
    assertTrue(template.renderText(data).contains("may still need to be cleared from the bed"))
    assertTrue(template.renderHtml(data).contains("may still need to be cleared from the bed"))
  }

  @Test
  fun `registry resolves all five bambu topics from the registered templates`() {
    val registry =
        TemplateRegistry(
            setOf(
                BambuPrintFinishedTemplate(),
                BambuPrintActionRequiredTemplate(),
                BambuPrinterAvailableTemplate(),
                BambuWatchOverdueTemplate(),
                BambuWatchExpiredTemplate(),
                BambuPrinterOfflineTemplate(),
            ))

    assertEquals(BambuPrintFinishedTopic, registry.findTopic("bambu.print.finished"))
    assertEquals(BambuPrintActionRequiredTopic, registry.findTopic("bambu.print.action-required"))
    assertEquals(BambuPrinterAvailableTopic, registry.findTopic("bambu.printer.available"))
    assertEquals(BambuWatchTopic, registry.findTopic("bambu.watch"))
    assertEquals(BambuCommitteeAlertsTopic, registry.findTopic("bambu.committee-alerts"))

    assertEquals(
        setOf(
            BambuPrintFinishedTopic,
            BambuPrintActionRequiredTopic,
            BambuPrinterAvailableTopic,
            BambuWatchTopic,
            BambuCommitteeAlertsTopic),
        registry.topics)

    val rendered =
        registry.render(
            "bambu-print-finished",
            """{"printerName":"Printer 1","jobName":"Bracket v3","statusPageUrl":"https://example.test/status"}""")
    assertEquals("Your print finished on Printer 1", rendered.subject)
  }

  @Test
  fun `hostile strings are escaped in the html of the remaining four templates`() {
    // QA F10: printer-available, printer-offline, watch-overdue and watch-expired (the
    // finished and action-required templates are covered above).
    val availableHtml =
        BambuPrinterAvailableTemplate()
            .renderHtml(
                BambuPrinterAvailableData(
                    printerName = hostileJob, freedByJobName = hostileJob, occurredAtText = "now"))
    assertFalse(availableHtml.contains("<script>alert(1)</script>"))

    val offlineHtml =
        BambuPrinterOfflineTemplate()
            .renderHtml(BambuPrinterOfflineData(printerName = hostileJob, offlineSinceText = "then"))
    assertFalse(offlineHtml.contains("<script>alert(1)</script>"))

    val watchData =
        BambuWatchNoticeData(printerName = hostileJob, jobName = hostileJob, scopeLabel = "your print")
    assertFalse(BambuWatchOverdueTemplate().renderHtml(watchData).contains("<script>"))
    assertFalse(BambuWatchExpiredTemplate().renderHtml(watchData).contains("<script>"))
  }

  @Test
  fun `the terminal overdue variant does not claim the watch is still being watched`() {
    val data =
        BambuWatchNoticeData(
            printerName = "Printer 1",
            jobName = "Bracket v3",
            scopeLabel = "your print",
            stillWatching = false)
    val template = BambuWatchOverdueTemplate()
    assertFalse(template.renderText(data).contains("still watching"))
    assertFalse(template.renderHtml(data).contains("still watching"))
    assertTrue(template.renderText(data).contains("ending"))
  }

  @Test
  fun `all six bambu templates resolve through the dagger module providers and round-trip`() {
    // QA F3: pull the template set from NotificationsModule itself (not a hand-built set),
    // then render each template from JSON encoded exactly as the producers encode it
    // (kotlinx, encodeDefaults = true).
    val templates =
        setOf(
            NotificationsModule.provideBambuPrintFinishedTemplate(),
            NotificationsModule.provideBambuPrintActionRequiredTemplate(),
            NotificationsModule.provideBambuPrinterAvailableTemplate(),
            NotificationsModule.provideBambuWatchOverdueTemplate(),
            NotificationsModule.provideBambuWatchExpiredTemplate(),
            NotificationsModule.provideBambuPrinterOfflineTemplate(),
        )
    val registry = TemplateRegistry(templates)
    val json = kotlinx.serialization.json.Json { encodeDefaults = true }

    for (name in
        listOf(
            "bambu-print-finished",
            "bambu-print-action-required",
            "bambu-printer-available",
            "bambu-watch-overdue",
            "bambu-watch-expired",
            "bambu-printer-offline")) {
      assertTrue(registry.find(name) != null, "template $name must resolve from the module set")
    }

    val actionRequired =
        registry.render(
            "bambu-print-action-required",
            json.encodeToString(
                BambuPrintActionRequiredData.serializer(),
                BambuPrintActionRequiredData(
                    printerName = "Printer 2",
                    jobName = "Enclosure lid",
                    eventLabel = "failed",
                    reason = "Filament runout",
                    occurredAtText = "Thu, Oct 22 at 4:01 PM CT",
                    statusPageUrl = "https://example.test/status")))
    assertEquals("Your print needs attention on Printer 2", actionRequired.subject)

    val available =
        registry.render(
            "bambu-printer-available",
            json.encodeToString(
                BambuPrinterAvailableData.serializer(),
                BambuPrinterAvailableData(
                    printerName = "Printer 3",
                    freedByJobName = "Test cube",
                    occurredAtText = "Thu, Oct 22 at 5:20 PM CT",
                    statusPageUrl = "https://example.test/status")))
    assertEquals("Printer 3 is available", available.subject)

    val overdue =
        registry.render(
            "bambu-watch-overdue",
            json.encodeToString(
                BambuWatchNoticeData.serializer(),
                BambuWatchNoticeData(
                    printerName = "Printer 1",
                    jobName = "Bracket v3",
                    scopeLabel = "your print",
                    statusPageUrl = "https://example.test/status")))
    assertEquals("On Printer 1, your print is taking longer than expected", overdue.subject)

    val expired =
        registry.render(
            "bambu-watch-expired",
            json.encodeToString(
                BambuWatchNoticeData.serializer(),
                BambuWatchNoticeData(
                    printerName = "Printer 1",
                    jobName = "Bracket v3",
                    scopeLabel = "the print you were waiting on",
                    statusPageUrl = "https://example.test/status")))
    assertEquals("Please check your print on Printer 1", expired.subject)

    val offline =
        registry.render(
            "bambu-printer-offline",
            json.encodeToString(
                BambuPrinterOfflineData.serializer(),
                BambuPrinterOfflineData(
                    printerName = "Printer 4",
                    offlineSinceText = "Thu, Oct 22 at 6:00 PM CT",
                    statusPageUrl = "https://example.test/status")))
    assertEquals("3D printer offline: Printer 4", offline.subject)
  }

  @Test
  fun `click-produced topics are hidden from preferences while the standing topic is visible`() {
    assertFalse(BambuWatchTopic.visibleInPreferences)
    assertFalse(BambuPrintFinishedTopic.visibleInPreferences)
    assertFalse(BambuPrintActionRequiredTopic.visibleInPreferences)
    assertFalse(BambuPrinterAvailableTopic.visibleInPreferences)
    assertTrue(BambuCommitteeAlertsTopic.visibleInPreferences)
    // Finished wording must never claim the bed is still occupied (requirements §5).
    val text =
        BambuPrintFinishedTemplate()
            .renderText(BambuPrintFinishedData(printerName = "Printer 1", jobName = "Bracket v3"))
    assertTrue(text.contains("Please collect your print and clear the bed"))
    assertFalse(text.contains("still occupied"))
  }
}
