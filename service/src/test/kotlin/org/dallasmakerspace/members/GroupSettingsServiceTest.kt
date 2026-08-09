package org.dallasmakerspace.members

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.LoggerFactory
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class GroupSettingsServiceTest {

  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockLogger: Logger
  private lateinit var mockRepository: IGroupSettingsRepository
  private lateinit var service: GroupSettingsService

  @Before
  fun setUp() {
    mockLoggerFactory = mock()
    mockLogger = mock()
    mockRepository = mock()

    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)

    service = GroupSettingsService(mockRepository, mockLoggerFactory)
  }

  @Test
  fun `getSettings returns only stored registered keys`() = runBlocking {
    whenever(mockRepository.getGroupId("group1")).thenReturn(1)
    whenever(mockRepository.getLatest(1))
        .thenReturn(mapOf("allow_self_add_remove" to "true", "unknown_setting" to "x"))

    val result = service.getSettings("group1")

    assertEquals(mapOf("allow_self_add_remove" to "true"), result)
  }

  @Test
  fun `getSettings returns empty map when nothing stored`() = runBlocking {
    whenever(mockRepository.getGroupId("group1")).thenReturn(1)
    whenever(mockRepository.getLatest(1)).thenReturn(emptyMap())

    val result = service.getSettings("group1")

    assertEquals(emptyMap<String, String>(), result)
  }

  @Test
  fun `getSettings returns empty map when group not found`() = runBlocking {
    whenever(mockRepository.getGroupId("ghost")).thenReturn(null)

    val result = service.getSettings("ghost")

    assertEquals(emptyMap<String, String>(), result)
  }

  @Test
  fun `getSettings never creates a groups row`(): Unit = runBlocking {
    whenever(mockRepository.getGroupId("ghost")).thenReturn(null)

    service.getSettings("ghost")

    verify(mockRepository, never()).getOrCreateGroupId(any(), anyOrNull())
  }

  @Test
  fun `updateSettings appends valid value with actor`() = runBlocking {
    whenever(mockRepository.getOrCreateGroupId(eq("group1"), anyOrNull())).thenReturn(1)
    whenever(mockRepository.getProfileId("admin")).thenReturn(2)

    service.updateSettings("group1", "CN=group1,OU=Groups", mapOf("allow_follow" to "true"), "admin")

    verify(mockRepository).append(eq(1), eq("allow_follow"), eq("true"), eq(2))
  }

  @Test
  fun `updateSettings appends clear when value is blank`() = runBlocking {
    whenever(mockRepository.getOrCreateGroupId(eq("group1"), anyOrNull())).thenReturn(1)
    whenever(mockRepository.getProfileId("admin")).thenReturn(2)

    service.updateSettings("group1", null, mapOf("allow_follow" to ""), "admin")

    verify(mockRepository).append(eq(1), eq("allow_follow"), eq(null), eq(2))
  }

  @Test
  fun `updateSettings appends clear when value is null`() = runBlocking {
    whenever(mockRepository.getOrCreateGroupId(eq("group1"), anyOrNull())).thenReturn(1)
    whenever(mockRepository.getProfileId("admin")).thenReturn(2)

    service.updateSettings("group1", null, mapOf("allow_follow" to null), "admin")

    verify(mockRepository).append(eq(1), eq("allow_follow"), eq(null), eq(2))
  }

  @Test
  fun `updateSettings rejects unknown key`(): Unit = runBlocking {
    whenever(mockRepository.getOrCreateGroupId(eq("group1"), anyOrNull())).thenReturn(1)

    assertFailsWith<IllegalArgumentException> {
      service.updateSettings("group1", null, mapOf("bogus_setting" to "value"), null)
    }
  }

  @Test
  fun `updateSettings rejects invalid value`(): Unit = runBlocking {
    whenever(mockRepository.getOrCreateGroupId(eq("group1"), anyOrNull())).thenReturn(1)

    assertFailsWith<IllegalArgumentException> {
      service.updateSettings("group1", null, mapOf("allow_follow" to "maybe"), null)
    }
  }
}
