package org.dallasmakerspace.guacamole

class GuacamoleApiException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
