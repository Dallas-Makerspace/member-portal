package org.dallasmakerspace.keyvalue

import kotlinx.serialization.json.Json

/**
 * Small durable string state addressed by (namespace, key): "has this been announced", a baseline
 * marker, a last-seen id. Pick a namespace per feature (for example `moodle-course-announced`).
 *
 * Not for high-volume data or anything you need to query by field: there are no foreign keys and
 * no per-value indexes, and [keys] reads the whole namespace. Use a real table for those.
 */
interface KeyValueStore {
  suspend fun get(namespace: String, key: String): String?

  /** Inserts or replaces the value. */
  suspend fun put(namespace: String, key: String, value: String)

  /** Inserts only if the key is new; returns whether it did. Safe against concurrent callers. */
  suspend fun putIfAbsent(namespace: String, key: String, value: String): Boolean

  /** [putIfAbsent] for many keys in one transaction (for seeding); returns how many were new. */
  suspend fun putAllIfAbsent(namespace: String, entries: Map<String, String>): Int

  suspend fun contains(namespace: String, key: String): Boolean

  /** Returns whether a value was removed. */
  suspend fun delete(namespace: String, key: String): Boolean

  /** Keys in [namespace] that start with [prefix] (all of them by default). */
  suspend fun keys(namespace: String, prefix: String = ""): Set<String>

  companion object {
    const val MAX_NAMESPACE_LENGTH = 64
    const val MAX_KEY_LENGTH = 190
    const val MAX_VALUE_BYTES = 65535
  }
}

/** A [KeyValueStore] with the namespace fixed, so callers don't repeat it. */
class KeyValueNamespace(private val store: KeyValueStore, val name: String) {
  suspend fun get(key: String) = store.get(name, key)

  suspend fun put(key: String, value: String) = store.put(name, key, value)

  suspend fun putIfAbsent(key: String, value: String) = store.putIfAbsent(name, key, value)

  suspend fun putAllIfAbsent(entries: Map<String, String>) = store.putAllIfAbsent(name, entries)

  suspend fun contains(key: String) = store.contains(name, key)

  suspend fun delete(key: String) = store.delete(name, key)

  suspend fun keys(prefix: String = "") = store.keys(name, prefix)

  /** The value decoded from JSON, or null if the key is absent. */
  suspend inline fun <reified T> getJson(key: String): T? =
      get(key)?.let { JSON.decodeFromString<T>(it) }

  suspend inline fun <reified T> putJson(key: String, value: T) =
      put(key, JSON.encodeToString(value))

  companion object {
    @PublishedApi internal val JSON = Json { ignoreUnknownKeys = true }
  }
}

fun KeyValueStore.namespace(name: String) = KeyValueNamespace(this, name)
