package org.dallasmakerspace.winrm

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory

@Singleton
class WinRmClientMock @Inject constructor(loggerFactory: LoggerFactory) : IWinRmClient {
  private val log = loggerFactory.create(javaClass)

  override suspend fun getRdpLogonSessions(hostname: String): List<WinRmRdpSession>? {
    log.info("Mock: getRdpLogonSessions hostname=$hostname")
    return emptyList()
  }
}
