package org.dallasmakerspace.winrm

/**
 * A single RDP logon session observed on a target machine via WinRM - the account's own AD
 * identity, when it logged on, and whether it's genuinely `Active` right now (per `quser`'s own
 * STATE column) versus merely `Disc` (disconnected - the client closed without logging off, so
 * the session and its programs are still alive server-side, resumable by reconnecting with the
 * same credentials). [isActive] matters specifically for a user reconnecting to their OWN
 * disconnected session: it should never be blocked as "already connected" the way a genuinely
 * duplicate *active* session would be - see `RemoteAccessHandler.buildMachineViewModel`.
 */
data class WinRmRdpSession(val username: String, val logonStartEpochMs: Long, val isActive: Boolean)

/**
 * One inbound replication link reported by `Get-ADReplicationPartnerMetadata` when run on [server]
 * - i.e. how up to date [server] is with [partner] for [partition]. [partner] is the partner DC's
 * short (NetBIOS) name, extracted from the cmdlet's full partner DN. Timestamps are null if the
 * link has never successfully replicated (a real, if rare, state for a freshly promoted DC).
 */
data class WinRmReplicationPartnerStatus(
    val server: String,
    val partner: String,
    val partition: String,
    val lastReplicationSuccess: java.time.Instant?,
    val lastReplicationAttempt: java.time.Instant?,
    val consecutiveReplicationFailures: Int,
    val lastReplicationResult: Int,
)

interface IWinRmClient {
  /**
   * All current RDP logon sessions on [hostname], observed via WinRM. This INCLUDES Guacamole's
   * own sessions - Guacamole itself connects over RDP using the connecting member's own AD
   * identity (its connection parameters pass through `${GUAC_USERNAME}` as the RDP login, not a
   * shared/generic account), so a Guacamole-routed session and a genuinely direct/bypass session
   * are indistinguishable at this layer - both surface as a real individual member's own username.
   * Callers reconcile this list against the already-known Guacamole active-connection list
   * themselves (see `RemoteAccessService.reconcileDirectSessions`) to isolate the sessions
   * Guacamole doesn't know about.
   *
   * Returns null if the check could not complete (WinRM unreachable, auth failure, timeout,
   * unparsable response) - callers MUST fail OPEN on null (never treat it as an empty list),
   * matching this codebase's existing permissive-on-error philosophy for optional signals.
   */
  suspend fun getRdpLogonSessions(hostname: String): List<WinRmRdpSession>?

  /**
   * Inbound replication status for every partition/partner pair [hostname] (a domain controller)
   * knows about, via `Get-ADReplicationPartnerMetadata`. Requires the target to have the
   * ActiveDirectory PowerShell module available, which is standard on any domain controller.
   *
   * Returns null if the check could not complete (WinRM unreachable, module missing, auth
   * failure, timeout, unparsable response) - callers should treat null as "unknown", not "healthy".
   */
  suspend fun getReplicationPartnerStatus(hostname: String): List<WinRmReplicationPartnerStatus>?
}
