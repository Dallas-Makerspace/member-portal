package org.dallasmakerspace.winrm

import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.metricshub.winrm.WinRMClient as MetricsHubWinRMClient

// Lists RDP (LogonType=10/RemoteInteractive) logon sessions currently on the target, with real
// identity and logon time - not just a raw connection count - via Get-CimInstance, a structured
// JSON array, no text-table parsing. Win32_LogonSession/Win32_Account are readable by any
// authenticated account by default, no elevated rights required beyond standard WMI read access.
//
// The script travels base64-encoded (winrm-java's .powerShell() uses -EncodedCommand under the
// hood), so pipes/quotes/newlines/$variables reach PowerShell exactly as written here - no
// cmd.exe-layer quoting to worry about, unlike a raw WinRS command string.
//
// @() forces array/collection context on the ForEach-Object result. ConvertTo-Json still unwraps
// a *single-element* array to a bare JSON object rather than a one-element array - a longstanding
// quirk present in Windows PowerShell 5.1, where the -AsArray fix (added in PS 6.2+) doesn't
// exist - so that's handled client-side in parseSessions() rather than by adding -AsArray, which
// would break with a parameter-not-found error on any target still running Windows PowerShell 5.1.
private val LIST_RDP_LOGON_SESSIONS_PS =
    """
    @(Get-CimInstance Win32_LogonSession -Filter "LogonType=10" | ForEach-Object {
      ${'$'}acct = Get-CimAssociatedInstance -InputObject ${'$'}_ -ResultClassName Win32_Account -ErrorAction SilentlyContinue | Select-Object -First 1
      if (${'$'}acct) {
        [PSCustomObject]@{ Username = ${'$'}acct.Name; StartEpochMs = [DateTimeOffset]::new(${'$'}_.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds() }
      }
    }) | ConvertTo-Json -Compress
    """
        .trimIndent()

/**
 * Detects RDP (LogonType=10) logon sessions on a target Windows machine via WinRM, with real
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
  private val json = Json { ignoreUnknownKeys = true }

  private val port by lazy { appConfig.requireIntProperty("app.winrm.port") }
  private val username by lazy { appConfig.requireStringProperty("app.winrm.username") }
  private val password by lazy { appConfig.requireStringProperty("app.winrm.password") }
  private val connectTimeoutMs by lazy { appConfig.requireIntProperty("app.winrm.connect-timeout-ms") }
  private val operationTimeoutMs by lazy {
    appConfig.requireLongProperty("app.winrm.operation-timeout-ms")
  }
  private val callTimeoutMs by lazy { appConfig.requireLongProperty("app.winrm.call-timeout-ms") }

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

  private fun canReachWinRmPort(hostname: String): Boolean =
      try {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(hostname, port), connectTimeoutMs)
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
                  .use { it.powerShell(LIST_RDP_LOGON_SESSIONS_PS).execute() }
            })
    return try {
      val response = future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
      if (response.exitCode() != 0) {
        log.warn(
            "WinRM RDP session query on {} exited non-zero ({}): {}",
            hostname,
            response.exitCode(),
            response.stderr().trim())
        return null
      }
      parseSessions(response.stdout(), hostname)
    } catch (e: TimeoutException) {
      future.cancel(true) // best-effort - the underlying client may not honor interruption
      log.debug("WinRM RDP session query on {} timed out after {}ms", hostname, callTimeoutMs)
      null
    } catch (e: Exception) {
      log.warn("WinRM RDP session query failed for {}", hostname, e)
      null
    }
  }

  private fun parseSessions(stdout: String, hostname: String): List<WinRmRdpSession>? {
    val trimmed = stdout.trim()
    if (trimmed.isEmpty()) return emptyList()
    return try {
      // ConvertTo-Json unwraps a single-element array to a bare object (see LIST_RDP_LOGON_-
      // SESSIONS_PS's comment) - normalize both shapes into a uniform list of objects.
      val objects =
          when (val element = json.parseToJsonElement(trimmed)) {
            is JsonArray -> element.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(element)
            else -> emptyList()
          }
      objects.mapNotNull { obj ->
        val user = obj["Username"]?.jsonPrimitive?.content
        val startMs = obj["StartEpochMs"]?.jsonPrimitive?.long
        if (user != null && startMs != null) WinRmRdpSession(user, startMs) else null
      }
    } catch (e: Exception) {
      log.warn("Could not parse WinRM RDP session response from {}: '{}'", hostname, stdout, e)
      null
    }
  }
}
