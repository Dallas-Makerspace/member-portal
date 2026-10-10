package org.dallasmakerspace.core.api

import kotlinx.serialization.Serializable

data class ApiResponse<T>(val status: Status, val message: String, val data: T?)

@Serializable
enum class Status {
  SUCCESS,
  ERROR,
}
