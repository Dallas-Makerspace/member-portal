package org.dallasmakerspace.storage

/** Wraps failures from an [IObjectStore] implementation so callers never see SDK-specific types. */
class ObjectStoreException(
    message: String,
    cause: Throwable? = null,
    val isNotFound: Boolean = false,
    val isRetryable: Boolean = false,
) : RuntimeException(message, cause)
