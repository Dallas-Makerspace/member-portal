package org.dallasmakerspace.smartwaiver

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.dallasmakerspace.core.DBMasterConnection
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class SmartWaiverMirrorRepositoryTest {
  private val repo = SmartWaiverMirrorRepository()
  private val json = Json { ignoreUnknownKeys = true }

  @BeforeTest
  fun setUp() {
    DBMasterConnection.db = db
    transaction(db) { tables.forEach { it.deleteAll() } }
  }

  private val apiWaiver =
      """
      {"waiverId":"fakewaiver000001","templateId":"tmpl-1","title":"Test Waiver","createdOn":"2024-03-05 18:30:00",
       "expirationDate":"","verified":true,"kiosk":true,"isMinor":false,"firstName":"Fakefirst","lastName":"Fakelast",
       "dob":"1990-01-01","email":"fake@example.invalid","tags":["a","b"],"flags":[],
       "participants":[{"firstName":"Fakefirst","lastName":"Fakelast","dob":"1990-01-01","isMinor":false,
                        "minorExpired":false,"phone":"555-0100","gender":"GENDER-SECRET"}],
       "guardian":null,"addressLineOne":"1 Fake St","addressCity":"Faketown","addressState":"TX","addressZip":"00000",
       "addressCountry":"US","emergencyContactName":"Fake Contact","emergencyContactRelation":"Friend",
       "emergencyContactPhone":"555-0101","photos":2,
       "driversLicenseNumber":"DL-SECRET-123","driversLicenseState":"TX","insuranceCarrier":"INS-SECRET-CO",
       "insurancePolicyNumber":"POLICY-SECRET-9","clientIP":"203.0.113.77","marketingAllowed":true,
       "typedSignatures":{"x":"SIG-SECRET"},"pdf":"PDF-SECRET",
       "customWaiverFields":{"k1":{"value":"Online","displayText":"Where did you hear about us?"},
                             "k2":{"value":"A friend","displayText":"Did anyone refer you?  If so, who?"}}}
      """
          .trimIndent()

  private val noCustomFields =
      apiWaiver.replace(
          Regex("\"customWaiverFields\":\\{.*\\}\\}\\s*$", RegexOption.DOT_MATCHES_ALL),
          "\"customWaiverFields\":{}}")

  private fun waiver(raw: String = apiWaiver) = json.decodeFromString<SmartwaiverFullWaiver>(raw)

  private fun photo(id: String) =
      MirroredPhoto(
          id, "kiosk", "tag", "jpg", "2024-03-05 18:30:01", "smartwaiver/fakewaiver000001/photos/$id.jpg", 8000)

  private fun rows() = transaction(db) { SmartWaiverMirrorTable.selectAll().toList() }

  private fun count(table: Table) = transaction(db) { table.selectAll().count() }.toInt()

  @Test
  fun `saves the waiver with typed columns, referral answers by label, and child rows`() {
    runBlocking {
      repo.saveMirror(waiver(), listOf(photo("p1"), photo("p2")), SmartWaiverMirrorRepository.PHOTOS_COMPLETE)
    }

    val row = rows().single()
    assertEquals("fakewaiver000001", row[SmartWaiverMirrorTable.waiverId])
    assertEquals("Online", row[SmartWaiverMirrorTable.referralType])
    assertEquals("A friend", row[SmartWaiverMirrorTable.referralSource])
    assertEquals("Completed At Kiosk", row[SmartWaiverMirrorTable.waiverStatus])
    assertEquals(2024, row[SmartWaiverMirrorTable.createdOnUtc]?.year)
    assertEquals("""["a","b"]""", row[SmartWaiverMirrorTable.tags])
    assertEquals("555-0100", row[SmartWaiverMirrorTable.phone])
    assertEquals(MIRROR_VERSION, row[SmartWaiverMirrorTable.mirrorVersion])
    assertEquals("COMPLETE", row[SmartWaiverMirrorTable.photosStatus])
    assertEquals(1, count(SmartWaiverParticipantsTable))
    assertEquals(2, runBlocking { repo.photosFor("fakewaiver000001") }.size)
  }

  @Test
  fun `driver license, insurance, ip, gender, signatures and pdf are stored nowhere`() {
    runBlocking { repo.saveMirror(waiver(), listOf(photo("p1")), SmartWaiverMirrorRepository.PHOTOS_COMPLETE) }

    val everything =
        transaction(db) {
          tables.joinToString("\n") { t ->
            t.selectAll().joinToString("\n") { r -> t.columns.joinToString("|") { c -> r[c].toString() } }
          }
        }
    listOf("DL-SECRET", "INS-SECRET", "POLICY-SECRET", "203.0.113.77", "GENDER-SECRET", "SIG-SECRET", "PDF-SECRET")
        .forEach { assertFalse(everything.contains(it), "$it was stored") }
  }

  @Test
  fun `a second save updates in place and keeps legacy-only values`() {
    transaction(db) {
      SmartWaiverMirrorTable.insert {
        it[firstName] = "Old"
        it[lastName] = "Name"
        it[birthDate] = "x"
        it[dateCompleted] = "2024-03-05 18:30:00"
        it[waiverId] = "fakewaiver000001"
        it[waiverStatus] = "Completed At Kiosk"
        it[checkinCount] = 3
        it[requestedWaiverEmail] = 1
        it[referralType] = "Legacy answer"
      }
    }

    runBlocking { repo.saveMirror(waiver(noCustomFields), emptyList(), SmartWaiverMirrorRepository.PHOTOS_NONE) }
    runBlocking { repo.saveMirror(waiver(noCustomFields), emptyList(), SmartWaiverMirrorRepository.PHOTOS_NONE) }

    val row = rows().single()
    assertEquals("Fakefirst", row[SmartWaiverMirrorTable.firstName])
    assertEquals(3, row[SmartWaiverMirrorTable.checkinCount])
    assertEquals("Legacy answer", row[SmartWaiverMirrorTable.referralType])
    assertEquals(1, count(SmartWaiverParticipantsTable))
  }

  @Test
  fun `a guardian is stored by name, relationship and phone only`() {
    val minor =
        apiWaiver.replace(
            "\"guardian\":null",
            "\"guardian\":{\"firstName\":\"Gfirst\",\"lastName\":\"Glast\",\"relationship\":\"Parent\"," +
                "\"phone\":\"555-0102\",\"dob\":\"1970-01-01\",\"guardianAddress\":\"GUARD-ADDR-SECRET\"}")

    runBlocking { repo.saveMirror(waiver(minor), emptyList(), SmartWaiverMirrorRepository.PHOTOS_NONE) }

    val g = transaction(db) { SmartWaiverGuardiansTable.selectAll().single() }
    assertEquals("Parent", g[SmartWaiverGuardiansTable.relationship])
    assertEquals("Gfirst", rows().single()[SmartWaiverMirrorTable.guardianFirstName])
    val stored = SmartWaiverGuardiansTable.columns.joinToString("|") { g[it].toString() }
    assertFalse(stored.contains("GUARD-ADDR-SECRET") || stored.contains("1970"))
  }

  @Test
  fun `over-long values are cut to the column width instead of failing the waiver`() {
    val raw = apiWaiver.replaceFirst("\"firstName\":\"Fakefirst\"", "\"firstName\":\"${"x".repeat(300)}\"")

    runBlocking { repo.saveMirror(waiver(raw), emptyList(), SmartWaiverMirrorRepository.PHOTOS_NONE) }

    assertEquals(100, rows().single()[SmartWaiverMirrorTable.firstName].length)
  }

  @Test
  fun `discover adds only waivers not stored and enrich picks up exactly those`() {
    runBlocking { repo.saveMirror(waiver(), emptyList(), SmartWaiverMirrorRepository.PHOTOS_NONE) }
    val summaries =
        listOf("fakewaiver000001", "fakewaiver000002", "fakewaiver000003").map {
          SmartwaiverSummary(
              waiverId = it,
              templateId = "tmpl-1",
              title = "T",
              createdOn = "2024-03-06 10:00:00",
              kiosk = false,
              firstName = "F",
              lastName = "L")
        }

    assertEquals(2, runBlocking { repo.insertMissingSummaries(summaries) })
    assertEquals(0, runBlocking { repo.insertMissingSummaries(summaries) })

    val needing = runBlocking { repo.waiversNeedingMirror(0, 10) }
    assertEquals(listOf("fakewaiver000002", "fakewaiver000003"), needing.map { it.waiverId })
    assertEquals(2, runBlocking { repo.countNeedingMirror() })
    // Walking by row id: after the first, only the second remains; after the last, nothing.
    assertEquals(listOf("fakewaiver000003"), runBlocking { repo.waiversNeedingMirror(needing[0].rowId, 10) }.map { it.waiverId })
    assertEquals(emptyList(), runBlocking { repo.waiversNeedingMirror(needing[1].rowId, 10) })
    val added = rows().first { it[SmartWaiverMirrorTable.waiverId] == "fakewaiver000002" }
    assertEquals("Completed Online", added[SmartWaiverMirrorTable.waiverStatus])
    assertNull(added[SmartWaiverMirrorTable.mirrorVersion])
  }

  @Test
  fun `check-ins are stored once even when a page overlaps the last one`() {
    fun checkin(id: Long) =
        SmartwaiverCheckin(id, "fakewaiver000001", "2024-03-0$id 12:00:00", "2024-03-01 09:00:00", 0, "tmpl-1", 0)

    assertEquals(3, runBlocking { repo.insertNewCheckins(listOf(checkin(1), checkin(2), checkin(3))) })
    assertEquals(1, runBlocking { repo.insertNewCheckins(listOf(checkin(3), checkin(4))) })

    assertEquals(4, count(SmartWaiverCheckinsTable))
  }

  @Test
  fun `backfill state round trips and updates in place`() {
    val s = BackfillState("enrich", "RUNNING", "2024-03", 5, 100, 12, null, null, null, null, null)

    runBlocking { repo.saveState(s) }
    runBlocking { repo.saveState(s.copy(status = "WAITING", doneCount = 9)) }

    val back = assertNotNull(runBlocking { repo.getState("enrich" ) })
    assertEquals("WAITING", back.status)
    assertEquals(9, back.doneCount)
    assertNull(runBlocking { repo.getState("missing") })
  }

  @Test
  fun `a save that lacks a field keeps the value already stored`() {
    runBlocking { repo.saveMirror(waiver(), emptyList(), SmartWaiverMirrorRepository.PHOTOS_NONE) }
    val sparse = SmartwaiverFullWaiver(waiverId = "fakewaiver000001", firstName = "Fakefirst", lastName = "Fakelast")

    runBlocking { repo.saveMirror(sparse, emptyList(), SmartWaiverMirrorRepository.PHOTOS_NONE) }

    val row = rows().single()
    assertEquals("fake@example.invalid", row[SmartWaiverMirrorTable.email])
    assertEquals("1990-01-01", row[SmartWaiverMirrorTable.birthDate])
    assertEquals("2024-03-05 18:30:00", row[SmartWaiverMirrorTable.dateCompleted])
    assertEquals("Test Waiver", row[SmartWaiverMirrorTable.documentTitle])
    assertEquals("1 Fake St", row[SmartWaiverMirrorTable.addressLineOne])
    assertEquals(2024, row[SmartWaiverMirrorTable.createdOnUtc]?.year)
  }

  @Test
  fun `a photo type longer than its column is cut instead of failing the waiver`() {
    val photo = MirroredPhoto("p1", "x".repeat(80), "tag", "jpg", null, "fakewaiver000001/photos/p1.jpg", 4)

    runBlocking { repo.saveMirror(waiver(), listOf(photo), SmartWaiverMirrorRepository.PHOTOS_COMPLETE) }

    val stored = transaction(db) { SmartWaiverPhotosTable.selectAll().single() }
    assertEquals(30, stored[SmartWaiverPhotosTable.photoType]?.length)
  }

  @Test
  fun `a waiver SmartWaiver no longer has is marked gone and stops counting as work to do`() {
    runBlocking {
      repo.insertMissingSummaries(
          listOf(SmartwaiverSummary(waiverId = "fakewaiver000009", templateId = "t", title = "T", createdOn = "2024-03-06 10:00:00")))
    }
    assertEquals(1, runBlocking { repo.countNeedingMirror() })

    runBlocking { repo.markGone("fakewaiver000009") }

    assertEquals(0, runBlocking { repo.countNeedingMirror() })
    assertEquals(1, runBlocking { repo.countGone() })
    assertEquals("GONE", rows().single()[SmartWaiverMirrorTable.photosStatus])
  }

  @Test
  fun `the run row can be claimed, renewed only by its owner, and released`() {
    val run = BackfillState("run", "RUNNING", null, 0, null, 5, null, null, null, null, null)
    runBlocking { repo.claimRun("copy-a", run) }

    assertEquals(true, runBlocking { repo.renewRun("copy-a", run.copy(requestsToday = 9)) })
    assertEquals(false, runBlocking { repo.renewRun("copy-b", run.copy(requestsToday = 99)) })
    assertEquals(9, runBlocking { repo.getState("run") }?.requestsToday)

    runBlocking { repo.markRunIdle() }

    assertEquals("IDLE", runBlocking { repo.getState("run") }?.status)
    assertEquals(false, runBlocking { repo.renewRun("copy-a", run) })
  }

  @Test
  fun `a unit checkpoint writes the stage only while the copy still owns the run`() {
    val run = BackfillState("run", "RUNNING", null, 0, null, 0, null, null, null, null, null)
    val stage = BackfillState("enrich", "RUNNING", "42", 3, 10, 0, null, null, null, null, null)
    runBlocking { repo.claimRun("copy-a", run) }

    assertEquals(true, runBlocking { repo.saveUnit(stage, "copy-a", run) })
    assertEquals("42", runBlocking { repo.getState("enrich") }?.cursorValue)

    assertEquals(false, runBlocking { repo.saveUnit(stage.copy(cursorValue = "99"), "copy-b", run) })
    assertEquals("42", runBlocking { repo.getState("enrich") }?.cursorValue, "a replaced copy must write nothing")
  }

  companion object {
    private val tables: List<Table> =
        listOf(
            SmartWaiverMirrorTable,
            SmartWaiverParticipantsTable,
            SmartWaiverGuardiansTable,
            SmartWaiverPhotosTable,
            SmartWaiverCheckinsTable,
            SmartWaiverBackfillStateTable,
        )

    val db: Database by lazy {
      val database =
          Database.connect(
              "jdbc:h2:mem:smartwaiver-mirror;DB_CLOSE_DELAY=-1;MODE=MariaDB;DATABASE_TO_LOWER=TRUE",
              driver = "org.h2.Driver")
      transaction(database) {
        // Exposed quotes the table's backticked schema as one H2 identifier, backticks included.
        exec("CREATE SCHEMA IF NOT EXISTS \"`dms-makermanager`\"")
        SchemaUtils.create(*tables.toTypedArray())
      }
      database
    }
  }
}
