package org.dallasmakerspace.storage

/**
 * Top-level prefixes within the shared object-store bucket. Add a case here for each new feature
 * that needs object storage rather than introducing a second bucket or client.
 */
enum class StorageNamespace(val prefix: String) {
  PROFILE_PHOTOS("profile-photos"),
  WORKFLOW_EXPORTS("workflow-exports"),
}
