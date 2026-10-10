package org.dallasmakerspace.core

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Simple util class for time related operations. This allows us to mock time in tests. */
@Singleton
class Time @Inject constructor() {
  fun getToday(): LocalDate {
    return LocalDate.now()
  }
}
