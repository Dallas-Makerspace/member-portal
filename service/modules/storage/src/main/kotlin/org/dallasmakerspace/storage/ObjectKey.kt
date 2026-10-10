package org.dallasmakerspace.storage

private const val MAX_KEY_LENGTH = 900

/**
 * A validated object-store key. Rejects patterns that could be used to escape a namespace prefix or
 * otherwise produce a surprising S3 key (path traversal, leading slash, control characters).
 */
@JvmInline
value class ObjectKey(val value: String) {
  init {
    require(value.isNotBlank()) { "ObjectKey must not be blank" }
    require(value.length <= MAX_KEY_LENGTH) {
      "ObjectKey exceeds $MAX_KEY_LENGTH characters: $value"
    }
    require(!value.startsWith("/")) { "ObjectKey must not start with '/': $value" }
    require(!value.contains("..")) { "ObjectKey must not contain '..': $value" }
    require(value.none { it.isISOControl() }) {
      "ObjectKey must not contain control characters: $value"
    }
  }

  operator fun plus(suffix: String): ObjectKey = ObjectKey("$value/$suffix")

  override fun toString(): String = value
}
