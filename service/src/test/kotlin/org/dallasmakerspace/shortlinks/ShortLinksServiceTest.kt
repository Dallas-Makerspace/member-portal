package org.dallasmakerspace.shortlinks

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.Logger

class ShortLinksServiceTest {

  private lateinit var mockLoggerFactory: LoggerFactory
  private lateinit var mockLogger: Logger
  private lateinit var mockRepository: ShortLinksRepository
  private lateinit var service: ShortLinksService

  @Before
  fun setUp() {
    mockLoggerFactory = mock()
    mockLogger = mock()
    mockRepository = mock()

    whenever(mockLoggerFactory.create(any<Class<*>>())).thenReturn(mockLogger)

    service = ShortLinksService(mockLoggerFactory, mockRepository)
  }

  // === NAMESPACE TESTS ===

  @Test
  fun `createNamespace should fail with invalid alias length - too short`() = runBlocking {
    val result =
        service.createNamespace(
            name = "Test Namespace",
            ownerType = NamespaceOwnerType.COMMITTEE,
            ownerGroupId = null,
            description = null,
            primaryAlias = "a", // Too short - must be 2-20 chars
            additionalAliases = emptyList(),
            createdBy = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("2-20") == true)
  }

  @Test
  fun `createNamespace should fail with invalid alias length - too long`() = runBlocking {
    val result =
        service.createNamespace(
            name = "Test Namespace",
            ownerType = NamespaceOwnerType.COMMITTEE,
            ownerGroupId = null,
            description = null,
            primaryAlias = "abcdefghijklmnopqrstu", // Too long - must be 2-20 chars
            additionalAliases = emptyList(),
            createdBy = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("2-20") == true)
  }

  @Test
  fun `createNamespace should fail with invalid alias characters`() = runBlocking {
    val result =
        service.createNamespace(
            name = "Test Namespace",
            ownerType = NamespaceOwnerType.COMMITTEE,
            ownerGroupId = null,
            description = null,
            primaryAlias = "ABC123", // Must be lowercase letters only
            additionalAliases = emptyList(),
            createdBy = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("lowercase") == true)
  }

  @Test
  fun `createNamespace should fail if alias is reserved`() = runBlocking {
    whenever(mockRepository.isAliasReserved("admin")).thenReturn(true)

    val result =
        service.createNamespace(
            name = "Test Namespace",
            ownerType = NamespaceOwnerType.COMMITTEE,
            ownerGroupId = null,
            description = null,
            primaryAlias = "admin",
            additionalAliases = emptyList(),
            createdBy = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("reserved") == true)
  }

  @Test
  fun `createNamespace should succeed with valid alias`() = runBlocking {
    val mockNamespace =
        Namespace(
            id = 1,
            name = "Woodshop",
            ownerType = NamespaceOwnerType.COMMITTEE,
            ownerGroupId = null,
            description = null,
            isActive = true,
            createdAt = Clock.System.now(),
            createdBy = 1)

    whenever(mockRepository.isAliasReserved(any())).thenReturn(false)
    whenever(mockRepository.createNamespace(any())).thenReturn(mockNamespace)
    whenever(mockRepository.createAlias(any(), any(), any()))
        .thenReturn(
            NamespaceAlias(
                id = 1,
                namespaceId = 1,
                alias = "ws",
                isPrimary = true,
                createdAt = Clock.System.now()))

    val result =
        service.createNamespace(
            name = "Woodshop",
            ownerType = NamespaceOwnerType.COMMITTEE,
            ownerGroupId = null,
            description = null,
            primaryAlias = "ws",
            additionalAliases = emptyList(),
            createdBy = 1)

    assertTrue(result.isSuccess)
    assertEquals("Woodshop", result.getOrNull()?.name)
    verify(mockRepository).createNamespace(any())
    verify(mockRepository).createAlias(1, "ws", true)
    Unit
  }

