package org.dallasmakerspace.itbot

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dallasmakerspace.config.ConfigOverrideService
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory

/** A public GitHub repository the IT assistant may read. */
data class RepoSpec(val name: String, val url: String) {
  /** Browser URL of the repository, for links in answers. */
  val webUrl: String
    get() = url.removeSuffix(".git")
}

data class RepoStatus(val name: String, val cloned: Boolean, val syncedAt: Instant?, val head: String?)

/**
 * Keeps read-only clones of the configured public repositories on local disk. Refreshed once a day
 * (04:00 Central) and on request; never with credentials, so only public repositories work.
 */
@Singleton
class RepoMirrorService
@Inject
constructor(
    private val configOverrideService: ConfigOverrideService,
    private val git: GitRunner,
    appConfig: AppConfig,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)
  private val root: Path =
      Paths.get(
          appConfig.getStringProperty(
              "app.itbot.mirror-dir", System.getProperty("java.io.tmpdir") + "/agent-repos"))
  private val mock =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  private val syncedAt = ConcurrentHashMap<String, Instant>()
  private val started = AtomicBoolean(false)

  suspend fun repos(): List<RepoSpec> =
      if (mock) emptyList() else parseRepos(configOverrideService.get(ConfigRegistry.AGENT_IT_BOT_REPOS))

  suspend fun repo(name: String): RepoSpec? = repos().firstOrNull { it.name == name }

  fun dirOf(spec: RepoSpec): Path = root.resolve(spec.name)

  suspend fun status(): List<RepoStatus> =
      repos().map { spec ->
        val dir = dirOf(spec)
        val cloned = Files.isDirectory(dir.resolve(".git"))
        val head =
            if (cloned) git.run(dir, "log", "-1", "--format=%h %cs").takeIf { it.ok }?.output?.trim()
            else null
        RepoStatus(spec.name, cloned, syncedAt[spec.name], head)
      }

  /** Clones or updates every repository; one failing does not stop the others. */
  suspend fun syncAll(): List<String> = repos().map { syncOne(it) }

  /** Clones or updates one repository by name; returns a one-line result. */
  suspend fun sync(name: String): String {
    val spec = repo(name) ?: return "Unknown repository: $name"
    return syncOne(spec)
  }

  private suspend fun syncOne(spec: RepoSpec): String =
      mutex.withLock {
        val dir = dirOf(spec)
        val result =
            if (Files.isDirectory(dir.resolve(".git"))) {
              val fetch = git.run(dir, "fetch", "--prune", "--no-tags", "origin", timeoutSeconds = SYNC_SECONDS)
              if (fetch.ok) git.run(dir, "reset", "--hard", "origin/HEAD") else fetch
            } else {
              Files.createDirectories(root)
              git.run(
                  root,
                  "clone",
                  "--no-tags",
                  "--single-branch",
                  "--",
                  spec.url,
                  dir.toString(),
                  timeoutSeconds = SYNC_SECONDS)
            }
        if (result.ok) {
          syncedAt[spec.name] = Instant.now()
          "${spec.name}: up to date"
        } else {
          log.warn("Sync of ${spec.name} failed: ${result.output.take(LOG_CHARS)}")
          "${spec.name}: sync failed"
        }
      }

  /** Syncs now, then once a night, from a deadline timer (no polling). */
  @Suppress("TooGenericExceptionCaught")
  fun startBackgroundSync() {
    if (mock || !started.compareAndSet(false, true)) return
    scope.launch {
      try {
        log.info("IT assistant repositories: ${syncAll().joinToString()}")
      } catch (e: Exception) {
        log.error("Initial repository sync failed", e)
      }
      while (true) {
        delay(millisUntilNextSync(Instant.now()))
        try {
          log.info("IT assistant repositories: ${syncAll().joinToString()}")
        } catch (e: Exception) {
          log.error("Nightly repository sync failed", e)
        }
      }
    }
  }

  companion object {
    private const val SYNC_SECONDS = 180L
    private const val LOG_CHARS = 300
    private const val SYNC_HOUR = 4
    private val GITHUB_URL = Regex("""https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+""")
    private val NAME = Regex("""[a-z0-9][a-z0-9-]{0,40}""")

    /** One `name=https://github.com/owner/repo.git` per line; anything else is ignored. */
    fun parseRepos(text: String): List<RepoSpec> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
              val name = line.substringBefore('=').trim()
              val url = line.substringAfter('=', "").trim()
              if (NAME.matches(name) && GITHUB_URL.matches(url)) RepoSpec(name, url) else null
            }
            .distinctBy { it.name }
            .toList()

    fun millisUntilNextSync(now: Instant): Long {
      val central = now.atZone(ZoneId.of("America/Chicago"))
      var next = central.withHour(SYNC_HOUR).withMinute(0).withSecond(0).withNano(0)
      if (!next.isAfter(central)) next = next.plusDays(1)
      return Duration.between(central, next).toMillis()
    }
  }
}
