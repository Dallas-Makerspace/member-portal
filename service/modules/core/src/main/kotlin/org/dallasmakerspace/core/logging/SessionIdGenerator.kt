package org.dallasmakerspace.core.logging

class SessionIdGenerator(private val length: Int = 10) {
  private val charPool: List<Char> = ('a'..'z') + ('0'..'9')

  fun generate(): String {
    return (1..length)
        .map { kotlin.random.Random.nextInt(0, charPool.size) }
        .map(charPool::get)
        .joinToString("")
  }
}
