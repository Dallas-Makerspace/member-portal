package org.dallasmakerspace.shortlinks

import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.models.*
import org.dallasmakerspace.shortlinks.db.*
import org.jetbrains.exposed.sql.*

/**
 * Repository for managing short links, namespaces, and click tracking. Provides data access layer
 * for all short link operations.
 */
class ShortLinksRepository @Inject constructor(loggerFactory: LoggerFactory) {
  private val log = loggerFactory.create(javaClass)

  // === NAMESPACE OPERATIONS ===

  suspend fun createNamespace(namespace: Namespace): Namespace = suspendTransaction {
    val dao =
        NamespaceDAO.new {
          name = namespace.name
          ownerType = namespace.ownerType.name.lowercase()
          ownerGroupId = namespace.ownerGroupId
          description = namespace.description
          isActive = namespace.isActive
          createdBy = namespace.createdBy
        }
    daoToNamespaceModel(dao)
  }

  suspend fun getNamespaceById(id: Int): Namespace? = suspendTransaction {
    NamespaceDAO.findById(id)?.let { daoToNamespaceModel(it) }
  }

  suspend fun getAllNamespaces(activeOnly: Boolean = true): List<Namespace> = suspendTransaction {
    val query =
        if (activeOnly) {
          NamespaceDAO.find { NamespaceTable.isActive eq true }
        } else {
          NamespaceDAO.all()
        }
    query.map { daoToNamespaceModel(it) }
  }

  suspend fun updateNamespace(id: Int, updates: Namespace): Namespace? = suspendTransaction {
    NamespaceDAO.findById(id)
        ?.apply {
          name = updates.name
          description = updates.description
          isActive = updates.isActive
        }
        ?.let { daoToNamespaceModel(it) }
  }

  suspend fun deleteNamespace(id: Int): Boolean = suspendTransaction {
    NamespaceDAO.findById(id)?.delete()
    true
  }

  // === NAMESPACE ALIAS OPERATIONS ===

  suspend fun createAlias(namespaceId: Int, alias: String, isPrimary: Boolean): NamespaceAlias =
      suspendTransaction {
        val dao =
            NamespaceAliasDAO.new {
              this.namespaceId = NamespaceDAO[namespaceId].id
              this.alias = alias
              this.isPrimary = isPrimary
            }
        NamespaceAlias(
            id = dao.id.value,
            namespaceId = dao.namespaceId.value,
            alias = dao.alias,
            isPrimary = dao.isPrimary,
            createdAt = toKotlinInstant(dao.createdAt))
      }

  suspend fun getAliasesByNamespace(namespaceId: Int): List<NamespaceAlias> = suspendTransaction {
    NamespaceAliasDAO.find { NamespaceAliasTable.namespaceId eq namespaceId }
        .map { dao ->
          NamespaceAlias(
              id = dao.id.value,
              namespaceId = dao.namespaceId.value,
              alias = dao.alias,
              isPrimary = dao.isPrimary,
              createdAt = toKotlinInstant(dao.createdAt))
        }
  }

  suspend fun resolveAlias(alias: String): Int? = suspendTransaction {
    NamespaceAliasDAO.find { NamespaceAliasTable.alias eq alias }.firstOrNull()?.namespaceId?.value
  }

  suspend fun deleteAlias(aliasId: Int): Boolean = suspendTransaction {
    NamespaceAliasDAO.findById(aliasId)?.delete()
    true
  }

  // === SHORT LINK OPERATIONS ===

  suspend fun createShortLink(link: ShortLink): ShortLink = suspendTransaction {
    val dao =
        ShortLinkDAO.new {
          namespaceId = link.namespaceId?.let { NamespaceDAO[it].id }
          slug = link.slug
          redirectType = link.redirectType.name.lowercase()
          destinationUrl = link.destinationUrl
          description = link.description
          creatorId = link.creatorId
          isActive = link.isActive
        }
    daoToShortLinkModel(dao)
  }

  suspend fun getShortLinkById(id: Int): ShortLink? = suspendTransaction {
    ShortLinkDAO.findById(id)?.let { daoToShortLinkModel(it) }
  }

  suspend fun getShortLinkBySlug(namespaceId: Int?, slug: String): ShortLink? = suspendTransaction {
    ShortLinkDAO.find {
          (ShortLinkTable.namespaceId eq namespaceId) and
              (ShortLinkTable.slug eq slug) and
              (ShortLinkTable.isActive eq true)
        }
        .firstOrNull()
        ?.let { daoToShortLinkModel(it) }
  }

