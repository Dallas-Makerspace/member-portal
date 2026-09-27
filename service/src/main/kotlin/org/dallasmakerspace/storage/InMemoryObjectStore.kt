package org.dallasmakerspace.storage

import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory [IObjectStore] used when `app.use-mock-services` is enabled (the `runAll` default) and
 * in tests, so neither requires AWS credentials or network access. State is per-process and is not
 * persisted across restarts.
 */
@Singleton
class InMemoryObjectStore @Inject constructor() : IObjectStore {
  private val objects = ConcurrentHashMap<String, ObjectContent>()

  override suspend fun put(
      key: ObjectKey,
      bytes: ByteArray,
      contentType: String,
      metadata: Map<String, String>,
  ): StoredObject {
    val stored =
        StoredObject(
            key = key,
            sizeBytes = bytes.size.toLong(),
            contentType = contentType,
            lastModified = Instant.now(),
            metadata = metadata,
        )
    objects[key.value] = ObjectContent(stored, bytes.copyOf())
    return stored
  }

  override suspend fun get(key: ObjectKey): ObjectContent? =
      objects[key.value]?.let { it.copy(bytes = it.bytes.copyOf()) }

  override suspend fun head(key: ObjectKey): StoredObject? = objects[key.value]?.stored

  override suspend fun delete(key: ObjectKey) {
    objects.remove(key.value)
  }

  override suspend fun list(prefix: ObjectKey, limit: Int): List<StoredObject> =
      objects.values
          .map { it.stored }
          .filter { it.key.value.startsWith(prefix.value) }
          .sortedBy { it.key.value }
          .take(limit)

  override fun presignedGetUrl(key: ObjectKey, ttl: Duration): URL =
      URL("http://localhost/mock-object-store/${key.value}")
}
