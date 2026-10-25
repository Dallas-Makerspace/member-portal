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

  val BAMBU_NOTIFICATIONS_ENABLED =
      ConfigKey(
          key = "bambu-notifications.enabled",
          description =
              "Enable Bambu printer notifications (one-shot print watches and committee " +
                  "printer-offline alerts, backed by Bambuddy webhooks); can be piloted for " +
                  "specific members/AD groups before a full rollout",
          category = "3D Fab",
          defaultValue = FeatureFlagValue(
              enabledUsernames = setOf("mandarlimaye"),
              enabledGroupNames = setOf("IT Early Access"),
          ),
          type = ConfigValueType.FeatureFlagType,
      )

  val BAMBU_NOTIFICATIONS_OFFLINE_HOLD_MINUTES =
      ConfigKey(
          key = "bambu-notifications.offline-hold-minutes",
          description =
              "Minutes a printer-offline committee alert is held before it is re-checked " +
                  "against Bambuddy and (only if the printer is still offline) sent",
          category = "3D Fab",
          defaultValue = 5,
          type = ConfigValueType.IntType,
      )

  val BAMBU_NOTIFICATIONS_TOMBSTONE_RETENTION_DAYS =
      ConfigKey(
          key = "bambu-notifications.tombstone-retention-days",
          description =
              "Days a fired/expired/cancelled Bambu print watch (or resolved offline alert) " +
                  "row is kept for support/debugging before the sweeper purges it",
          category = "3D Fab",
          defaultValue = 7,
          type = ConfigValueType.IntType,
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

  val COMMITTEE_FOLLOW_ENABLED =
      ConfigKey(
          key = "notifications.committee-follow-enabled",
          description =
              "Gates following a committee (committee page button and new-class notifications); " +
                  "can be piloted for specific usernames or AD groups before a full rollout",
          category = "Notifications",
          defaultValue = FeatureFlagValue(
              enabledUsernames = setOf("mandarlimaye"),
              enabledGroupNames = setOf("IT Early Access"),
          ),
          type = ConfigValueType.FeatureFlagType,
      )

  val COMMITTEE_BROADCAST_ENABLED =
      ConfigKey(
          key = "notifications.committee-broadcast-enabled",
          description =
              "Lets a committee chair send a short message to the committee's followers; " +
                  "can be piloted for specific usernames or AD groups before a full rollout",
          category = "Notifications",
          defaultValue = FeatureFlagValue(
              enabledUsernames = setOf("mandarlimaye"),
              enabledGroupNames = setOf("IT Early Access"),
          ),
          type = ConfigValueType.FeatureFlagType,
      )

  val COMMITTEE_TALK_CATEGORIES =
      ConfigKey(
          key = "notifications.committee-talk-categories",
          description =
              "Talk category for threads created on a chair's behalf, as comma-separated " +
                  "committeeId=categoryId pairs",
          category = "Notifications",
          defaultValue =
              "1=7,2=18,3=12,4=21,5=30,6=9,7=78,8=31,9=10,11=11,19=8,12=19,13=6,14=23,15=13," +
                  "16=69,20=64,21=42,22=63,23=41,17=17,18=99",
          type = ConfigValueType.StringType,
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

  val WORKFLOWS_ENABLED =
      ConfigKey(
          key = "workflows.enabled",
          description =
              "Enable the workflows framework (multi-step operations with preflight, live run " +
                  "status and the admin in-progress page); can be piloted for specific " +
                  "members/AD groups before a full rollout",
          category = "Workflows",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )

  val GROUPS_ARCHIVE_ENABLED =
      ConfigKey(
          key = "groups.archive.enabled",
          description =
              "Allow archiving groups (the group.archive workflow deletes the Active Directory " +
                  "group); can be piloted for specific usernames/AD groups before a full rollout",
          category = "Groups",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )

  val SMARTWAIVER_MIRROR_ENABLED =
      ConfigKey(
          key = "smartwaiver.mirror.enabled",
          description = "Mirror SmartWaiver data when a new waiver or check-in arrives",
          category = "SmartWaiver",
          defaultValue = false,
          type = ConfigValueType.BooleanType,
      )

  val SMARTWAIVER_LOCAL_READS_ENABLED =
      ConfigKey(
          key = "smartwaiver.local-reads.enabled",
          description = "Read SmartWaiver reports and kiosk photos from the local copy",
          category = "SmartWaiver",
          defaultValue = false,
          type = ConfigValueType.BooleanType,
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
          BAMBU_NOTIFICATIONS_ENABLED,
          BAMBU_NOTIFICATIONS_OFFLINE_HOLD_MINUTES,
          BAMBU_NOTIFICATIONS_TOMBSTONE_RETENTION_DAYS,
          MOODLE_ENABLED,
          WAIVER_REPORT_ALLOWED_USERNAMES,
          NOTIFICATIONS_ENABLED,
          NOTIFICATIONS_PER_MEMBER_IMMEDIATE_DAILY_CAP,
          COMMITTEE_BROADCAST_ENABLED,
          COMMITTEE_TALK_CATEGORIES,
          FOLLOW_FEATURE_ENABLED,
          PROFILE_PHOTO_ENABLED,
          ACCOUNT_MENU_ENABLED,
          WORKFLOWS_ENABLED,
          GROUPS_ARCHIVE_ENABLED,
          SMARTWAIVER_MIRROR_ENABLED,
          SMARTWAIVER_LOCAL_READS_ENABLED,
      )
}
