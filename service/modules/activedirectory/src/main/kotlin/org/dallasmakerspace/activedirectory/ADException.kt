package org.dallasmakerspace.activedirectory

import java.io.IOException

class ADException(message: String, cause: Throwable? = null) : IOException(message, cause)
