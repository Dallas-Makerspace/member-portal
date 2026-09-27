package org.dallasmakerspace.storage

import java.net.URL
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Confines an [IObjectStore] to a single [StorageNamespace]'s prefix. Every key a caller passes is
 * relative to the namespace; this class does the prefixing so a namespace can never read, write,
 * list or presign a key outside its own prefix.
 */
class NamespacedObjectStore(
    private val delegate: IObjectStore,
    private val namespace: StorageNamespace,
) {
  private fun scoped(key: ObjectKey): ObjectKey = ObjectKey(namespace.prefix) + key.value

  suspend fun put(
      key: ObjectKey,
      bytes: ByteArray,
      contentType: String,
      metadata: Map<String, String> = emptyMap(),
  ): StoredObject = delegate.put(scoped(key), bytes, contentType, metadata).unscoped()

  suspend fun get(key: ObjectKey): ObjectContent? =
      delegate.get(scoped(key))?.let { it.copy(stored = it.stored.unscoped()) }

  suspend fun head(key: ObjectKey): StoredObject? = delegate.head(scoped(key))?.unscoped()

  suspend fun delete(key: ObjectKey) = delegate.delete(scoped(key))

  suspend fun list(
      prefix: ObjectKey,
      limit: Int = IObjectStore.DEFAULT_LIST_LIMIT
  ): List<StoredObject> = delegate.list(scoped(prefix), limit).map { it.unscoped() }

  fun presignedGetUrl(key: ObjectKey, ttl: Duration): URL =
      delegate.presignedGetUrl(scoped(key), ttl)

  /** Strips the namespace prefix back off before handing a [StoredObject] back to the caller. */
  private fun StoredObject.unscoped(): StoredObject =
      copy(key = ObjectKey(key.value.removePrefix("${namespace.prefix}/")))
}

/**
 * Vends [NamespacedObjectStore] instances so consumers never see the raw, unscoped [IObjectStore].
 */
@Singleton
class ObjectStoreFactory @Inject constructor(private val objectStore: IObjectStore) {
  fun forNamespace(namespace: StorageNamespace): NamespacedObjectStore =
      NamespacedObjectStore(objectStore, namespace)
}
