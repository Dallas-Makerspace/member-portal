package org.dallasmakerspace.discourse

import java.io.IOException

open class DiscourseApiException : IOException {
  constructor(message: String) : super(message)

  constructor(message: String, cause: Throwable?) : super(message, cause)
}

class DiscourseUserNotFoundException(username: String) :
    DiscourseApiException("User not found in Discourse: $username")

/** Talk answered 429; try again after [waitSeconds]. */
class DiscourseRateLimitException(val waitSeconds: Long) :
    DiscourseApiException("Talk rate limit hit; retry after $waitSeconds seconds")

/** The topic doesn't exist, was deleted, or can't be seen with the portal's API key. */
class DiscourseTopicNotFoundException(topicId: Int) :
    DiscourseApiException("Topic not found or not visible in Discourse: $topicId")