  @Test
  fun `createNamespace should succeed with hyphenated alias`() = runBlocking {
    val mockNamespace =
        Namespace(
            id = 1,
            name = "Software",
            ownerType = NamespaceOwnerType.SYSTEM,
            ownerGroupId = null,
            description = null,
            isActive = true,
            createdAt = Clock.System.now(),
            createdBy = 1)

    whenever(mockRepository.isAliasReserved(any())).thenReturn(false)
    whenever(mockRepository.createNamespace(any())).thenReturn(mockNamespace)
    whenever(mockRepository.createAlias(any(), any(), any()))
        .thenReturn(
            NamespaceAlias(
                id = 1,
                namespaceId = 1,
                alias = "sw",
                isPrimary = true,
                createdAt = Clock.System.now()))

    val result =
        service.createNamespace(
            name = "Software",
            ownerType = NamespaceOwnerType.SYSTEM,
            ownerGroupId = null,
            description = null,
            primaryAlias = "sw",
            additionalAliases = listOf("software", "software-sig"),
            createdBy = 1)

    assertTrue(result.isSuccess)
    verify(mockRepository).createAlias(1, "software", false)
    verify(mockRepository).createAlias(1, "software-sig", false)
    Unit
  }

  @Test
  fun `createNamespace should fail when alias starts or ends with hyphen`() = runBlocking {
    val result =
        service.createNamespace(
            name = "Test Namespace",
            ownerType = NamespaceOwnerType.COMMITTEE,
            ownerGroupId = null,
            description = null,
            primaryAlias = "-sw",
            additionalAliases = emptyList(),
            createdBy = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("hyphen") == true)
  }

  // === SHORT LINK TESTS ===

  @Test
  fun `createShortLink should fail with invalid URL - missing protocol`() = runBlocking {
    val result =
        service.createShortLink(
            namespaceId = null,
            slug = "test",
            destinationUrl = "www.example.com", // Missing http/https
            description = null,
            creatorId = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("http") == true)
  }

  @Test
  fun `createShortLink should fail with URL too long`() = runBlocking {
    val longUrl = "https://example.com/" + "a".repeat(2050)

    val result =
        service.createShortLink(
            namespaceId = null,
            slug = "test",
            destinationUrl = longUrl,
            description = null,
            creatorId = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("too long") == true)
  }

  @Test
  fun `createShortLink should fail with invalid slug format`() = runBlocking {
    val result =
        service.createShortLink(
            namespaceId = 1,
            slug = "Test_Slug", // Invalid - contains uppercase and underscore
            destinationUrl = "https://example.com",
            description = null,
            creatorId = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("lowercase") == true)
  }

  @Test
  fun `createShortLink should fail with slug starting with hyphen`() = runBlocking {
    val result =
        service.createShortLink(
            namespaceId = 1,
            slug = "-test",
            destinationUrl = "https://example.com",
            description = null,
            creatorId = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("hyphen") == true)
  }

  @Test
  fun `createShortLink should fail with slug ending with hyphen`() = runBlocking {
    val result =
        service.createShortLink(
            namespaceId = 1,
            slug = "test-",
            destinationUrl = "https://example.com",
            description = null,
            creatorId = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("hyphen") == true)
  }

  @Test
  fun `createShortLink should fail when namespace link has no slug`() = runBlocking {
    val result =
        service.createShortLink(
            namespaceId = 1, // Namespace provided
            slug = null, // No slug - required for namespace links
            destinationUrl = "https://example.com",
            description = null,
            creatorId = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("required") == true)
  }

