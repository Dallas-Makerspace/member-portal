package org.dallasmakerspace.winrm

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.Logger

/**
 * Exercises the REAL [WinRmClient] (not mocked) against a socket that accepts a TCP connection
 * but never responds, to validate that the hard client-side timeout (`Future.get`) actually
 * bounds wall-clock time - a coroutine timeout alone would not interrupt the underlying blocking
 * SOAP call (see [WinRmClient]'s class doc). Uses `application.conf`'s real dev-mode WinRM
 * defaults (port 5985), so this test briefly binds that port locally for its duration.
 */
class WinRmClientTest {

  @Test
  fun `getRdpLogonSessions returns null within the configured deadline when the target hangs`() =
      runBlocking {
        val loggerFactory = mock<LoggerFactory>()
        whenever(loggerFactory.create(WinRmClient::class.java)).thenReturn(mock<Logger>())
        val client = WinRmClient(AppConfig(), loggerFactory)

        // Bind (but never accept from) the real WinRM port so the TCP pre-flight dial succeeds -
        // the OS completes the handshake once the socket is listening, even without an explicit
        // accept() - and the subsequent SOAP call then hangs indefinitely waiting for an HTTP
        // response that never comes, exercising the Future.get timeout path specifically.
        ServerSocket(5985).use {
          val startMs = System.currentTimeMillis()
          val result = client.getRdpLogonSessions("127.0.0.1")
          val elapsedMs = System.currentTimeMillis() - startMs

          assertNull(result)
          // connect-timeout-ms (500) + call-timeout-ms (1500) from application.conf is the
          // configured budget; 15s gives headroom for JVM/classloading variance on a cold run
          // while still conclusively proving this returns in seconds, not "hangs forever" (which
          // is what this test guards against; the precise millisecond budget is exercised by
          // mocked RemoteAccessServiceTest cases instead).
          assertTrue(elapsedMs < 15_000, "Expected to return well under 15s, took ${elapsedMs}ms")
        }
      }
}
