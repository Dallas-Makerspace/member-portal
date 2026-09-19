package org.dallasmakerspace.winrm

import javax.inject.Inject
import javax.inject.Singleton
import org.dallasmakerspace.core.LoggerFactory

private const val MOCK_REPLICATION_LAG_SECONDS = 300L

@Singleton
class WinRmClientMock @Inject constructor(loggerFactory: LoggerFactory) : IWinRmClient {
  private val log = loggerFactory.create(javaClass)

  override suspend fun getRdpLogonSessions(hostname: String): List<WinRmRdpSession>? {
    log.info("Mock: getRdpLogonSessions hostname=$hostname")
    return emptyList()
  }

  override suspend fun getReplicationPartnerStatus(hostname: String): List<WinRmReplicationPartnerStatus>? {
    log.info("Mock: getReplicationPartnerStatus hostname=$hostname")
    val now = java.time.Instant.now()
    val otherDcs = listOf("dc1", "dc2", "dc3").filterNot { it == hostname }
    return otherDcs.map { partner ->
      WinRmReplicationPartnerStatus(
          server = hostname,
          partner = partner,
          partition = "DC=example,DC=internal",
          lastReplicationSuccess = now.minusSeconds(MOCK_REPLICATION_LAG_SECONDS),
          lastReplicationAttempt = now.minusSeconds(MOCK_REPLICATION_LAG_SECONDS),
          consecutiveReplicationFailures = 0,
          lastReplicationResult = 0,
      )
    }
  }
}
