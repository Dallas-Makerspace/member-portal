package org.dallasmakerspace.storage

import java.time.Instant

/** Metadata about an object, without its bytes. Returned by `put`, `head` and `list`. */
data class StoredObject(
    val key: ObjectKey,
    val sizeBytes: Long,
    val contentType: String,
    val lastModified: Instant,
    val metadata: Map<String, String> = emptyMap(),
)

/** An object's bytes plus its metadata. Returned by `get`. */
data class ObjectContent(
    val stored: StoredObject,
    val bytes: ByteArray,
) {
  override fun equals(other: Any?): Boolean =
      other is ObjectContent && stored == other.stored && bytes.contentEquals(other.bytes)

  override fun hashCode(): Int = stored.hashCode() * 31 + bytes.contentHashCode()
}