  @Test
  fun `createShortLink should fail when slug already exists`() = runBlocking {
    val existingLink =
        ShortLink(
            id = 1,
            namespaceId = null,
            slug = "existing",
            redirectType = RedirectType.BASIC,
            destinationUrl = "https://existing.com",
            description = null,
            creatorId = 1,
            updatedBy = null,
            isActive = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now())

    whenever(mockRepository.getShortLinkBySlug(null, "existing")).thenReturn(existingLink)

    val result =
        service.createShortLink(
            namespaceId = null,
            slug = "existing",
            destinationUrl = "https://example.com",
            description = null,
            creatorId = 1)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull()?.message?.contains("already exists") == true)
  }

  @Test
  fun `createShortLink should succeed with valid slug and URL`() = runBlocking {
    val mockLink =
        ShortLink(
            id = 1,
            namespaceId = null,
            slug = "test-link",
            redirectType = RedirectType.BASIC,
            destinationUrl = "https://example.com",
            description = "Test link",
            creatorId = 1,
            updatedBy = null,
            isActive = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now())

    whenever(mockRepository.getShortLinkBySlug(null, "test-link")).thenReturn(null)
    whenever(mockRepository.createShortLink(any())).thenReturn(mockLink)

    val result =
        service.createShortLink(
            namespaceId = null,
            slug = "test-link",
            destinationUrl = "https://example.com",
            description = "Test link",
            creatorId = 1)

    assertTrue(result.isSuccess)
    assertEquals("test-link", result.getOrNull()?.slug)
    assertEquals("https://example.com", result.getOrNull()?.destinationUrl)
  }

  // === REDIRECT RESOLUTION TESTS ===

  @Test
  fun `resolveRedirect should return NotFound for unknown alias`() = runBlocking {
    whenever(mockRepository.resolveAlias("unknown")).thenReturn(null)

    val result = service.resolveRedirect("unknown/test")

    assertTrue(result is RedirectResult.NotFound)
    assertTrue((result as RedirectResult.NotFound).message.contains("alias not found"))
  }

  @Test
  fun `resolveRedirect should return NotFound for unknown slug`() = runBlocking {
    whenever(mockRepository.getShortLinkBySlug(null, "unknown")).thenReturn(null)

    val result = service.resolveRedirect("unknown")

    assertTrue(result is RedirectResult.NotFound)
    assertTrue((result as RedirectResult.NotFound).message.contains("not found"))
  }

  @Test
  fun `resolveRedirect should return Success for valid root-level slug`() = runBlocking {
    val mockLink =
        ShortLink(
            id = 1,
            namespaceId = null,
            slug = "example",
            redirectType = RedirectType.BASIC,
            destinationUrl = "https://example.com",
            description = null,
            creatorId = 1,
            updatedBy = null,
            isActive = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now())

    whenever(mockRepository.getShortLinkBySlug(null, "example")).thenReturn(mockLink)

    val result = service.resolveRedirect("example")

    assertTrue(result is RedirectResult.Success)
    assertEquals("https://example.com", (result as RedirectResult.Success).destinationUrl)
  }

  @Test
  fun `resolveRedirect should return Success for valid namespace path`() = runBlocking {
    val mockLink =
        ShortLink(
            id = 1,
            namespaceId = 5,
            slug = "safety",
            redirectType = RedirectType.BASIC,
            destinationUrl = "https://example.com/safety-video",
            description = null,
            creatorId = 1,
            updatedBy = null,
            isActive = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now())

    whenever(mockRepository.resolveAlias("ws")).thenReturn(5)
    whenever(mockRepository.getShortLinkBySlug(5, "safety")).thenReturn(mockLink)

    val result = service.resolveRedirect("ws/safety")

    assertTrue(result is RedirectResult.Success)
    assertEquals(
        "https://example.com/safety-video", (result as RedirectResult.Success).destinationUrl)
    verify(mockRepository).resolveAlias("ws")
    verify(mockRepository).getShortLinkBySlug(5, "safety")
    Unit
  }

  @Test
  fun `resolveRedirect should include description in Success result`() = runBlocking {
    val mockLink =
        ShortLink(
            id = 1,
            namespaceId = null,
            slug = "example",
            redirectType = RedirectType.BASIC,
            destinationUrl = "https://example.com",
            description = "An example link",
            creatorId = 1,
            updatedBy = null,
            isActive = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now())

    whenever(mockRepository.getShortLinkBySlug(null, "example")).thenReturn(mockLink)

    val result = service.resolveRedirect("example")

    assertTrue(result is RedirectResult.Success)
    assertEquals("An example link", (result as RedirectResult.Success).description)
  }

  @Test
  fun `resolveRedirect should record click on success`() = runBlocking {
    val mockLink =
        ShortLink(
            id = 1,
            namespaceId = null,
            slug = "tracked",
            redirectType = RedirectType.BASIC,
            destinationUrl = "https://example.com",
            description = null,
            creatorId = 1,
            updatedBy = null,
            isActive = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now())

    whenever(mockRepository.getShortLinkBySlug(null, "tracked")).thenReturn(mockLink)

    val result = service.resolveRedirect("tracked", memberId = 42)

    assertTrue(result is RedirectResult.Success)
    verify(mockRepository).recordClick(any())
    Unit
  }

  @Test
  fun `resolveRedirect should not record click when recordClick is false`() = runBlocking {
    val mockLink =
        ShortLink(
            id = 1,
            namespaceId = null,
            slug = "previewed",
            redirectType = RedirectType.BASIC,
            destinationUrl = "https://example.com",
            description = null,
            creatorId = 1,
            updatedBy = null,
            isActive = true,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now())

    whenever(mockRepository.getShortLinkBySlug(null, "previewed")).thenReturn(mockLink)

    val result = service.resolveRedirect("previewed", recordClick = false)

    assertTrue(result is RedirectResult.Success)
    verify(mockRepository, org.mockito.kotlin.never()).recordClick(any())
    Unit
  }
}
