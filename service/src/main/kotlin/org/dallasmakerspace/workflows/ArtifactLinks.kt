package org.dallasmakerspace.workflows

import java.net.URL
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dallasmakerspace.storage.ObjectKey
import org.dallasmakerspace.storage.ObjectStoreFactory
import org.dallasmakerspace.storage.StorageNamespace

enum class ArtifactHead {
  PRESENT,
  MISSING,

  /** Unknown namespace or a key the object store would reject. */
  INVALID,
}

/** Checks and links the objects behind workflow artifacts; keys are relative to the namespace. */
interface ArtifactLinks {
  /** Throws `ObjectStoreException` if the store cannot be reached. */
  suspend fun head(namespace: String, key: String): ArtifactHead

  suspend fun presignedGet(namespace: String, key: String, ttl: Duration): URL
}

@Singleton
class ObjectStoreArtifactLinks @Inject constructor(private val factory: ObjectStoreFactory) :
    ArtifactLinks {

  override suspend fun head(namespace: String, key: String): ArtifactHead {
    val (store, objectKey) = scoped(namespace, key) ?: return ArtifactHead.INVALID
    return if (store.head(objectKey) == null) ArtifactHead.MISSING else ArtifactHead.PRESENT
  }

  override suspend fun presignedGet(namespace: String, key: String, ttl: Duration): URL {
    val (store, objectKey) = requireNotNull(scoped(namespace, key)) { "Invalid artifact location" }
    return withContext(Dispatchers.IO) { store.presignedGetUrl(objectKey, ttl) }
  }

  /** The namespaced store always prefixes the key, so a key can never leave its namespace. */
  private fun scoped(namespace: String, key: String) =
      try {
        factory.forNamespace(StorageNamespace.valueOf(namespace)) to ObjectKey(key)
      } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
        null
      }
}
