package org.dallasmakerspace.dataviz

import javax.inject.Inject
import org.dallasmakerspace.core.api.DataVizReport
import org.dallasmakerspace.models.DataVizResponse

class DataVizRouter
@Inject
constructor(dataVizReportSet: Set<@JvmSuppressWildcards DataVizReport>) {
  private val reportMap: Map<String, DataVizReport> = dataVizReportSet.associateBy { it.getName() }

  suspend fun route(method: String, params: Map<String, List<String>>): DataVizResponse? =
      reportMap[method]?.getData(params)
}
