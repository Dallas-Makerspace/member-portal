package org.dallasmakerspace.models

private const val HEX_RADIX = 16

/** Versioned by the storage key (new per upload), so the immutable-cached URL changes every time. */
fun profilePhotoUrl(username: String, key: String): String =
    "/avatar/@$username?v=${key.hashCode().toUInt().toString(HEX_RADIX)}"
