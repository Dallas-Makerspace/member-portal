package org.dallasmakerspace.config.routing

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.dallasmakerspace.auth.Permission
import org.dallasmakerspace.auth.authorize
import org.dallasmakerspace.config.ConfigKey
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.api.ApiResponse
import org.dallasmakerspace.core.api.Status

fun Route.configRoutes(configOverrideService: ConfigOverrideService) {
  authorize(Permission.MANAGE_CONFIG) {
    get("/config") {
      val values = configOverrideService.getCurrentValues()
      call.respond(ApiResponse(Status.SUCCESS, "Config values: ${values.size}", values))
    }

    get("/config/{key}") {
      val key =
          call.parameters["key"]
              ?: return@get call.respond(
                  HttpStatusCode.BadRequest,
                  ApiResponse(Status.ERROR, "Key is required", null),
              )
      val isInfraUser = call.request.queryParameters["isInfraUser"]?.toBoolean() ?: false
      @Suppress("UNCHECKED_CAST")
      val configKey =
          ConfigRegistry.ALL.find { it.key == key }
              ?: return@get call.respond(
                  HttpStatusCode.NotFound,
                  ApiResponse(Status.ERROR, "Config key not found: $key", null),
              )
      val resolvedValue = configOverrideService.get(configKey as ConfigKey<Any>, isInfraUser)
      val values = configOverrideService.getCurrentValues()
      val metadata =
          values.find { it.key == key }
              ?: return@get call.respond(
                  HttpStatusCode.NotFound,
                  ApiResponse(Status.ERROR, "Config key not found: $key", null),
              )
      val responseValue =
          metadata.copy(currentValue = configOverrideService.serialize(resolvedValue, configKey))
      call.respond(ApiResponse(Status.SUCCESS, "Config value for $key", responseValue))
    }

    get("/config/{key}/history") {
      val key =
          call.parameters["key"]
              ?: return@get call.respond(
                  HttpStatusCode.BadRequest,
                  ApiResponse(Status.ERROR, "Key is required", null),
              )
      if (ConfigRegistry.ALL.none { it.key == key }) {
        return@get call.respond(
            HttpStatusCode.NotFound,
            ApiResponse(Status.ERROR, "Config key not found: $key", null),
        )
      }
      val history = configOverrideService.getHistory(key)
      call.respond(
          ApiResponse(Status.SUCCESS, "History for $key: ${history.size} entries", history))
    }
  }

  authorize(Permission.MANAGE_CONFIG) {
    patch("/config/{key}") {
      val key =
          call.parameters["key"]
              ?: return@patch call.respond(
                  HttpStatusCode.BadRequest,
                  ApiResponse(Status.ERROR, "Key is required", null),
              )

      val configKey =
          ConfigRegistry.ALL.find { it.key == key }
              ?: return@patch call.respond(
                  HttpStatusCode.NotFound,
                  ApiResponse(Status.ERROR, "Config key not found: $key", null),
              )

      val body = call.receive<Map<String, String>>()
      val rawValue =
          body["value"]
              ?: return@patch call.respond(
                  HttpStatusCode.BadRequest,
                  ApiResponse(Status.ERROR, "Missing 'value' in request body", null),
              )
      val reason =
          body["reason"]
              ?: return@patch call.respond(
                  HttpStatusCode.BadRequest,
                  ApiResponse(Status.ERROR, "Missing 'reason' in request body", null),
              )
      if (reason.isBlank()) {
        return@patch call.respond(
            HttpStatusCode.BadRequest,
            ApiResponse(Status.ERROR, "Reason cannot be blank", null),
        )
      }

      val changedBy = call.request.headers["X-Username"] ?: "unknown"
      val infraOnly = body["infraOnly"]?.toBoolean() ?: false

      try {
        // setRaw handles deserialization from string then validates via the ConfigKey's validators.
        // This works for all ConfigValueType variants via the sealed class dispatch.
        configOverrideService.setRaw(key, rawValue, changedBy, reason, infraOnly)
        call.respond(ApiResponse(Status.SUCCESS, "Config key '$key' updated", null))
      } catch (e: IllegalArgumentException) {
        call.respond(
            HttpStatusCode.BadRequest,
            ApiResponse(Status.ERROR, e.message ?: "Invalid value", null),
        )
      }
    }

    post("/config/{key}/reset") {
      val key =
          call.parameters["key"]
              ?: return@post call.respond(
                  HttpStatusCode.BadRequest,
                  ApiResponse(Status.ERROR, "Key is required", null),
              )

      @Suppress("UNCHECKED_CAST")
      val configKey =
          ConfigRegistry.ALL.find { it.key == key }
              ?: return@post call.respond(
                  HttpStatusCode.NotFound,
                  ApiResponse(Status.ERROR, "Config key not found: $key", null),
              )

      val body = call.receive<Map<String, String>>()
      val reason =
          body["reason"]
              ?: return@post call.respond(
                  HttpStatusCode.BadRequest,
                  ApiResponse(Status.ERROR, "Missing 'reason' in request body", null),
              )
      if (reason.isBlank()) {
        return@post call.respond(
            HttpStatusCode.BadRequest,
            ApiResponse(Status.ERROR, "Reason cannot be blank", null),
        )
      }

      val changedBy = call.request.headers["X-Username"] ?: "unknown"

      @Suppress("UNCHECKED_CAST")
      configOverrideService.resetToDefault(
          configKey as org.dallasmakerspace.config.ConfigKey<Any>,
          changedBy,
          reason,
      )
      call.respond(ApiResponse(Status.SUCCESS, "Config key '$key' reset to default", null))
    }

    post("/config/cache/invalidate") {
      val key = call.request.queryParameters["key"]
      configOverrideService.invalidateCache(key)
      val message =
          if (key != null) "Cache invalidated for key: $key" else "Cache fully invalidated"
      call.respond(ApiResponse(Status.SUCCESS, message, null))
    }
  }
}
