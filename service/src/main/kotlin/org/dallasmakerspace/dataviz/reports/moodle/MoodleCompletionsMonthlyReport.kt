package org.dallasmakerspace.dataviz.reports.moodle

import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.moodle.LearnStatsCalculator

private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
private const val FIRST_TIME = "First-time learners"

class MoodleCompletionsMonthlyReport @Inject constructor(private val support: MoodleReportSupport) :
    DataVizReport() {

  override fun getName(): String = "moodle-completions-monthly"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val (inputs, error) = support.load(params)
    if (inputs == null) return error!!
    val courseKeys = inputs.committee?.let { LearnStatsCalculator.courseKeysFor(inputs.courses, it) }
    val months = LearnStatsCalculator.monthly(inputs.records, courseKeys, inputs.today)
    // First-time learners only make sense site-wide; the committee view is completions alone.
    val showFirstTime = inputs.committee == null
    return DataVizResponse(
        data =
            months.map {
              DataItem(
                  buildMap {
                    put("Month", JsonPrimitive(it.month.format(MONTH_LABEL)))
                    put("Completions", JsonPrimitive(it.completions))
                    if (showFirstTime) put(FIRST_TIME, JsonPrimitive(it.newLearners))
                  })
            },
        dataFields =
            buildList {
              add(DataField(name = "Month", type = DataType.STRING, label = "Month"))
              add(DataField(name = "Completions", type = DataType.NUMBER, label = "Completions"))
              if (showFirstTime) add(DataField(name = FIRST_TIME, type = DataType.NUMBER, label = FIRST_TIME))
            },
        metadata =
            support.metadata(
                inputs, mapOf("Total completions" to "${months.sumOf { it.completions }}")),
    )
  }
}
