package org.dallasmakerspace.winrm

import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.metricshub.winrm.WinRMClient as MetricsHubWinRMClient

private const val QUSER_PS = "quser"

// quser's own documented way of saying "zero sessions on this machine" is a non-zero exit code
// plus this exact message (landing on stdout or stderr depending on Windows version) - a real
// empty result, not a failure. Discovered the hard way: Win32_LogonSession (tried first) retains
// leaked/orphaned LSA session records for months after a user has genuinely logged off (confirmed
// live against prwjump - 100+ stale LogonType=10 entries dating back to September, for users who
// were nowhere in quser's own output), making it fundamentally unreliable for "who's really on
// this machine right now". quser talks to the same Remote Desktop Session Host session table an
// admin would see via Task Manager - the actual ground truth - at the cost of parsing its
// console-table text instead of getting clean structured JSON.
private const val QUSER_NO_SESSIONS_MARKER = "No User exists"

// USERNAME SESSIONNAME ID STATE IDLE-TIME LOGON-TIME. SESSIONNAME is blank for some disconnected
// sessions, which shifts the remaining columns - handled by making that group optional rather than
// assuming a fixed column count; STATE is anchored to its known value set specifically so the
// optional-SESSIONNAME case can't misalign against it. The caller's own session (if any) is
// prefixed with ">" instead of a space - stripped, not treated as part of the username.
private val QUSER_LINE_REGEX =
    Regex(
        """^\s*>?(\S+)\s+(?:(\S+)\s+)?(\d+)\s+(Active|Disc|Conn|Idle|Listen|Shadow|Down|Init)\s+(\S+)\s+(.+?)\s*$""")
private const val QUSER_USERNAME_GROUP = 1
private const val QUSER_STATE_GROUP = 4
private const val QUSER_LOGON_TIME_GROUP = 6
private const val QUSER_ACTIVE_STATE = "Active"

