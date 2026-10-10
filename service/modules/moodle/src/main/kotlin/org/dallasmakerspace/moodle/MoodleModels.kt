package org.dallasmakerspace.moodle

import kotlinx.serialization.Serializable

// Wire models for the Moodle web service functions the portal calls. Only the fields we use.

@Serializable
data class MoodleCategory(
    val id: Int,
    val name: String = "",
    val idnumber: String? = null,
    val parent: Int = 0,
    val sortorder: Int = 0,
    val path: String = "",
    val visible: Int = 1,
)

@Serializable
data class MoodleCourse(
    val id: Int,
    val shortname: String = "",
    val fullname: String = "",
    val displayname: String? = null,
    val categoryid: Int = 0,
    val sortorder: Int = 0,
    val summary: String? = null,
    val visible: Int = 1,
    /** Course contacts (teachers) as shown in Moodle's course listings. */
    val contacts: List<MoodleCourseContact> = emptyList(),
    /** Enabled enrolment plugins, e.g. "self", "manual". */
    val enrollmentmethods: List<String> = emptyList(),
    val customfields: List<MoodleCustomField> = emptyList(),
)

@Serializable
data class MoodleCustomField(val shortname: String = "", val value: String? = null, val valueraw: String? = null)

@Serializable data class MoodleCourseContact(val id: Int, val fullname: String = "")

@Serializable data class MoodleCoursesResponse(val courses: List<MoodleCourse> = emptyList())

@Serializable data class MoodleUser(val id: Int, val username: String = "")

@Serializable
data class MoodleUserCourse(
    val id: Int,
    val progress: Double? = null,
    val completed: Boolean? = null,
)

@Serializable data class MoodleReportRow(val columns: List<String?> = emptyList())

@Serializable
data class MoodleReportData(
    val headers: List<String> = emptyList(),
    val rows: List<MoodleReportRow> = emptyList(),
    val totalrowcount: Int = 0,
)

@Serializable data class MoodleReportResponse(val data: MoodleReportData = MoodleReportData())
