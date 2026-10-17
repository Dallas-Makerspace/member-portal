package org.dallasmakerspace.storage

/**
 * Top-level prefixes within an object-store bucket. Add a case here for each new feature that needs
 * object storage rather than introducing a second client. [SMARTWAIVER] lives in its own bucket
 * (see `StorageModule`) because its retention and access differ from the shared one.
 */
enum class StorageNamespace(val prefix: String) {
  PROFILE_PHOTOS("profile-photos"),
  WORKFLOW_EXPORTS("workflow-exports"),
  SMARTWAIVER("smartwaiver"),
}
