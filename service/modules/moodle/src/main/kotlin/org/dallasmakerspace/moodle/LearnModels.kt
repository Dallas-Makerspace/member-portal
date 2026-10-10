package org.dallasmakerspace.moodle

import kotlinx.serialization.Serializable

// Portal-facing DTOs served to the UI.

@Serializable
data class LearnCourse(
    val id: Int,
    val shortname: String,
    val name: String,
    val summary: String,
    val url: String,
    val visible: Boolean,
    val categoryName: String,
    val committeeSlug: String? = null,
    val committeeName: String? = null,
    val authorName: String? = null,
    val authorUsername: String? = null,
    /** Members can enrol themselves (Moodle self enrolment is enabled). */
    val selfEnrol: Boolean = false,
    /** AD group a member is added to on completing the course (course custom field ad_group). */
    val adGroup: String? = null,
)

@Serializable data class LearnCatalog(val learnUrl: String, val courses: List<LearnCourse>)

@Serializable
data class LearnMemberCourse(val courseId: Int, val progress: Int? = null, val completed: Boolean)
