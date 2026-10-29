package org.dallasmakerspace.itbot.tools

import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.dallasmakerspace.itbot.RepoMirrorService
import org.dallasmakerspace.itbot.RepoSpec

/** A repository that is configured and cloned, ready for read-only git commands. */
data class RepoTarget(val spec: RepoSpec, val dir: Path) {
  fun link(path: String, line: Int? = null): String =
      "${spec.webUrl}/blob/HEAD/$path" + (line?.let { "#L$it" } ?: "")
}

/** Shared argument checks for the code tools, so every git call gets only vetted values. */
@Singleton
class RepoAccess @Inject constructor(private val mirror: RepoMirrorService) {

  /** The named repository, or a message for the model explaining what to do instead. */
  suspend fun target(name: String?): Pair<RepoTarget?, String?> {
    val specs = mirror.repos()
    if (specs.isEmpty()) return null to "No repositories are configured."
    mirror.awaitReady()
    val spec =
        specs.firstOrNull { it.name == name?.trim() }
            ?: return null to "Unknown repository. Available: ${specs.joinToString { it.name }}."
    val dir = mirror.dirOf(spec)
    if (!Files.isDirectory(dir.resolve(".git"))) {
      return null to "${spec.name} has not been downloaded yet. Try again in a minute."
    }
    return RepoTarget(spec, dir) to null
  }

  /** Every repository that is downloaded, for searches that span all of them. */
  suspend fun all(): List<RepoTarget> {
    val specs = mirror.repos()
    if (specs.isNotEmpty()) mirror.awaitReady()
    return specs.map { RepoTarget(it, mirror.dirOf(it)) }.filter { Files.isDirectory(it.dir.resolve(".git")) }
  }

  companion object {
    private val SAFE_PATH = Regex("""[A-Za-z0-9_.@+ /-]{0,300}""")
    private val SAFE_PATHSPEC = Regex("""[A-Za-z0-9_.@+/*?-]{1,200}""")
    private val SAFE_REV = Regex("""[A-Za-z0-9_./~^@{}-]{1,100}""")
    private val SECRET_FILE =
        Regex("""(?i)(^\.env.*|.*\.(pem|key|p12|pfx|jks|keystore)$|^id_(rsa|dsa|ecdsa|ed25519)$|^\.git(config|credentials)?$)""")

    /** A file or directory path inside a repository, or null when it is unsafe or off limits. */
    fun cleanPath(raw: String?): String? {
      val path = raw.orEmpty().trim().trim('/')
      if (!SAFE_PATH.matches(path) || path.startsWith("-")) return null
      val segments = path.split('/').filter { it.isNotEmpty() }
      if (segments.any { it == ".." || it == "." || it == ".git" || SECRET_FILE.matches(it) }) return null
      return segments.joinToString("/")
    }

    /** A glob such as `*.kt`, or null when it holds anything else. */
    fun cleanPathspec(raw: String?): String? {
      val spec = raw.orEmpty().trim()
      if (spec.isEmpty() || !SAFE_PATHSPEC.matches(spec) || spec.startsWith("-") || ".." in spec) return null
      return spec
    }

    /** A commit, tag or branch name, never an option. */
    fun cleanRev(raw: String?): String? {
      val rev = raw.orEmpty().trim()
      return rev.takeIf { SAFE_REV.matches(it) && !it.startsWith("-") }
    }

    val EXCLUDE_SECRETS =
        listOf(":(exclude,glob)**/.env*", ":(exclude,glob)**/*.pem", ":(exclude,glob)**/*.key")
  }
}

internal fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.int(name: String): Int? = string(name)?.trim()?.toIntOrNull()
