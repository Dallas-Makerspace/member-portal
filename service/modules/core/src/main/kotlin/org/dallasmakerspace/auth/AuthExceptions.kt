package org.dallasmakerspace.auth

/**
 * Exception thrown when a request lacks valid authentication credentials. Should result in HTTP 401
 * Unauthorized response.
 */
class UnauthorizedException(message: String) : RuntimeException(message)

/**
 * Exception thrown when an authenticated user lacks required permissions. Should result in HTTP 403
 * Forbidden response.
 */
class ForbiddenException(message: String) : RuntimeException(message)
