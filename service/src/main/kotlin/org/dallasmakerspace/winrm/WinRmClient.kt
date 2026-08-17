package org.dallasmakerspace.winrm

import io.cloudsoft.winrm4j.client.WinRmClientContext
import io.cloudsoft.winrm4j.winrm.WinRmTool
import java.net.InetSocketAddress
import java.net.Socket
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
import org.apache.http.client.config.AuthSchemes
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory

// Counts ESTABLISHED RDP connections rather than logon sessions - a plain, structured integer
// with no text-table parsing, and only needs standard user rights (consistent with a
// least-privilege "Remote Management Users" account, no admin required).
private const val COUNT_ESTABLISHED_RDP_CONNECTIONS_PS =
    "@(Get-NetTCPConnection -LocalPort 3389 -State Established -ErrorAction SilentlyContinue).Count"

/**
 * Detects direct (non-Guacamole) RDP sessions on a target Windows machine via WinRM.
 *
 * Timeout strategy - deliberately NOT a coroutine `withTimeout`/`withTimeoutOrNull` around the
 * blocking WinRM call: this repo already learned (see CalendarRepository's handling of blocking
 * JDBC calls) that a coroutine timeout only stops *waiting* for a blocking call, it does not
 * interrupt the underlying thread. The real, hard deadline here is [Future.get] with a timeout,
 * which reliably returns control to the caller regardless of whether the worker thread is still
 * blocked. A fast TCP pre-flight on the WinRM port catches the common "not listening/unreachable"
 * case almost for free, before ever touching the (much heavier) SOAP call.
 */
@Singleton
class WinRmClient @Inject constructor(private val appConfig: AppConfig, loggerFactory: LoggerFactory) :
    IWinRmClient {
  companion object {
    init {
      // winrm4j pulls in cxf-rt-transports-http-hc, which registers an async
      // (HttpAsyncClient-based) conduit factory. Async mode hands requests off across pooled
      // connections, but NTLM's challenge-response handshake must stay pinned to a single TCP
      // connection - breaking that produces exactly "Authorization loop detected ... realm null",
      // not a real auth failure. Forcing sync mode makes the same factory return a synchronous
      // Apache HttpClient conduit instead, which correctly pins the connection for the handshake.
      // Must be set before the first WinRM call in the JVM's lifetime, hence here rather than at
      // call time.
      System.setProperty("org.apache.cxf.transport.http.async.usePolicy", "NEVER")
    }
  }

  private val log = loggerFactory.create(javaClass)

  private val port by lazy { appConfig.requireIntProperty("app.winrm.port") }
  // winrm4j's 3-arg WinRmTool.Builder.builder(host, username, password) hardcodes domain=null -
  // it does NOT parse "DOMAIN\user" out of a combined username string (confirmed from its
  // source: `builder(address, username, password)` just delegates to
  // `builder(address, null, username, password)`). Passing the literal "DOMAIN\user" string as a
  // bare username with domain=null produces exactly the symptom this caused in production:
  // "Authorization loop detected ... realm null" on every call. Split it ourselves and use the
  // domain-aware 4-arg overload instead.
  private val rawUsername by lazy { appConfig.requireStringProperty("app.winrm.username") }
  private val domain by lazy { rawUsername.substringBefore('\\', missingDelimiterValue = "").ifBlank { null } }
  private val username by lazy { rawUsername.substringAfter('\\') }
  private val password by lazy { appConfig.requireStringProperty("app.winrm.password") }
  private val connectTimeoutMs by lazy { appConfig.requireIntProperty("app.winrm.connect-timeout-ms") }
  private val operationTimeoutMs by lazy {
    appConfig.requireLongProperty("app.winrm.operation-timeout-ms")
  }
  private val callTimeoutMs by lazy { appConfig.requireLongProperty("app.winrm.call-timeout-ms") }

  // Bounded + daemon so a stuck target (executePs whose thread interrupt is ignored by the
  // underlying CXF conduit) can never block JVM shutdown or grow unbounded - it just occupies one
  // pool slot until the OS eventually reclaims the stuck connection. Created once, since this
  // class itself is @Singleton.
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

  override suspend fun getEstablishedRdpConnectionCount(hostname: String): Int? {
    if (hostname.isBlank()) return null
    if (!canReachWinRmPort(hostname)) {
      log.debug("WinRM port {} unreachable on {}, skipping session check", port, hostname)
      return null
    }
    return withContext(Dispatchers.IO) { executeCountQuery(hostname) }
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

  private fun executeCountQuery(hostname: String): Int? {
    val future =
        executor.submit(
            Callable {
              val context = WinRmClientContext.newInstance()
              try {
                val tool =
                    WinRmTool.Builder.builder(hostname, domain, username, password)
                        .authenticationScheme(AuthSchemes.NTLM)
                        .port(port)
                        .useHttps(false)
                        .context(context)
                        .build()
                tool.setOperationTimeout(operationTimeoutMs)
                tool.executePs(COUNT_ESTABLISHED_RDP_CONNECTIONS_PS)
              } finally {
                context.shutdown()
              }
            })
    return try {
      val response = future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
      if (response.statusCode != 0) {
        log.warn(
            "WinRM session count on {} exited non-zero ({}): {}",
            hostname,
            response.statusCode,
            response.stdErr.trim())
        return null
      }
      response.stdOut.trim().toIntOrNull().also {
        if (it == null) {
          log.warn("Could not parse WinRM session count response from {}: '{}'", hostname, response.stdOut)
        }
      }
    } catch (e: TimeoutException) {
      future.cancel(true) // best-effort - the underlying conduit may not honor interruption
      log.debug("WinRM session count on {} timed out after {}ms", hostname, callTimeoutMs)
      null
    } catch (e: Exception) {
      log.warn("WinRM session count check failed for {}", hostname, e)
      null
    }
  }
}
