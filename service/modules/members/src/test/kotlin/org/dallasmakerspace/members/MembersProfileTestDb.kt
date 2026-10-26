package org.dallasmakerspace.members

import org.dallasmakerspace.core.DBMemberPortalConnection
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

/** H2 stand-in for the profile table, mirroring create.sql's unique keys. */
object MembersProfileTestDb {
  val db: Database by lazy {
    val database =
        Database.connect(
            "jdbc:h2:mem:members-profile;DB_CLOSE_DELAY=-1;MODE=MariaDB;" +
                "DATABASE_TO_LOWER=TRUE;IGNORECASE=TRUE",
            driver = "org.h2.Driver")
    transaction(database) {
      exec(
          "CREATE TABLE IF NOT EXISTS profile (" +
              "id INT AUTO_INCREMENT PRIMARY KEY, " +
              "username VARCHAR(100) NOT NULL, " +
              "is_enabled BOOLEAN DEFAULT FALSE NOT NULL, " +
              "discourse_username VARCHAR(100) NULL, " +
              "discourse_avatar_url VARCHAR(2083) NULL, " +
              "discord_userid VARCHAR(100) NULL, " +
              "discord_username VARCHAR(100) NULL, " +
              "discord_avatar_url VARCHAR(2083) NULL, " +
              "discord_webhook_id VARCHAR(100) NULL, " +
              "profile_photo_key VARCHAR(512) NULL, " +
              "profile_photo_updated_at TIMESTAMP NULL, " +
              "attributes TEXT NULL, " +
              "CONSTRAINT profile_username_unique UNIQUE (username), " +
              "CONSTRAINT profile_discourse_username_unique UNIQUE (discourse_username))")
    }
    database
  }

  fun reset() {
    DBMemberPortalConnection.db = db
    transaction(db) { exec("DELETE FROM profile") }
  }

  fun insert(
      username: String,
      discourseUsername: String? = null,
      discordUserId: String? = null,
      discourseAvatarUrl: String? = null,
  ) {
    transaction(db) {
      exec(
          "INSERT INTO profile (username, discourse_username, discord_userid, " +
              "discourse_avatar_url) VALUES ('$username', ${sql(discourseUsername)}, " +
              "${sql(discordUserId)}, ${sql(discourseAvatarUrl)})")
    }
  }

  fun discourseUsername(username: String): String? = column(username, "discourse_username")

  fun discourseAvatarUrl(username: String): String? = column(username, "discourse_avatar_url")

  fun discordUserId(username: String): String? = column(username, "discord_userid")

  private fun sql(value: String?) = value?.let { "'$it'" } ?: "NULL"

  private fun column(username: String, name: String): String? =
      transaction(db) {
            exec("SELECT $name FROM profile WHERE username = '$username'") {
              val found = mutableListOf<String?>()
              if (it.next()) found.add(it.getString(1))
              found
            }
          }
          ?.firstOrNull()
}