// quser renders LOGON TIME as the target machine's own local wall-clock time with no timezone
// info and no fixed AM/PM presence (depends on the OS locale) - tried in order, first match wins.
// Assumes America/Chicago, matching every other target machine in this deployment (see CLAUDE.md's
// Timestamp Handling section for why this repo treats Chicago as the anchor) - a real assumption,
// not a guarantee, since quser's exact format follows the target's OS locale, not ours.
private val QUSER_LOGON_TIME_FORMATS =
    listOf(
        DateTimeFormatter.ofPattern("M/d/yyyy h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("M/d/yyyy H:mm", Locale.US),
    )
private val CHICAGO_ZONE = ZoneId.of("America/Chicago")

// Runs ON the target DC (queries its own inbound replication links for every partition) so three
// WinRM calls - one per DC - build the full mesh picture, rather than trusting one DC's view of
// the other two. Dates are explicitly rendered as ISO-8601 UTC strings ('o' format) since
// ConvertTo-Json otherwise emits the .NET-specific "/Date(...)/ " wire format. -InputObject (not
// piped) so ConvertTo-Json still wraps a single result in a JSON array like every other count.
private const val AD_REPLICATION_STATUS_PS =
    """${'$'}r = @(Get-ADReplicationPartnerMetadata -Target ${'$'}env:COMPUTERNAME -Partition * -ErrorAction Stop | ForEach-Object {
  [PSCustomObject]@{
    Server = ${'$'}_.Server
    Partner = ${'$'}_.Partner
    Partition = ${'$'}_.Partition
    LastReplicationSuccess = ${'$'}(if (${'$'}_.LastReplicationSuccess) { ${'$'}_.LastReplicationSuccess.ToUniversalTime().ToString('o') } else { ${'$'}null })
    LastReplicationAttempt = ${'$'}(if (${'$'}_.LastReplicationAttempt) { ${'$'}_.LastReplicationAttempt.ToUniversalTime().ToString('o') } else { ${'$'}null })
    ConsecutiveReplicationFailures = ${'$'}_.ConsecutiveReplicationFailures
    LastReplicationResult = ${'$'}_.LastReplicationResult
  }
})
ConvertTo-Json -InputObject ${'$'}r -Depth 3"""

// Get-ADReplicationPartnerMetadata's "Partner" is the partner DC's full NTDS Settings DN, e.g.
// "CN=NTDS Settings,CN=DC2,CN=Servers,...", not a plain hostname - extract just the short name.
private val PARTNER_DN_SERVER_REGEX = Regex("""CN=NTDS Settings,\s*CN=([^,]+)""", RegexOption.IGNORE_CASE)

/**
 * Detects RDP logon sessions on a target Windows machine via WinRM (using `quser`, the same
 * Remote Desktop Session Host session table an admin would see in Task Manager), with real
 * identity (AD username) and logon start time - see [IWinRmClient] for why this can be reconciled
 * against Guacamole's own already-known sessions to isolate direct/bypass ones.
 *
 * Uses org.metricshub:winrm-java (vendored - see service/libs/README.md), not the more commonly
 * recommended winrm4j: winrm4j wraps an old Apache CXF stack whose NTLM implementation has a
 * confirmed, unfixed upstream bug (cloudsoft/winrm4j#110) where the handshake completes
 * successfully (verified via the target's own Windows Security event log, event 4624) but the
 * server rejects the subsequent encrypted payload - surfacing as CXF's own "Authorization loop
 * detected". winrm-java removed the CXF backend specifically to fix this class of issue; verified
 * directly against both real target machines before adopting it here.
 *
 * Timeout strategy - deliberately NOT a coroutine `withTimeout`/`withTimeoutOrNull` around the
 * blocking WinRM call: this repo already learned (see CalendarRepository's handling of blocking
 * JDBC calls) that a coroutine timeout only stops *waiting* for a blocking call, it does not
 * interrupt the underlying thread. The real, hard deadline here is [Future.get] with a timeout,
 * which reliably returns control to the caller regardless of whether the worker thread is still
 * blocked - kept as a defensive backstop even though winrm-java's own `.timeout(Duration)` should
 * already enforce socket-level timeouts internally (unlike CXF, this is a from-scratch client, not
 * one more library claim to take on faith). A fast TCP pre-flight on the WinRM port catches the
 * common "not listening/unreachable" case almost for free, before ever touching the SOAP call.
 */
@Singleton
class WinRmClient @Inject constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) :
    IWinRmClient {
  private val log = loggerFactory.create(javaClass)

  private val port by lazy { appConfig.requireIntProperty("app.winrm.port") }
  private val username by lazy { appConfig.requireStringProperty("app.winrm.username") }
  private val password by lazy { appConfig.requireStringProperty("app.winrm.password") }
  private val connectTimeoutMs by lazy { appConfig.requireIntProperty("app.winrm.connect-timeout-ms") }
  private val operationTimeoutMs by lazy {
    appConfig.requireLongProperty("app.winrm.operation-timeout-ms")
  }
  private val callTimeoutMs by lazy { appConfig.requireLongProperty("app.winrm.call-timeout-ms") }

  // Separate, longer budget for the replication check: it loads the ActiveDirectory PowerShell
  // module and enumerates every partition/partner on the target, and DCs may only be reachable
  // over a relayed/tunneled path (e.g. Tailscale) - the RDP-session-check timeouts above are
  // tuned for a fast interactive quser call and are too tight for this.
  private val adReplicationConnectTimeoutMs by lazy {
    appConfig.requireIntProperty("app.ad-replication.connect-timeout-ms")
  }
  private val adReplicationOperationTimeoutMs by lazy {
    appConfig.requireLongProperty("app.ad-replication.operation-timeout-ms")
  }
  private val adReplicationCallTimeoutMs by lazy {
    appConfig.requireLongProperty("app.ad-replication.call-timeout-ms")
  }

  // Bounded + daemon so a stuck target can never block JVM shutdown or grow unbounded - it just
  // occupies one pool slot until the OS eventually reclaims the stuck connection. Created once,
  // since this class itself is @Singleton.
  private val executor by lazy {
    val poolSize = appConfig.requireIntProperty("app.winrm.executor-pool-size")
    val threadNumber = AtomicInteger(1)
    Executors.newFixedThreadPool(
        poolSize,
        ThreadFactory { runnable ->
          Thread(runnable, "winrm-client-${threadNumber.getAndIncrement()}").apply {
            isDaemon = true
          }
        })
  }

  override suspend fun getRdpLogonSessions(hostname: String): List<WinRmRdpSession>? =
      when {
        hostname.isBlank() -> null
        !canReachWinRmPort(hostname) -> {
          log.debug("WinRM port {} unreachable on {}, skipping session check", port, hostname)
          null
        }
        else -> withContext(Dispatchers.IO) { executeSessionQuery(hostname) }
      }

  override suspend fun getReplicationPartnerStatus(hostname: String): List<WinRmReplicationPartnerStatus>? =
      when {
        hostname.isBlank() -> null
        !canReachWinRmPort(hostname, adReplicationConnectTimeoutMs) -> {
          log.debug("WinRM port {} unreachable on {}, skipping replication status check", port, hostname)
          null
        }
        else -> withContext(Dispatchers.IO) { executeReplicationStatusQuery(hostname) }
      }

  private fun canReachWinRmPort(hostname: String, timeoutMs: Int = connectTimeoutMs): Boolean =
      try {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(hostname, port), timeoutMs)
          true
        }
      } catch (e: Exception) {
        false
      }

  private fun executeSessionQuery(hostname: String): List<WinRmRdpSession>? {
    val future =
        executor.submit(
            Callable {
              MetricsHubWinRMClient.builder(hostname)
                  .credentials(username, password.toCharArray())
                  .port(port)
                  .http()
                  .timeout(Duration.ofMillis(operationTimeoutMs))
                  .build()
                  .use { it.powerShell(QUSER_PS).execute() }
            })
    return try {
      val response = future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
      when {
        response.exitCode() == 0 -> parseQuserOutput(response.stdout())
        (response.stdout() + response.stderr()).contains(QUSER_NO_SESSIONS_MARKER, ignoreCase = true) ->
            emptyList()
        else -> {
          log.warn(
              "quser on {} exited non-zero ({}): {}",
              hostname,
              response.exitCode(),
              response.stderr().trim())
          null
        }
      }
    } catch (e: TimeoutException) {
      future.cancel(true) // best-effort - the underlying client may not honor interruption
      log.debug("WinRM RDP session query on {} timed out after {}ms", hostname, callTimeoutMs)
      null
    } catch (e: Exception) {
      log.warn("WinRM RDP session query failed for {}", hostname, e)
      null
    }
  }

  private fun executeReplicationStatusQuery(hostname: String): List<WinRmReplicationPartnerStatus>? {
    val future =
        executor.submit(
            Callable {
              MetricsHubWinRMClient.builder(hostname)
                  .credentials(username, password.toCharArray())
                  .port(port)
                  .http()
                  .timeout(Duration.ofMillis(adReplicationOperationTimeoutMs))
                  .build()
                  .use { it.powerShell(AD_REPLICATION_STATUS_PS).execute() }
            })
    return try {
      val response = future.get(adReplicationCallTimeoutMs, TimeUnit.MILLISECONDS)
      if (response.exitCode() == 0) {
        parseReplicationPartnerJson(hostname, response.stdout())
      } else {
        log.warn(
            "Get-ADReplicationPartnerMetadata on {} exited non-zero ({}): {}",
            hostname,
            response.exitCode(),
            response.stderr().trim())
        null
      }
    } catch (e: TimeoutException) {
      future.cancel(true) // best-effort - the underlying client may not honor interruption
      log.debug("WinRM replication status query on {} timed out after {}ms", hostname, adReplicationCallTimeoutMs)
      null
    } catch (e: Exception) {
      log.warn("WinRM replication status query failed for {}", hostname, e)
      null
    }
  }

  // internal, not private, so tests can exercise the parsing logic directly with canned JSON -
  // real cmdlet output only has one row when a DC has exactly one replication partner, so the
  // single-object-vs-array case is otherwise easy to leave uncovered.
  @Suppress("ReturnCount")
  internal fun parseReplicationPartnerJson(hostname: String, stdout: String): List<WinRmReplicationPartnerStatus> {
    val trimmed = stdout.trim()
    if (trimmed.isEmpty()) return emptyList()
    val root =
        try {
          Json.parseToJsonElement(trimmed)
        } catch (e: Exception) {
          log.warn("Could not parse replication metadata JSON from {}: {}", hostname, e.message)
          return emptyList()
        }
    val items =
        when (root) {
          is JsonArray -> root
          is JsonObject -> JsonArray(listOf(root))
          else -> return emptyList()
        }
    return items.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val server = obj["Server"]?.jsonPrimitive?.contentOrNull
      val partnerDn = obj["Partner"]?.jsonPrimitive?.contentOrNull
      if (server == null || partnerDn == null) {
        log.warn("Replication metadata row from {} missing Server/Partner: {}", hostname, obj)
        return@mapNotNull null
      }
      WinRmReplicationPartnerStatus(
          server = server,
          partner = PARTNER_DN_SERVER_REGEX.find(partnerDn)?.groupValues?.get(1) ?: partnerDn,
          partition = obj["Partition"]?.jsonPrimitive?.contentOrNull ?: "",
          lastReplicationSuccess = parseIsoInstant(obj["LastReplicationSuccess"]?.jsonPrimitive?.contentOrNull),
          lastReplicationAttempt = parseIsoInstant(obj["LastReplicationAttempt"]?.jsonPrimitive?.contentOrNull),
          consecutiveReplicationFailures = obj["ConsecutiveReplicationFailures"]?.jsonPrimitive?.intOrNull ?: 0,
          lastReplicationResult = obj["LastReplicationResult"]?.jsonPrimitive?.intOrNull ?: 0,
      )
    }
  }

  private fun parseIsoInstant(raw: String?): Instant? =
      raw?.let {
        try {
          Instant.parse(it)
        } catch (e: java.time.format.DateTimeParseException) {
          null
        }
      }

  // internal, not private, so tests can exercise the parsing logic directly with canned quser
  // text - the interesting edge cases (blank SESSIONNAME, unparseable lines, locale-dependent
  // LOGON TIME formats) are far easier to cover this way than via a real WinRM round-trip.
  internal fun parseQuserOutput(stdout: String): List<WinRmRdpSession> {
    // First line is always the column header - quser has no way to suppress it, and we only get
    // here once the exit code already told us at least one real session exists.
    val dataLines = stdout.lines().drop(1).filter { it.isNotBlank() }
    return dataLines.mapNotNull { line ->
      val match = QUSER_LINE_REGEX.find(line)
      if (match == null) {
        log.warn("Could not parse quser line: '{}'", line)
        return@mapNotNull null
      }
      val groups = match.groupValues
      val user = groups[QUSER_USERNAME_GROUP]
      val isActive = groups[QUSER_STATE_GROUP] == QUSER_ACTIVE_STATE
      val logonTimeRaw = groups[QUSER_LOGON_TIME_GROUP].trim()
      WinRmRdpSession(user, parseQuserLogonTime(logonTimeRaw) ?: System.currentTimeMillis(), isActive)
    }
  }

  internal fun parseQuserLogonTime(raw: String): Long? {
    for (formatter in QUSER_LOGON_TIME_FORMATS) {
      try {
        return LocalDateTime.parse(raw, formatter).atZone(CHICAGO_ZONE).toInstant().toEpochMilli()
      } catch (e: DateTimeParseException) {
        // try the next format
      }
    }
    log.warn("Could not parse quser LOGON TIME '{}' with any known format", raw)
    return null
  }
}