  suspend fun getAllShortLinks(
      namespaceId: Int? = null,
      activeOnly: Boolean = true
  ): List<ShortLink> = suspendTransaction {
    // Calculate click counts for all links
    val clickCounts = mutableMapOf<Int, Long>()
    ShortLinkClickTable.selectAll().forEach { row ->
      val linkId = row[ShortLinkClickTable.shortLinkId].value
      clickCounts[linkId] = (clickCounts[linkId] ?: 0L) + 1L
    }

    val query =
        when {
          namespaceId != null && activeOnly -> {
            ShortLinkDAO.find {
              (ShortLinkTable.namespaceId eq namespaceId) and (ShortLinkTable.isActive eq true)
            }
          }
          namespaceId != null -> {
            ShortLinkDAO.find { ShortLinkTable.namespaceId eq namespaceId }
          }
          activeOnly -> {
            ShortLinkDAO.find { ShortLinkTable.isActive eq true }
          }
          else -> ShortLinkDAO.all()
        }

    query.map { dao ->
      val clicks = clickCounts[dao.id.value] ?: 0L
      daoToShortLinkModel(dao, clicks)
    }
  }

  suspend fun updateShortLink(id: Int, updates: ShortLink): ShortLink? = suspendTransaction {
    ShortLinkDAO.findById(id)
        ?.apply {
          slug = updates.slug
          destinationUrl = updates.destinationUrl
          description = updates.description
          isActive = updates.isActive
          updatedBy = updates.updatedBy
          updatedAt = java.time.Instant.now()
        }
        ?.let { daoToShortLinkModel(it) }
  }

  suspend fun deleteShortLink(id: Int): Boolean = suspendTransaction {
    ShortLinkDAO.findById(id)?.delete()
    true
  }

  // === CLICK TRACKING ===

  suspend fun recordClick(click: ShortLinkClick): ShortLinkClick = suspendTransaction {
    val dao =
        ShortLinkClickDAO.new {
          shortLinkId = ShortLinkDAO[click.shortLinkId].id
          memberId = click.memberId
          resolvedPath = click.resolvedPath
          variableValues = click.variableValues
          ipAddress = click.ipAddress
          userAgent = click.userAgent
          referrer = click.referrer
        }
    ShortLinkClick(
        id = dao.id.value,
        shortLinkId = dao.shortLinkId.value,
        memberId = dao.memberId,
        resolvedPath = dao.resolvedPath,
        variableValues = dao.variableValues,
        clickedAt = toKotlinInstant(dao.clickedAt),
        ipAddress = dao.ipAddress,
        userAgent = dao.userAgent,
        referrer = dao.referrer)
  }

  suspend fun getClickCount(shortLinkId: Int): Long = suspendTransaction {
    ShortLinkClickTable.select { ShortLinkClickTable.shortLinkId eq shortLinkId }.count()
  }

  suspend fun getPopularShortLinks(limit: Int = 10): List<Pair<ShortLink, Long>> =
      suspendTransaction {
        val clickCounts = mutableMapOf<Int, Long>()

        ShortLinkClickTable.selectAll().forEach { row ->
          val linkId = row[ShortLinkClickTable.shortLinkId].value
          clickCounts[linkId] = (clickCounts[linkId] ?: 0L) + 1L
        }

        ShortLinkDAO.find { ShortLinkTable.isActive eq true }
            .map { dao ->
              val clicks = clickCounts[dao.id.value] ?: 0L
              daoToShortLinkModel(dao, clicks) to clicks
            }
            .sortedByDescending { it.second }
            .take(limit)
      }

  // === RESERVED ALIASES ===

  suspend fun isAliasReserved(alias: String): Boolean = suspendTransaction {
    ReservedAliasTable.select { ReservedAliasTable.alias eq alias }.count() > 0
  }

  suspend fun getReservedAliases(): List<String> = suspendTransaction {
    ReservedAliasTable.selectAll().map { it[ReservedAliasTable.alias] }
  }

  // === HELPER METHODS ===

  private fun daoToNamespaceModel(dao: NamespaceDAO): Namespace {
    return Namespace(
        id = dao.id.value,
        name = dao.name,
        ownerType =
            when (dao.ownerType) {
              "committee" -> NamespaceOwnerType.COMMITTEE
              "system" -> NamespaceOwnerType.SYSTEM
              else -> throw IllegalArgumentException("Unknown owner type: ${dao.ownerType}")
            },
        ownerGroupId = dao.ownerGroupId,
        description = dao.description,
        isActive = dao.isActive,
        createdAt = toKotlinInstant(dao.createdAt),
        createdBy = dao.createdBy)
  }

  private fun daoToShortLinkModel(dao: ShortLinkDAO, clickCount: Long = 0L): ShortLink {
    return ShortLink(
        id = dao.id.value,
        namespaceId = dao.namespaceId?.value,
        slug = dao.slug,
        redirectType =
            when (dao.redirectType) {
              "basic" -> RedirectType.BASIC
              "dynamic" -> RedirectType.DYNAMIC
              else -> throw IllegalArgumentException("Unknown redirect type: ${dao.redirectType}")
            },
        destinationUrl = dao.destinationUrl,
        description = dao.description,
        creatorId = dao.creatorId,
        updatedBy = dao.updatedBy,
        isActive = dao.isActive,
        createdAt = toKotlinInstant(dao.createdAt),
        updatedAt = toKotlinInstant(dao.updatedAt),
        clickCount = clickCount)
  }

  private fun toKotlinInstant(javaInstant: java.time.Instant): kotlinx.datetime.Instant {
    return kotlinx.datetime.Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano)
  }
}
