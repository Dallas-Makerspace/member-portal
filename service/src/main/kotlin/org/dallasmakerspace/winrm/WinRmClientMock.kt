package org.dallasmakerspace.winrm

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory

@Singleton
class WinRmClientMock @Inject constructor(loggerFactory: LoggerFactory) : IWinRmClient {
  private val log = loggerFactory.create(javaClass)

  override suspend fun getEstablishedRdpConnectionCount(hostname: String): Int? {
    log.info("Mock: getEstablishedRdpConnectionCount hostname=$hostname")
    return 0
  }
}
