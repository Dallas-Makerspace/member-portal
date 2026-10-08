package org.dallasmakerspace.moodle

interface IMoodleApiClient {
  val baseUrl: String

  /** Id of the custom report the learning stats come from; 0 when not configured. */
  val statsReportId: Int

  suspend fun getCategories(): List<MoodleCategory>

  /** All courses the service user can see, hidden ones included (the site course excluded). */
  suspend fun getCourses(): List<MoodleCourse>

  /** Moodle user id for an AD username, or null if they have never logged into Moodle. */
  suspend fun getUserId(username: String): Int?

  /** Usernames for Moodle user ids; ids the service can't see are left out. */
  suspend fun getUsernames(userIds: Collection<Int>): Map<Int, String>

  suspend fun getUserCourses(userId: Int): List<MoodleUserCourse>

  suspend fun getReportPage(reportId: Int, page: Int, perPage: Int): MoodleReportData
}
