package org.dallasmakerspace.winrm

interface IWinRmClient {
  /**
   * Total ESTABLISHED RDP (port 3389) connections currently observed on [hostname] via WinRM -
   * this INCLUDES Guacamole's own connection (Guacamole itself connects over RDP, so it is
   * indistinguishable from a direct/bypass session at the network level). Callers must subtract
   * the already-known Guacamole session count for that machine to isolate direct sessions.
   *
   * Returns null if the check could not complete (WinRM unreachable, auth failure, timeout,
   * unparsable response) - callers MUST fail OPEN on null (never treat it as zero), matching this
   * codebase's existing permissive-on-error philosophy for optional connectivity signals.
   */
  suspend fun getEstablishedRdpConnectionCount(hostname: String): Int?
}
