package org.dallasmakerspace.storage

import java.net.URL
import java.time.Duration

/**
 * A generic, namespace-agnostic read/write abstraction over an object store (S3 in production, an
 * in-memory map for mock mode and tests). Consumers should obtain a scoped instance from
 * `ObjectStoreFactory.forNamespace(...)` rather than depending on this interface directly, so a
 * consumer can never read or write outside its own prefix.
 *
 * All methods throw [ObjectStoreException] on failure; `get`/`head` return null when the key does
 * not exist rather than throwing.
 */
interface IObjectStore {
  suspend fun put(
      key: ObjectKey,
      bytes: ByteArray,
      contentType: String,
      metadata: Map<String, String> = emptyMap(),
  ): StoredObject

  suspend fun get(key: ObjectKey): ObjectContent?

  suspend fun head(key: ObjectKey): StoredObject?

  suspend fun delete(key: ObjectKey)

  suspend fun list(prefix: ObjectKey, limit: Int = DEFAULT_LIST_LIMIT): List<StoredObject>

  fun presignedGetUrl(key: ObjectKey, ttl: Duration): URL

  companion object {
    const val DEFAULT_LIST_LIMIT = 1000
  }
}
