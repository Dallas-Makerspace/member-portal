import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

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
    id("com.ncorti.ktfmt.gradle") version "0.23.0" apply false
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

// Shared setup for the :service:* feature modules; each module's own build file only adds what is
// specific to it.
val ktorVersion: String by project
val kotlinVersion: String by project
val logbackVersion: String by project
val exposedVersion: String by project
val daggerVersion: String by project

configure(subprojects.filter { it.path.startsWith(":service:") }) {
  apply(plugin = "org.jetbrains.kotlin.jvm")
  apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
  apply(plugin = "com.google.devtools.ksp")
  apply(plugin = "org.jetbrains.kotlinx.kover")
  apply(plugin = "com.ncorti.ktfmt.gradle")
  apply(plugin = "io.gitlab.arturbosch.detekt")

  repositories {
    mavenCentral()
    // Vendored WinRM jar (see service/libs/README.md); consumers of :service:winrm resolve it too.
    flatDir { dirs(rootProject.file("service/libs")) }
  }

  extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(21) }
  // Tests ran against the app's application.conf / logback.xml before the split; keep that.
  extensions.configure<SourceSetContainer> {
    named("test") { resources.srcDir(rootProject.file("service/src/main/resources")) }
  }
  extensions.configure<DetektExtension> {
    config.setFrom(rootProject.file("service/config/detekt/detekt.yml"))
  }

  dependencies {
    add("implementation", project(":common-models"))
    add("implementation", "com.google.dagger:dagger:$daggerVersion")
    add("ksp", "com.google.dagger:dagger-compiler:$daggerVersion")
    add("implementation", "ch.qos.logback:logback-classic:$logbackVersion")
    add("implementation", "org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
    add("implementation", "com.squareup.okhttp3:okhttp:4.12.0")
    // Ktor server + client packages shared by most feature modules
    add("implementation", "io.ktor:ktor-utils:$ktorVersion")
    add("implementation", "io.ktor:ktor-server-core-jvm:$ktorVersion")
    add("implementation", "io.ktor:ktor-server-auth:$ktorVersion")
    add("implementation", "io.ktor:ktor-server-resources:$ktorVersion")
    add("implementation", "io.ktor:ktor-server-status-pages:$ktorVersion")
    add("implementation", "io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    add("implementation", "io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    add("implementation", "io.ktor:ktor-client-core:$ktorVersion")
    add("implementation", "io.ktor:ktor-client-okhttp:$ktorVersion")
    add("implementation", "io.ktor:ktor-client-json:$ktorVersion")
    add("implementation", "io.ktor:ktor-client-logging:$ktorVersion")
    add("implementation", "io.ktor:ktor-client-content-negotiation:$ktorVersion")
    // Exposed ORM
    add("implementation", "org.jetbrains.exposed:exposed-core:$exposedVersion")
    add("implementation", "org.jetbrains.exposed:exposed-dao:$exposedVersion")
    add("implementation", "org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    add("implementation", "org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

    add("testImplementation", "io.ktor:ktor-server-test-host:$ktorVersion")
    add("testImplementation", "io.ktor:ktor-client-mock:$ktorVersion")
    add("testImplementation", "io.ktor:ktor-serialization-gson-jvm:$ktorVersion")
    if (path != ":service:core") {
      add("testImplementation", testFixtures(project(":service:core")))
    }
    add("testImplementation", "org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
    add("testImplementation", "junit:junit:4.13.2")
    add("testImplementation", "org.mockito:mockito-core:4.11.0")
    add("testImplementation", "org.mockito:mockito-inline:4.11.0")
    add("testImplementation", "org.mockito.kotlin:mockito-kotlin:5.4.0")
  }
}

allprojects {
    group = "org.dallasmakerspace"
    version = "0.0.1"
}

// Loads KEY=VALUE pairs from a gitignored .env file (never committed).
fun loadDotEnv(envFile: File): Map<String, String> {
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

// :service:run and :ui:run read <module>/.env, so runAll and IDE Gradle run configs both get it.
// Env vars already set (shell, OS, run configuration) win; .env only fills gaps.
subprojects {
    tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
        notCompatibleWithConfigurationCache("doFirst calls the script-level loadDotEnv helper")
        val envFile = project.file(".env")
        doFirst {
            val dotEnv = loadDotEnv(envFile)
            if (dotEnv.isEmpty()) return@doFirst
            val added = dotEnv.filterKeys { it !in environment }
            added.forEach { (key, value) -> environment(key, value) }
            logger.lifecycle(
                "> loaded ${added.size} of ${dotEnv.size} vars from ${envFile.parentFile.name}/.env " +
                    "(${dotEnv.size - added.size} already set)")
        }
    }
}

tasks.register("runAll") {
    group = "application"
    description = "Runs service and ui subprojects concurrently"
    notCompatibleWithConfigurationCache("Spawns child Gradle processes from script-level helpers")
    doLast {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val gradlew = if (isWindows) listOf("cmd", "/c", ".\\gradlew.bat") else listOf("./gradlew")

        data class Proc(val label: String, val task: String)
        val configs = listOf(Proc("service", ":service:run"), Proc("ui", ":ui:run"))

        // Accumulate every PID we've ever seen in each process tree. We snapshot
        // continuously because cmd.exe (the direct child on Windows) exits fast,
        // leaving the Gradle/Ktor JVMs as orphans that descendants() can no longer reach.
        val trackedPids = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

        fun startProcess(cfg: Proc): Process {
            // --no-daemon keeps the Gradle JVM alive as a direct child of cmd.exe,
            // giving us a stable, walkable process tree for the lifetime of the server.
            // <module>/.env is loaded by the module's own run task (see subprojects above).
            val processBuilder = ProcessBuilder(gradlew + listOf(cfg.task, "--no-daemon"))
                .directory(rootDir)
                .redirectErrorStream(true)

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
