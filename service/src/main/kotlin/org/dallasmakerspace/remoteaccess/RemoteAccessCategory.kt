package org.dallasmakerspace.remoteaccess

import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigRegistry

enum class RemoteAccessCategory(
    val slug: String,
    val displayName: String,
    val description: String,
    val connectionIdsConfigKey: ConfigKey<String>,
    val adGroupConfigKey: ConfigKey<String>,
) {
  JUMP_SERVER(
      slug = "jump-server",
      displayName = "Jump Server",
      description = "Shared workstation(s) with common tools and software",
      connectionIdsConfigKey = ConfigRegistry.REMOTE_ACCESS_JUMP_SERVER_CONNECTION_IDS,
      adGroupConfigKey = ConfigRegistry.REMOTE_ACCESS_JUMP_SERVER_AD_GROUP,
  ),
  MASTERCAM(
      slug = "mastercam",
      displayName = "MasterCam",
      description = "MasterCam CAD/CAM workstation",
      connectionIdsConfigKey = ConfigRegistry.REMOTE_ACCESS_MASTERCAM_CONNECTION_IDS,
      adGroupConfigKey = ConfigRegistry.REMOTE_ACCESS_MASTERCAM_AD_GROUP,
  ),
}
