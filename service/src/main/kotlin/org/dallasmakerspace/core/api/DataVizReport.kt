package org.dallasmakerspace.core.api

import javax.inject.Inject
import org.dallasmakerspace.models.DataVizResponse

/**
 * Abstract class representing a data visualization report. Each report has a unique name and must
 * implement the `getData` method to provide the data for the report.
 */
open class DataVizReport @Inject constructor() {

  open fun getName(): String = this::class.simpleName ?: "UnknownReport"

  /**
   * Abstract method to retrieve the data for the report. Implementations of this class must provide
   * the logic to generate or fetch the data for the report.
   *
   * @return A `DataVizResponse` object containing the report data.
   */
  open suspend fun getData(params: Map<String, List<String>>): DataVizResponse? = null
}
