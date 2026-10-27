package org.dallasmakerspace.itbot

import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

data class GitResult(
    val exitCode: Int,
    val output: String,
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
) {
  val ok: Boolean
    get() = exitCode == 0 && !timedOut
}

/**
 * Runs git with an argument list (never a shell), no credentials, no prompts and no user or system
 * config, so only what the caller passes can influence it.
 */
@Singleton
class GitRunner @Inject constructor() {
  suspend fun run(
      dir: Path?,
      vararg args: String,
      timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
      maxChars: Int = DEFAULT_MAX_CHARS,
  ): GitResult =
      withContext(Dispatchers.IO) {
        val builder =
            ProcessBuilder(listOf("git", "--no-pager") + SAFE_CONFIG + args)
                .redirectErrorStream(true)
        dir?.let { builder.directory(it.toFile()) }
        builder.environment().apply {
          put("GIT_TERMINAL_PROMPT", "0")
          put("GIT_CONFIG_NOSYSTEM", "1")
          put("GIT_CONFIG_GLOBAL", NULL_DEVICE)
          put("LC_ALL", "C")
        }
        val process = builder.start()
        process.outputStream.close()
        coroutineScope {
          val output = async { readCapped(process.inputStream, maxChars) }
          val finished = runInterruptible { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }
          if (!finished) process.destroyForcibly()
          val (text, truncated) = output.await()
          GitResult(
              exitCode = if (finished) process.exitValue() else -1,
              output = text,
              truncated = truncated,
              timedOut = !finished)
        }
      }

  /** Keeps reading past [maxChars] so the process never blocks on a full pipe. */
  private fun readCapped(stream: InputStream, maxChars: Int): Pair<String, Boolean> {
    val text = StringBuilder()
    var truncated = false
    stream.bufferedReader(Charsets.UTF_8).use { reader ->
      val buffer = CharArray(BUFFER_CHARS)
      while (true) {
        val read = reader.read(buffer)
        if (read < 0) break
        if (text.length < maxChars) {
          text.append(buffer, 0, minOf(read, maxChars - text.length))
        }
        if (text.length >= maxChars) truncated = true
      }
    }
    return text.toString() to truncated
  }

  companion object {
    const val DEFAULT_TIMEOUT_SECONDS = 20L
    const val DEFAULT_MAX_CHARS = 20_000
    private const val BUFFER_CHARS = 8_192
    private val NULL_DEVICE = if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"
    private val SAFE_CONFIG =
        listOf(
            "-c", "credential.helper=",
            "-c", "core.fsmonitor=false",
            "-c", "core.hooksPath=$NULL_DEVICE",
            "-c", "protocol.allow=never",
            "-c", "protocol.https.allow=always",
            "-c", "safe.directory=*",
        )
  }
}
