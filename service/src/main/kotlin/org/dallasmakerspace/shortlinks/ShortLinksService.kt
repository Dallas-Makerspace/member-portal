package org.dallasmakerspace.shortlinks

import javax.inject.Inject
import kotlin.random.Random
import kotlinx.datetime.Clock
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.*

/**
 * Business logic for short links feature. Handles validation, slug generation, and redirect
 * resolution.
 */
class ShortLinksService
@Inject
constructor(loggerFactory: LoggerFactory, private val repository: ShortLinksRepository) {
  private val log = loggerFactory.create(javaClass)

  // === NAMESPACE MANAGEMENT ===

  suspend fun createNamespace(
      name: String,
      ownerType: NamespaceOwnerType,
      ownerGroupId: Int?,
      description: String?,
      primaryAlias: String,
      additionalAliases: List<String> = emptyList(),
      createdBy: Int?
  ): Result<Namespace> {
    // Validate primary alias
    validateAlias(primaryAlias)?.let {
      return Result.failure(it)
    }

    // Validate additional aliases
    additionalAliases.forEach { alias ->
      validateAlias(alias)?.let {
        return Result.failure(it)
      }
    }

    // Create namespace
    val namespace =
        repository.createNamespace(
            Namespace(
                id = -1,
                name = name,
                ownerType = ownerType,
                ownerGroupId = ownerGroupId,
                description = description,
                isActive = true,
                createdAt = Clock.System.now(),
                createdBy = createdBy))

    // Create primary alias
    repository.createAlias(namespace.id, primaryAlias, isPrimary = true)

    // Create additional aliases
    additionalAliases.forEach { alias ->
      repository.createAlias(namespace.id, alias, isPrimary = false)
    }

    log.info(
        "Created namespace '${namespace.name}' (ID: ${namespace.id}) with primary alias '$primaryAlias'")
    return Result.success(namespace)
  }

  suspend fun getNamespace(id: Int): Namespace? {
    return repository.getNamespaceById(id)
  }

  suspend fun getAllNamespaces(activeOnly: Boolean = true): List<Namespace> {
    val namespaces = repository.getAllNamespaces(activeOnly)
    // Enrich with aliases
    return namespaces.map { namespace ->
      val aliases = repository.getAliasesByNamespace(namespace.id)
      namespace.copy(aliases = aliases)
    }
  }

  suspend fun updateNamespace(id: Int, updates: Namespace): Result<Namespace> {
    val updated =
        repository.updateNamespace(id, updates)
            ?: return Result.failure(Exception("Namespace not found: $id"))
    return Result.success(updated)
  }

  suspend fun deleteNamespace(id: Int): Result<Unit> {
    repository.deleteNamespace(id)
    log.info("Deleted namespace ID: $id")
    return Result.success(Unit)
  }

  suspend fun addAlias(
      namespaceId: Int,
      alias: String,
      isPrimary: Boolean
  ): Result<NamespaceAlias> {
    validateAlias(alias)?.let {
      return Result.failure(it)
    }

    val created = repository.createAlias(namespaceId, alias, isPrimary)
    log.info("Added alias '$alias' to namespace $namespaceId")
    return Result.success(created)
  }

  // === SHORT LINK MANAGEMENT ===

  suspend fun createShortLink(
      namespaceId: Int?,
      slug: String?,
      destinationUrl: String,
      description: String?,
      creatorId: Int?
  ): Result<ShortLink> {
    // Validate destination URL
    validateUrl(destinationUrl)?.let {
      return Result.failure(it)
    }

    // Generate slug if not provided (root-level only)
    val finalSlug =
        if (slug != null) {
          // Custom slug - validate it
          validateSlug(slug)?.let {
            return Result.failure(it)
          }
          slug
        } else {
          // Auto-generate slug (root-level only)
          if (namespaceId != null) {
            return Result.failure(Exception("Custom slug required for namespace links"))
          }
          generateUniqueSlug()
        }

    // Check for duplicate
    val existing = repository.getShortLinkBySlug(namespaceId, finalSlug)
    if (existing != null) {
      log.warn(
          "Short link creation failed: slug '$finalSlug' already exists in namespace $namespaceId (existing link id=${existing.id})")
      return Result.failure(Exception("Slug '$finalSlug' already exists in this namespace"))
    }

    val link =
        repository.createShortLink(
            ShortLink(
                id = -1,
                namespaceId = namespaceId,
                slug = finalSlug,
                redirectType = RedirectType.BASIC,
                destinationUrl = destinationUrl,
                description = description,
                creatorId = creatorId,
                updatedBy = null,
                isActive = true,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now()))

    log.info(
        "Created short link: ${formatShortLinkPath(namespaceId, finalSlug)} -> $destinationUrl")
    return Result.success(link)
  }

  suspend fun getShortLink(id: Int): ShortLink? {
    return repository.getShortLinkById(id)
  }

  suspend fun getAllShortLinks(
      namespaceId: Int? = null,
      activeOnly: Boolean = true
  ): List<ShortLink> {
    return repository.getAllShortLinks(namespaceId, activeOnly)
  }

  suspend fun updateShortLink(id: Int, updates: ShortLink): Result<ShortLink> {
    // Validate slug if changed
    validateSlug(updates.slug)?.let {
      return Result.failure(it)
    }

    // Validate URL if changed
    validateUrl(updates.destinationUrl)?.let {
      return Result.failure(it)
    }

    val updated =
        repository.updateShortLink(id, updates)
            ?: return Result.failure(Exception("Short link not found: $id"))

    log.info("Updated short link ID: $id")
    return Result.success(updated)
  }

  suspend fun deleteShortLink(id: Int): Result<Unit> {
    repository.deleteShortLink(id)
    log.info("Deleted short link ID: $id")
    return Result.success(Unit)
  }

  suspend fun getPopularShortLinks(limit: Int = 10): List<Pair<ShortLink, Long>> {
    return repository.getPopularShortLinks(limit)
  }

  // === REDIRECT RESOLUTION ===

  suspend fun resolveRedirect(path: String, memberId: Int? = null): RedirectResult {
    log.debug("Resolving redirect for path: $path")

    // Parse path: "alias/slug" or "slug"
    val parts = path.split("/", limit = 2)

    val (namespaceId, slug) =
        if (parts.size == 2) {
          // Namespace path: "ws/safety-video"
          val alias = parts[0]
          val resolvedId =
              repository.resolveAlias(alias)
                  ?: return RedirectResult.NotFound("Namespace alias not found: $alias")
          resolvedId to parts[1]
        } else {
          // Root-level path: "t6u9i3"
          null to parts[0]
        }

    // Look up short link
    val link =
        repository.getShortLinkBySlug(namespaceId, slug)
            ?: return RedirectResult.NotFound("Short link not found: $path")

    // Record click (async, don't wait - we'll do this in a try-catch to not block redirect)
    try {
      repository.recordClick(
          ShortLinkClick(
              id = -1,
              shortLinkId = link.id,
              memberId = memberId,
              resolvedPath = path,
              variableValues = null,
              clickedAt = Clock.System.now(),
              ipAddress = null, // Will be set from ApplicationCall in route handler
              userAgent = null, // Will be set from ApplicationCall in route handler
              referrer = null // Will be set from ApplicationCall in route handler
              ))
    } catch (e: Exception) {
      log.error("Failed to record click for link ${link.id}", e)
      // Don't fail the redirect
    }

    return RedirectResult.Success(link.destinationUrl, link.description)
  }

  // === VALIDATION ===

  private suspend fun validateAlias(alias: String): Exception? {
    // Length check
    if (alias.length < 2 || alias.length > 20) {
      log.warn("Alias validation failed for '$alias': must be 2-20 characters (was ${alias.length})")
      return Exception("Alias must be 2-20 characters")
    }

    // Format check: lowercase letters, numbers, hyphens
    if (!alias.matches(Regex("^[a-z0-9-]+$"))) {
      val invalidChars = alias.filterNot { it in 'a'..'z' || it in '0'..'9' || it == '-' }.toSet()
      log.warn(
          "Alias validation failed for '$alias': contains characters other than lowercase letters, " +
              "numbers, and hyphens (invalid chars: $invalidChars)")
      return Exception("Alias must contain only lowercase letters, numbers, and hyphens")
    }

    // Cannot start or end with hyphen
    if (alias.startsWith("-") || alias.endsWith("-")) {
      log.warn("Alias validation failed for '$alias': cannot start or end with hyphen")
      return Exception("Alias cannot start or end with hyphen")
    }

    // Reserved check
    if (repository.isAliasReserved(alias)) {
      log.warn("Alias validation failed for '$alias': alias is reserved")
      return Exception("Alias '$alias' is reserved")
    }

    return null
  }

  private fun validateSlug(slug: String): Exception? {
    // Length check
    if (slug.isEmpty() || slug.length > 50) {
      log.warn("Slug validation failed for '$slug': must be 1-50 characters (was ${slug.length})")
      return Exception("Slug must be 1-50 characters")
    }

    // Format check: lowercase letters, numbers, hyphens
    if (!slug.matches(Regex("^[a-z0-9-]+$"))) {
      val invalidChars = slug.filterNot { it in 'a'..'z' || it in '0'..'9' || it == '-' }.toSet()
      log.warn(
          "Slug validation failed for '$slug': contains characters other than lowercase letters, " +
              "numbers, and hyphens (invalid chars: $invalidChars)")
      return Exception("Slug must contain only lowercase letters, numbers, and hyphens")
    }

    // Cannot start or end with hyphen
    if (slug.startsWith("-") || slug.endsWith("-")) {
      log.warn("Slug validation failed for '$slug': cannot start or end with hyphen")
      return Exception("Slug cannot start or end with hyphen")
    }

    return null
  }

  private fun validateUrl(url: String): Exception? {
    // Length check
    if (url.length > 2048) {
      log.warn("URL validation failed: too long (${url.length} chars, max 2048)")
      return Exception("URL too long (max 2048 characters)")
    }

    // Must be HTTP/HTTPS
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      log.warn("URL validation failed for '$url': must start with http:// or https://")
      return Exception("URL must start with http:// or https://")
    }

    // Basic URL validation
    try {
      java.net.URL(url)
    } catch (e: Exception) {
      log.warn("URL validation failed for '$url': ${e.message}")
      return Exception("Invalid URL format: ${e.message}")
    }

    return null
  }

  // === SLUG GENERATION ===

  private suspend fun generateUniqueSlug(): String {
    val chars = "abcdefghjkmnpqrstuvwxyz23456789" // Avoid ambiguous: 0/O, 1/l/I
    var attempts = 0
    val maxAttempts = 100

    while (attempts < maxAttempts) {
      val length = Random.nextInt(6, 9) // 6-8 characters
      val slug = (1..length).map { chars[Random.nextInt(chars.length)] }.joinToString("")

      // Check if slug exists
      val existing = repository.getShortLinkBySlug(null, slug)
      if (existing == null) {
        return slug
      }

      attempts++
    }

    throw Exception("Failed to generate unique slug after $maxAttempts attempts")
  }

  private fun formatShortLinkPath(namespaceId: Int?, slug: String): String {
    return if (namespaceId != null) {
      "dallas.ms/{namespace}/$slug"
    } else {
      "dallas.ms/$slug"
    }
  }
}

sealed class RedirectResult {
  data class Success(val destinationUrl: String, val description: String?) : RedirectResult()

  data class NotFound(val message: String) : RedirectResult()
}
