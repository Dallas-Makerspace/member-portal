package org.dallasmakerspace.dataviz.reports.moodle

import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.moodle.LearnStatsCalculator

private val DAY_LABEL = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

class MoodleCoursesReport @Inject constructor(private val support: MoodleReportSupport) :
    DataVizReport() {

  override fun getName(): String = "moodle-courses"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val (inputs, error) = support.load(params)
    if (inputs == null) return error!!
    val stats =
        LearnStatsCalculator.courseStats(inputs.records, inputs.courses, inputs.committee, inputs.today)
    return DataVizResponse(
        data =
            stats.map { s ->
              val course: JsonElement =
                  s.course?.let {
                    buildJsonObject {
                      put("text", JsonPrimitive(if (it.visible) it.name else "${it.name} (hidden)"))
                      put("url", JsonPrimitive(it.url))
                    }
                  } ?: JsonPrimitive(s.courseKey)
              val values = mutableMapOf("Course" to course)
              if (inputs.committee == null) {
                values["Committee"] = JsonPrimitive(s.course?.committeeName ?: "—")
              }
              values["Completions (12 mo)"] = JsonPrimitive(s.completionsLastYear)
              values["Completions (all time)"] = JsonPrimitive(s.completed)
              values["Last completed"] = JsonPrimitive(s.lastCompleted?.format(DAY_LABEL) ?: "Never")
              DataItem(values)
            },
        dataFields =
            buildList {
              add(DataField(name = "Course", type = DataType.LINK, label = "Course"))
              if (inputs.committee == null) {
                add(DataField(name = "Committee", type = DataType.STRING, label = "Committee"))
              }
              add(DataField(name = "Completions (12 mo)", type = DataType.NUMBER, label = "Completions (12 mo)"))
              add(
                  DataField(
                      name = "Completions (all time)", type = DataType.NUMBER, label = "Completions (all time)"))
              add(DataField(name = "Last completed", type = DataType.STRING, label = "Last completed"))
            },
        metadata = support.metadata(inputs, mapOf("Courses" to "${stats.size}")),
    )
  }
}
