package org.dallasmakerspace.discourse

import java.io.IOException

open class DiscourseApiException : IOException {
  constructor(message: String) : super(message)

  constructor(message: String, cause: Throwable?) : super(message, cause)
}

class DiscourseUserNotFoundException(username: String) :
    DiscourseApiException("User not found in Discourse: $username")
