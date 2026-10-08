package org.dallasmakerspace.dataviz.reports.moodle

import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import org.dallasmakerspace.dataviz.DataVizReport
import org.dallasmakerspace.models.DataField
import org.dallasmakerspace.models.DataItem
import org.dallasmakerspace.models.DataType
import org.dallasmakerspace.models.DataVizResponse
import org.dallasmakerspace.moodle.LearnStatsCalculator

class MoodleByCommitteeReport @Inject constructor(private val support: MoodleReportSupport) :
    DataVizReport() {

  override fun getName(): String = "moodle-by-committee"

  override suspend fun getData(params: Map<String, List<String>>): DataVizResponse {
    val (inputs, error) = support.load(params)
    if (inputs == null) return error!!
    val stats =
        LearnStatsCalculator.byCommittee(inputs.records, inputs.courses, inputs.today)
            .sortedByDescending { it.learnersLastYear }
    return DataVizResponse(
        data =
            stats.map {
              DataItem(
                  mapOf(
                      "Committee" to JsonPrimitive(it.committee.name),
                      "Members trained" to JsonPrimitive(it.learnersLastYear),
                  ))
            },
        dataFields =
            listOf(
                DataField(name = "Committee", type = DataType.STRING, label = "Committee"),
                DataField(name = "Members trained", type = DataType.NUMBER, label = "Members trained"),
            ),
        metadata = support.metadata(inputs),
    )
  }
}
