package org.dallasmakerspace.notifications.templates

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.dallasmakerspace.notifications.topics.CommitteeUpdatesTopic

/** Template data for "a committee you follow published an online course". */
@Serializable
data class CommitteeCourseData(
    val courseName: String = "",
    /** Name of the followed committee the course belongs to, e.g. "Laser". */
    val committeeName: String = "",
    val courseUrl: String = "",
)

/** "A committee you follow published an online course", produced when a course becomes visible. */
class CommitteeCourseTemplate :
    NotificationTemplate<CommitteeCourseData>(
        name = "committee-course",
        topic = CommitteeUpdatesTopic,
    ) {
  override val serializer: KSerializer<CommitteeCourseData> = CommitteeCourseData.serializer()

  override fun renderSubject(data: CommitteeCourseData): String =
      "New online course from ${data.committeeName}: ${data.courseName}"

  override fun renderText(data: CommitteeCourseData): String = buildString {
    appendLine("${data.committeeName} published a new online course:")
    appendLine()
    appendLine(data.courseName)
    appendLine()
    appendLine("Take the course at ${data.courseUrl}")
    appendLine()
    appendLine("You are receiving this because you follow ${data.committeeName}.")
  }

  override fun renderHtml(data: CommitteeCourseData): String = buildString {
    append("<p>${escapeHtml(data.committeeName)} published a new online course:</p>")
    append("<h3>${escapeHtml(data.courseName)}</h3>")
    append("<p><a href=\"${escapeHtml(data.courseUrl)}\">Take the course</a></p>")
    append("<p style=\"color:#888;font-size:13px;\">You are receiving this because you follow ")
    append(escapeHtml(data.committeeName))
    append(".</p>")
  }
}
