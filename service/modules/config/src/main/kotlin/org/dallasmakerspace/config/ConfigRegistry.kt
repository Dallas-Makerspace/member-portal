package org.dallasmakerspace.config

object ConfigRegistry {
  val VOTING_ELECTIONS_URL =
      ConfigKey(
          key = "voting.elections-url",
          description = "URL for the DMS elections procedures information page",
          category = "Voting",
          defaultValue = "https://source.dallasmakerspace.org/display/Board/Board+of+Directors",
          type = ConfigValueType.StringType,
          validators = listOf(NotBlankValidator(), UrlValidator()),
      )

  val GROUP_MEMBER_MANAGEMENT_ENABLED =
      ConfigKey(
          key = "groups.member-management-enabled",
          description = "Enable adding and removing members from groups via the member portal",
          category = "Groups",
          defaultValue = false,
          type = ConfigValueType.BooleanType,
      )

  val GROUP_SETTINGS_ENABLED =
      ConfigKey(
          key = "groups.settings-enabled",
          description =
              "Enable viewing and editing per-group settings (such as allow_self_add_remove " +
                  "and allow_follow) via the member portal; can be piloted for specific " +
                  "admins/AD groups before a full rollout",
          category = "Groups",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )

  val REMOTE_ACCESS_ENABLED =
      ConfigKey(
          key = "remote-access.enabled",
          description =
              "Enable the Remote Access feature (VM connections via Guacamole); can be piloted " +
                  "for specific members/AD groups before a full rollout",
          category = "Remote Access",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )

  val PROFILE_PHOTO_ENABLED =
      ConfigKey(
          key = "profile-photo.enabled",
          description =
              "Let members upload/take a profile photo from their profile page; can be piloted " +
                  "for specific members/AD groups before a full rollout",
          category = "Profile Photo",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )

  val REMOTE_ACCESS_JUMP_SERVER_CONNECTION_IDS =
      ConfigKey(
          key = "remote-access.jump-server.connection-ids",
          description = "Comma-separated Guacamole connection IDs for the Jump Server category",
          category = "Remote Access",
          defaultValue = "2,3,4",
          type = ConfigValueType.StringType,
      )

  val REMOTE_ACCESS_JUMP_SERVER_AD_GROUP =
      ConfigKey(
          key = "remote-access.jump-server.ad-group",
          description = "AD group required for Jump Server access (blank = all members)",
          category = "Remote Access",
          defaultValue = "",
          type = ConfigValueType.StringType,
      )

  val REMOTE_ACCESS_MASTERCAM_CONNECTION_IDS =
      ConfigKey(
          key = "remote-access.mastercam.connection-ids",
          description = "Comma-separated Guacamole connection IDs for the MasterCam category",
          category = "Remote Access",
          defaultValue = "1",
          type = ConfigValueType.StringType,
      )

  val REMOTE_ACCESS_MASTERCAM_AD_GROUP =
      ConfigKey(
          key = "remote-access.mastercam.ad-group",
          description = "AD group required for MasterCam access (blank = all members)",
          category = "Remote Access",
          defaultValue = "",
          type = ConfigValueType.StringType,
      )

  val BAMBU_STATUS_ENABLED =
      ConfigKey(
          key = "bambu-status.enabled",
          description =
              "Enable the Bambu 3D printer status summary/detail pages (backed by Bambuddy); can " +
                  "be piloted for specific members/AD groups before a full rollout",
          category = "3D Fab",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )

  val MOODLE_ENABLED =
      ConfigKey(
          key = "moodle.enabled",
          description =
              "Show DMS Learn (Moodle) online courses on committee pages, the home page, search " +
                  "and Ask AI, plus the Online Courses report; can be piloted for specific " +
                  "members/AD groups before a full rollout",
          category = "Moodle",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )

  val WAIVER_REPORT_ALLOWED_USERNAMES =
      ConfigKey(
          key = "waiver-report.allowed-usernames",
          description =
              "Comma-separated usernames granted access to the Recent Signed Waivers report",
          category = "Waiver Report",
          defaultValue = "",
          type = ConfigValueType.StringType,
      )

  val NOTIFICATIONS_ENABLED =
      ConfigKey(
          key = "notifications.enabled",
          description =
              "Master switch for sending member notifications; when disabled the worker skips " +
                  "sending and notifications wait in the queue until it is re-enabled, except for " +
                  "members listed in enabledUsernames below, whose notifications are still sent - " +
                  "use that to pilot with trusted testers before a full rollout. " +
                  "enabledGroupNames is not honored by the notification worker/digest processor " +
                  "(would require a per-recipient AD group lookup); only enabledUsernames is " +
                  "checked at those call sites",
          category = "Notifications",
          defaultValue = FeatureFlagValue(enabled = true),
          type = ConfigValueType.FeatureFlagType,
      )

  val NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP =
      ConfigKey(
          key = "notifications.per-member-immediate-daily-cap",
          description =
              "Safety valve: max immediate emails (a combined burst counts once) a member " +
                  "receives per Central-time day; the email that reaches it tells the member, and " +
                  "anything after goes to the next daily digest (0 = unlimited)",
          category = "Notifications",
          defaultValue = 15,
          type = ConfigValueType.IntType,
      )

  val FOLLOW_FEATURE_ENABLED =
      ConfigKey(
          key = "notifications.follow-feature-enabled",
          description =
              "Gates the follow/unfollow-a-member feature (profile button, following/followers " +
                  "list, and event-announcement notifications); can be piloted for specific " +
                  "usernames or AD groups before a full rollout",
          category = "Notifications",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )

  val ACCOUNT_MENU_ENABLED =
      ConfigKey(
          key = "nav.account-menu.enabled",
          description =
              "Show the avatar account menu (profile, follows, notifications, settings, sign " +
                  "out, attention dot) instead of the navbar's \"My Profile\" link; on by " +
                  "default, disable as a kill switch",
          category = "Navigation",
          defaultValue = FeatureFlagValue(enabled = true),
          type = ConfigValueType.FeatureFlagType,
      )

  val ALL: List<ConfigKey<*>> =
      listOf(
          VOTING_ELECTIONS_URL,
          GROUP_MEMBER_MANAGEMENT_ENABLED,
          GROUP_SETTINGS_ENABLED,
          REMOTE_ACCESS_ENABLED,
          REMOTE_ACCESS_JUMP_SERVER_CONNECTION_IDS,
          REMOTE_ACCESS_JUMP_SERVER_AD_GROUP,
          REMOTE_ACCESS_MASTERCAM_CONNECTION_IDS,
          REMOTE_ACCESS_MASTERCAM_AD_GROUP,
          BAMBU_STATUS_ENABLED,
          MOODLE_ENABLED,
          WAIVER_REPORT_ALLOWED_USERNAMES,
          NOTIFICATIONS_ENABLED,
          NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP,
          FOLLOW_FEATURE_ENABLED,
          PROFILE_PHOTO_ENABLED,
          ACCOUNT_MENU_ENABLED,
      )
}
