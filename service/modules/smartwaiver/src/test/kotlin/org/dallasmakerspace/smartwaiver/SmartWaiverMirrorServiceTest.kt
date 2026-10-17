package org.dallasmakerspace.smartwaiver

import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.DBMasterConnection
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.storage.IObjectStore
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.StoredObject
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SmartWaiverMirrorServiceTest {
  private val client = mock<ISmartwaiverApiClient>()
  private val inner = InMemoryObjectStore()
  private val puts = mutableListOf<String>()
  private val store: IObjectStore =
      object : IObjectStore by inner {
        override suspend fun put(
            key: ObjectKey,
            bytes: ByteArray,
            contentType: String,
            metadata: Map<String, String>,
        ): StoredObject {
          puts += key.value
          return inner.put(key, bytes, contentType, metadata)
        }
      }
  private val photoBytes = "fake-jpeg-bytes".toByteArray()
  private val b64 = Base64.getEncoder().encodeToString(photoBytes)

  @BeforeTest
  fun setUp() {
    DBMasterConnection.db = SmartWaiverMirrorRepositoryTest.db
    transaction(SmartWaiverMirrorRepositoryTest.db) {
      SmartWaiverPhotosTable.deleteAll()
      SmartWaiverParticipantsTable.deleteAll()
      SmartWaiverMirrorTable.deleteAll()
    }
  }

  private fun service(repo: SmartWaiverMirrorRepository = SmartWaiverMirrorRepository()) =
      SmartWaiverMirrorService(client, repo, store, LoggerFactory())

  private fun waiver(photos: Int? = 2) =
      SmartwaiverFullWaiver(
          waiverId = "fakewaiver000001",
          firstName = "Fake",
          lastName = "User",
          createdOn = "2024-03-05 18:30:00",
          kiosk = true,
          photos = photos,
          participants = listOf(SmartwaiverParticipant(firstName = "Fake", lastName = "User")))

  private fun entry(id: String?, data: String? = b64, fileType: String? = "jpg") =
      SmartwaiverPhotoEntry(type = "kiosk", date = "2024-03-05 18:30:01", tag = "t", fileType = fileType, photoId = id, photo = data)

  private fun stubApi(photos: List<SmartwaiverPhotoEntry>, waiver: SmartwaiverFullWaiver = waiver()) {
    runBlocking {
      whenever(client.getWaiver("fakewaiver000001")).thenReturn(waiver)
      whenever(client.getWaiverPhotos("fakewaiver000001")).thenReturn(photos)
    }
  }

  private fun rows() = transaction(SmartWaiverMirrorRepositoryTest.db) { SmartWaiverMirrorTable.selectAll().toList() }

  @Test
  fun `stores photos under the waiver and records them with the waiver`() {
    stubApi(listOf(entry("p1"), entry("p2")))

    val outcome = runBlocking { service().mirrorWaiver("fakewaiver000001") }

    assertEquals(MirrorOutcome("fakewaiver000001", 2, "COMPLETE"), outcome)
    assertEquals(listOf("smartwaiver/fakewaiver000001/photos/p1.jpg", "smartwaiver/fakewaiver000001/photos/p2.jpg"), puts)
    val stored = assertNotNull(runBlocking { inner.get(ObjectKey("smartwaiver/fakewaiver000001/photos/p1.jpg")) })
    assertEquals(photoBytes.toList(), stored.bytes.toList())
    assertEquals(listOf("fakewaiver000001/photos/p1.jpg", "fakewaiver000001/photos/p2.jpg"),
        runBlocking { SmartWaiverMirrorRepository().photosFor("fakewaiver000001") }.map { it.s3Key })
    assertEquals("COMPLETE", rows().single()[SmartWaiverMirrorTable.photosStatus])
  }

  @Test
  fun `mirroring the same waiver again uploads nothing new`() {
    stubApi(listOf(entry("p1"), entry("p2")))
    runBlocking { service().mirrorWaiver("fakewaiver000001") }
    puts.clear()

    runBlocking { service().mirrorWaiver("fakewaiver000001") }

    assertEquals(emptyList(), puts)
    assertEquals(1, rows().size)
  }

  @Test
  fun `a waiver that reports no photos is not asked for them`() {
    stubApi(emptyList(), waiver(photos = 0))

    val outcome = runBlocking { service().mirrorWaiver("fakewaiver000001") }

    assertEquals("NONE", outcome.photosStatus)
    runBlocking { verify(client, never()).getWaiverPhotos(any()) }
  }

  @Test
  fun `unusable photo entries are skipped and the waiver is marked partial`() {
    stubApi(listOf(entry("good"), entry("../escape"), entry("nodata", data = null), entry("bad64", data = "!!!not base64!!!")))

    val outcome = runBlocking { service().mirrorWaiver("fakewaiver000001") }

    assertEquals(1, outcome.photos)
    assertEquals("PARTIAL", outcome.photosStatus)
    assertEquals(listOf("smartwaiver/fakewaiver000001/photos/good.jpg"), puts)
  }

  @Test
  fun `if recording fails, only the objects this call uploaded are removed`() {
    stubApi(listOf(entry("old"), entry("new")))
    runBlocking { inner.put(ObjectKey("smartwaiver/fakewaiver000001/photos/old.jpg"), photoBytes, "image/jpeg") }
    val failing = mock<SmartWaiverMirrorRepository>()
    runBlocking { whenever(failing.saveMirror(any(), any(), any(), any())).doThrow(IllegalStateException("db down")) }

    assertFailsWith<IllegalStateException> { runBlocking { service(failing).mirrorWaiver("fakewaiver000001") } }

    assertNull(runBlocking { inner.head(ObjectKey("smartwaiver/fakewaiver000001/photos/new.jpg")) })
    assertNotNull(runBlocking { inner.head(ObjectKey("smartwaiver/fakewaiver000001/photos/old.jpg")) })
  }
}
