package org.dallasmakerspace.moodle

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import org.dallasmakerspace.core.LoggerFactory

private const val MOCK_LEARNERS = 150
private const val MOCK_ENROLMENTS = 600
private const val MOCK_HISTORY_DAYS = 730
private const val MOCK_COMPLETION_PERCENT = 65
private val MOCK_AUTHORS = mapOf(9001 to ("jdoe" to "Jane Doe"), 9002 to ("asmith" to "Alex Smith"))

@Singleton
class MoodleApiClientMock @Inject constructor(loggerFactory: LoggerFactory) : IMoodleApiClient {
  private val log = loggerFactory.create(javaClass)

  override val baseUrl: String = "https://learn.example.org"
  override val statsReportId: Int = 1

  private val categories =
      listOf(
          MoodleCategory(id = 1, name = "Laser", idnumber = "EX-LASER", sortorder = 10000, path = "/1"),
          MoodleCategory(id = 2, name = "Woodshop", idnumber = "EX-WOOD", sortorder = 20000, path = "/2"),
          MoodleCategory(id = 3, name = "Woodshop CNC", parent = 2, sortorder = 20100, path = "/2/3"),
          MoodleCategory(id = 4, name = "3D Fab", idnumber = "EX-3D", sortorder = 30000, path = "/4"),
          MoodleCategory(id = 5, name = "Example General", sortorder = 40000, path = "/5"),
          MoodleCategory(id = 6, name = "Example Sandbox", sortorder = 50000, path = "/6", visible = 0),
      )

  private val courses =
      listOf(
          course(101, "EX-LASER-101", "Example Laser Safety & Orientation", 1, 10001, author = 9001),
          course(102, "EX-LASER-201", "Example Laser Engraving Settings", 1, 10002),
          course(201, "EX-WOOD-100", "Example Woodshop Orientation", 2, 20001, author = 9002),
          course(202, "EX-WOOD-110", "Example Table Saw Basics", 2, 20002, author = 9002),
          course(203, "EX-WOOD-120", "Example Router Table Basics", 2, 20003),
          course(204, "EX-WOOD-130", "Example Bandsaw Basics", 2, 20004, selfEnrol = false),
          course(205, "EX-CNC-100", "Example Tabletop CNC", 3, 20101),
          course(301, "EX-3D-100", "Example 3D Printing: Start Here", 4, 30001, author = 9001),
          course(302, "EX-3D-200", "Example Resin Printing", 4, 30002),
          course(303, "EX-3D-OLD", "Example Retired Printer Course", 4, 30003, visible = 0),
          course(401, "EX-GEN-100", "Example Member Orientation", 5, 40001),
          course(501, "EX-SANDBOX", "Example Course Authoring Sandbox", 6, 50001),
      )

  private val reportRows: List<List<String?>> by lazy {
    val random = Random(42)
    val today = LocalDate.now()
    val format = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH)
    List(MOCK_ENROLMENTS) {
      val course = courses[random.nextInt(courses.size - 1)]
      val enrolled = today.minusDays(random.nextLong(MOCK_HISTORY_DAYS.toLong()))
      val completed =
          if (random.nextInt(100) < MOCK_COMPLETION_PERCENT) enrolled.plusDays(random.nextLong(14))
          else null
      listOf(
          course.shortname,
          "learner${random.nextInt(MOCK_LEARNERS)}",
          enrolled.atTime(10, 30).format(format),
          completed?.takeIf { !it.isAfter(today) }?.atTime(19, 15)?.format(format).orEmpty(),
      )
    }
  }

  override suspend fun getCategories(): List<MoodleCategory> {
    log.info("Mock: getCategories")
    return categories
  }

  override suspend fun getCourses(): List<MoodleCourse> {
    log.info("Mock: getCourses")
    return courses
  }

  override suspend fun getUserId(username: String): Int? {
    log.info("Mock: getUserId($username)")
    return if (username == "never-logged-in") null else username.hashCode() and Int.MAX_VALUE
  }

  override suspend fun getUsernames(userIds: Collection<Int>): Map<Int, String> {
    log.info("Mock: getUsernames($userIds)")
    return userIds.mapNotNull { id -> MOCK_AUTHORS[id]?.let { id to it.first } }.toMap()
  }

  override suspend fun getUserCourses(userId: Int): List<MoodleUserCourse> {
    log.info("Mock: getUserCourses($userId)")
    return listOf(
        MoodleUserCourse(id = 101, progress = 100.0, completed = true),
        MoodleUserCourse(id = 202, progress = 40.0, completed = false),
        MoodleUserCourse(id = 301, progress = 0.0, completed = false),
    )
  }

  override suspend fun getReportPage(reportId: Int, page: Int, perPage: Int): MoodleReportData {
    log.info("Mock: getReportPage($reportId, $page, $perPage)")
    return MoodleReportData(
        headers = listOf("Course short name", "Username", "Time created", "Time completed"),
        rows = reportRows.drop(page * perPage).take(perPage).map { MoodleReportRow(it) },
        totalrowcount = reportRows.size,
    )
  }

  private fun course(
      id: Int,
      shortname: String,
      fullname: String,
      categoryId: Int,
      sortorder: Int,
      visible: Int = 1,
      author: Int? = null,
      selfEnrol: Boolean = true,
  ) =
      MoodleCourse(
          id = id,
          shortname = shortname,
          fullname = fullname,
          categoryid = categoryId,
          sortorder = sortorder,
          summary = "<p>$fullname &amp; a short self-paced quiz. Mock course for local development.</p>",
          visible = visible,
          contacts = listOfNotNull(author?.let { MoodleCourseContact(it, MOCK_AUTHORS.getValue(it).second) }),
          enrollmentmethods = if (selfEnrol) listOf("manual", "self") else listOf("manual"),
      )
}
