package org.dallasmakerspace.winrm

/** A single RDP (LogonType=10/RemoteInteractive) logon session observed on a target machine via
 * WinRM - just the account's own AD identity and when it logged on, nothing Guacamole-specific
 * (see [IWinRmClient] for why that's enough to reconcile against Guacamole's own session list). */
data class WinRmRdpSession(val username: String, val logonStartEpochMs: Long)

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
}
