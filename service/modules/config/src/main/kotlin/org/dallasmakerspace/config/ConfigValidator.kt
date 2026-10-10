package org.dallasmakerspace.config

interface ConfigValidator<T> {
  fun validate(value: T): ValidationResult
}

data class ValidationResult(val valid: Boolean, val error: String? = null) {
  companion object {
    val OK = ValidationResult(true)

    fun error(msg: String) = ValidationResult(false, msg)
  }
}

class NotBlankValidator : ConfigValidator<String> {
  override fun validate(value: String) =
      if (value.isNotBlank()) ValidationResult.OK
      else ValidationResult.error("Value cannot be blank")
}

class UrlValidator : ConfigValidator<String> {
  override fun validate(value: String): ValidationResult {
    return if (value.startsWith("http://") || value.startsWith("https://")) ValidationResult.OK
    else ValidationResult.error("Must be a valid URL starting with http:// or https://")
  }
}
