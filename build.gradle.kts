// Root build file for member-portal monorepo
// Individual module configurations are in service/build.gradle.kts and ui/build.gradle.kts

plugins {
    base
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("com.google.devtools.ksp") version "2.2.20-2.0.2" apply false
    id("io.ktor.plugin") version "3.3.3" apply false
    id("org.jetbrains.kotlinx.kover") version "0.7.6" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
}

// Load local properties if they exist (gitignored for machine-specific settings)
val localPropertiesFile = file("gradle.properties.local")
if (localPropertiesFile.exists()) {
    val localProperties = java.util.Properties()
    localProperties.load(localPropertiesFile.inputStream())
    localProperties.forEach { key, value ->
        if (key is String && value is String) {
            extra.set(key, value)
        }
    }
}

allprojects {
    group = "org.dallasmakerspace"
    version = "0.0.1"
}

tasks.register("runAll") {
    group = "application"
    description = "Runs service and ui subprojects concurrently"
    doLast {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val gradlew = if (isWindows) listOf("cmd", "/c", ".\\gradlew.bat") else listOf("./gradlew")

        data class Proc(val label: String, val task: String)
        val configs = listOf(Proc("service", ":service:run"), Proc("ui", ":ui:run"))

        // Accumulate every PID we've ever seen in each process tree. We snapshot
        // continuously because cmd.exe (the direct child on Windows) exits fast,
        // leaving the Gradle/Ktor JVMs as orphans that descendants() can no longer reach.
        val trackedPids = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

        // Loads KEY=VALUE pairs from <dir>/.env if present (gitignored, never committed).
        // Lets a module be run against real prod-like config locally instead of mocks.
        fun loadDotEnv(dir: File): Map<String, String> {
            val envFile = dir.resolve(".env")
            if (!envFile.exists()) return emptyMap()
            return envFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx < 0) return@mapNotNull null
                    val key = line.substring(0, idx).trim()
                    var value = line.substring(idx + 1).trim()
                    if (value.length >= 2 &&
                        ((value.first() == '"' && value.last() == '"') ||
                            (value.first() == '\'' && value.last() == '\''))
                    ) {
                        value = value.substring(1, value.length - 1)
                    }
                    key to value
                }
                .toMap()
        }

        fun startProcess(cfg: Proc): Process {
            // --no-daemon keeps the Gradle JVM alive as a direct child of cmd.exe,
            // giving us a stable, walkable process tree for the lifetime of the server.
            val processBuilder = ProcessBuilder(gradlew + listOf(cfg.task, "--no-daemon"))
                .directory(rootDir)
                .redirectErrorStream(true)

            // Already-set shell/OS env vars win; .env only fills gaps not already set.
            val dotEnv = loadDotEnv(file(cfg.label))
            if (dotEnv.isNotEmpty()) {
                val env = processBuilder.environment()
                dotEnv.forEach { (k, v) -> env.putIfAbsent(k, v) }
                println("> [${cfg.label}] loaded ${dotEnv.size} vars from ${cfg.label}/.env")
            }

            val process = processBuilder.start()
            trackedPids.add(process.pid())
            Thread {
                while (process.isAlive) {
                    try { process.toHandle().descendants().forEach { trackedPids.add(it.pid()) }
                    } catch (_: Exception) {}
                    Thread.sleep(500)
                }
            }.also { it.isDaemon = true; it.start() }
            Thread {
                process.inputStream.bufferedReader().forEachLine { println("[${cfg.label}] $it") }
            }.also { it.isDaemon = true; it.start() }
            return process
        }

        fun killAll() {
            trackedPids.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }

        println("> Starting service (:8081) and ui (:8000) — Ctrl-C to stop both")
        val processes = configs.map { startProcess(it) }

        // Shutdown hook covers the --no-daemon case (JVM shuts down directly).
        // finally covers the Gradle daemon case: the daemon doesn't shut down on
        // Ctrl+C, but Gradle interrupts the task thread, which unblocks join() and
        // runs the finally block.
        Runtime.getRuntime().addShutdownHook(Thread(::killAll))
        try {
            val threads = processes.map { Thread { it.waitFor() }.also(Thread::start) }
            threads.forEach(Thread::join)
        } finally {
            killAll()
        }
    }
}
