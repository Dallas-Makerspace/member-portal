package org.dallasmakerspace.smartwaiver

import java.time.LocalDate
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.DBMasterConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LocalWaiverReadsTest {
  private val repo = SmartWaiverMirrorRepository()
  private val config = mock<ConfigOverrideService>()
  private val inner = InMemoryObjectStore()
  private val reads = LocalWaiverReads(repo, config, inner, LoggerFactory())

  @BeforeTest
  fun setUp() {
    DBMasterConnection.db = SmartWaiverMirrorRepositoryTest.db
    transaction(SmartWaiverMirrorRepositoryTest.db) {
      SmartWaiverPhotosTable.deleteAll()
      SmartWaiverParticipantsTable.deleteAll()
      SmartWaiverMirrorTable.deleteAll()
    }
    flag(false)
  }

  private fun flag(on: Boolean) {
    runBlocking { whenever(config.get(ConfigRegistry.SMARTWAIVER_LOCAL_READS_ENABLED)).thenReturn(on) }
  }

  private fun save(id: String, first: String, last: String, createdOn: String, others: List<Pair<String, String>> = emptyList(), photos: List<MirroredPhoto> = emptyList(), email: String? = "$first@example.invalid") {
    val participants = (listOf(first to last) + others).map { SmartwaiverParticipant(firstName = it.first, lastName = it.second) }
    val waiver = SmartwaiverFullWaiver(waiverId = id, firstName = first, lastName = last, email = email, createdOn = createdOn, kiosk = true, participants = participants)
    runBlocking { repo.saveMirror(waiver, photos, SmartWaiverMirrorRepository.PHOTOS_COMPLETE) }
  }

  @Test
  fun `the flag decides, and source can force either side`() {
    flag(false)
    assertFalse(runBlocking { reads.useLocal() })
    assertTrue(runBlocking { reads.useLocal("local") })
    assertTrue(runBlocking { reads.useLocal("LOCAL") })
    flag(true)
    assertTrue(runBlocking { reads.useLocal() })
    assertFalse(runBlocking { reads.useLocal("api") })
    assertTrue(runBlocking { reads.useLocal("nonsense") })
  }

  @Test
  fun `a date range covers whole Chicago days at both ends (winter, UTC-6)`() {
    save("fakewaiver01", "Ann", "Fake", "2024-03-05 05:59:59")
    save("fakewaiver02", "Bob", "Fake", "2024-03-05 06:00:00")
    save("fakewaiver03", "Cy", "Fake", "2024-03-07 05:59:59")
    save("fakewaiver04", "Di", "Fake", "2024-03-07 06:00:00")

    val ids = runBlocking { reads.signingData(LocalDate.of(2024, 3, 5), LocalDate.of(2024, 3, 6)) }.map { it.waiverId }

    assertEquals(setOf("fakewaiver02", "fakewaiver03"), ids.toSet())
  }

  @Test
  fun `a date range covers whole Chicago days at both ends (summer, UTC-5)`() {
    save("fakewaiver01", "Ann", "Fake", "2024-06-05 04:59:59")
    save("fakewaiver02", "Bob", "Fake", "2024-06-05 05:00:00")
    save("fakewaiver03", "Cy", "Fake", "2024-06-06 04:59:59")
    save("fakewaiver04", "Di", "Fake", "2024-06-06 05:00:00")

    val ids = runBlocking { reads.summaries(LocalDate.of(2024, 6, 5), LocalDate.of(2024, 6, 5)) }.map { it.waiverId }

    assertEquals(setOf("fakewaiver02", "fakewaiver03"), ids.toSet())
  }

  @Test
  fun `signing data carries the weekday for the weekly counts`() {
    save("fakewaiver01", "Ann", "Fake", "2024-03-07 10:00:00") // a Thursday

    val data = runBlocking { reads.signingData(LocalDate.of(2024, 3, 7), LocalDate.of(2024, 3, 7)) }.single()

    assertEquals(4, data.dayOfWeek)
    assertEquals(LocalDate.of(2024, 3, 7), data.date.toLocalDate())
  }

  @Test
  fun `an evening signing counts on its Chicago day, not the UTC day`() {
    save("fakewaiver01", "Ann", "Fake", "2024-03-08 01:30:00") // Friday UTC, Thursday 7:30 pm Chicago

    val data = runBlocking { reads.signingData(LocalDate.of(2024, 3, 7), LocalDate.of(2024, 3, 7)) }.single()

    assertEquals(4, data.dayOfWeek)
    assertEquals(java.time.LocalDateTime.of(2024, 3, 7, 19, 30), data.date)
  }

  @Test
  fun `kiosk photo waivers show the signing time in Chicago time`() {
    save("fakewaiver01", "Pat", "Sample", "2024-03-08 01:30:00")

    val result = runBlocking { reads.kioskPhotos("Pat", "Sample", "x@example.invalid") }

    assertEquals("2024-03-07 19:30:00", result.single().dateCompleted)
  }

  @Test
  fun `summaries carry what the recent-waivers report shows`() {
    save("fakewaiver01", "Ann", "Fake", "2024-03-07 10:00:00", email = "ann@example.invalid")

    val s = runBlocking { reads.summaries(LocalDate.of(2024, 3, 7), LocalDate.of(2024, 3, 7)) }.single()

    assertEquals("ann@example.invalid", s.email)
    assertEquals("Ann", s.firstName)
    assertEquals("2024-03-07 10:00:00", s.createdOn)
  }

  @Test
  fun `rows written by the original minimal path are still found`() {
    transaction(SmartWaiverMirrorRepositoryTest.db) {
      SmartWaiverMirrorTable.insertLegacy("fakewaiver09", "Old", "Row", "2024-03-07 08:00:00")
    }

    val found = runBlocking { reads.signingData(LocalDate.of(2024, 3, 7), LocalDate.of(2024, 3, 7)) }

    assertEquals(listOf("fakewaiver09"), found.map { it.waiverId })
  }

  @Test
  fun `photos are matched by name, including as a later participant, newest first`() {
    val photo = { id: String, key: String -> MirroredPhoto(id, "kiosk", "tag", "jpg", null, key, 4) }
    runBlocking { inner.put(ObjectKey("smartwaiver/fakewaiver01/photos/a.jpg"), "AAAA".toByteArray(), "image/jpeg") }
    runBlocking { inner.put(ObjectKey("smartwaiver/fakewaiver02/photos/b.jpg"), "BBBB".toByteArray(), "image/jpeg") }
    save("fakewaiver01", "Pat", "Sample", "2024-01-01 10:00:00", photos = listOf(photo("a", "fakewaiver01/photos/a.jpg")), email = "pat@example.invalid")
    save("fakewaiver02", "Other", "Parent", "2024-02-01 10:00:00", others = listOf("pat" to "SAMPLE"), photos = listOf(photo("b", "fakewaiver02/photos/b.jpg")))
    save("fakewaiver03", "Someone", "Else", "2024-03-01 10:00:00")

    val result = runBlocking { reads.kioskPhotos("Pat", "Sample", "pat@example.invalid") }

    assertEquals(listOf("fakewaiver02", "fakewaiver01"), result.map { it.waiverId })
    assertEquals(listOf(false, true), result.map { it.emailMatches })
    assertEquals("AAAA", String(Base64.getDecoder().decode(result[1].photos.single().dataBase64!!)))
    assertEquals("jpg", result[1].photos.single().fileType)
  }

  @Test
  fun `a photo missing from storage is left out instead of failing the view`() {
    runBlocking { inner.put(ObjectKey("smartwaiver/fakewaiver01/photos/a.jpg"), "AAAA".toByteArray(), "image/jpeg") }
    save("fakewaiver01", "Pat", "Sample", "2024-01-01 10:00:00",
        photos = listOf(
            MirroredPhoto("a", "kiosk", "t", "jpg", null, "fakewaiver01/photos/a.jpg", 4),
            MirroredPhoto("gone", "kiosk", "t", "jpg", null, "fakewaiver01/photos/gone.jpg", 4)))

    val result = runBlocking { reads.kioskPhotos("Pat", "Sample", "x@example.invalid") }

    assertEquals(listOf("a"), result.single().photos.map { it.photoId })
  }

  @Test
  fun `a member with no waivers gets an empty list`() {
    assertEquals(emptyList(), runBlocking { reads.kioskPhotos("No", "Body", "n@example.invalid") })
  }

  private fun SmartWaiverMirrorTable.insertLegacy(id: String, first: String, last: String, completed: String) {
    insert<SmartWaiverMirrorTable> {
      it[firstName] = first
      it[lastName] = last
      it[birthDate] = "x"
      it[dateCompleted] = completed
      it[waiverId] = id
      it[waiverStatus] = "Completed At Kiosk"
      it[checkinCount] = 1
      it[requestedWaiverEmail] = 1
    }
  }
}
