description = "Web UI for Member Profiles"

val ktorVersion: String by project
val daggerVersion: String by project
val logbackVersion: String by project
val kotlinxVersion = "1.6.0"
val kotlinTestUnit = "2.2.20"

repositories { mavenCentral() }

dependencies {
  implementation(project(":common-models"))
  implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
  implementation("ch.qos.logback", "logback-classic", logbackVersion)
  implementation("org.codehaus.janino:janino:3.1.12") // For conditional logback config
  implementation("com.google.dagger", "dagger", daggerVersion)
  ksp("com.google.dagger", "dagger-compiler", daggerVersion)
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("io.ktor", "ktor-client-auth", ktorVersion)
  implementation("io.ktor", "ktor-client-cio", ktorVersion)
  implementation("io.ktor", "ktor-client-content-negotiation", ktorVersion)
  implementation("io.ktor", "ktor-client-core", ktorVersion)
  implementation("io.ktor", "ktor-client-logging", ktorVersion)
  implementation("io.ktor", "ktor-serialization-gson-jvm", ktorVersion)
  implementation("io.ktor", "ktor-serialization-jackson", ktorVersion)
  implementation("io.ktor", "ktor-server-auth", ktorVersion)
  implementation("io.ktor", "ktor-server-sessions", ktorVersion)
  implementation("io.ktor:ktor-server-caching-headers:$ktorVersion")
  implementation("io.ktor:ktor-server-compression:$ktorVersion")
  implementation("io.ktor:ktor-server-content-negotiation-jvm")
  implementation("io.ktor", "ktor-server-core", ktorVersion)
  implementation("io.ktor:ktor-server-call-id:$ktorVersion")
  implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
  implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
  implementation("io.ktor:ktor-server-forwarded-header-jvm:$ktorVersion")
  implementation("io.ktor", "ktor-server-netty", ktorVersion)
  implementation("io.ktor", "ktor-server-status-pages", ktorVersion)
  implementation("io.ktor", "ktor-server-thymeleaf-jvm", ktorVersion)
  implementation("io.ktor:ktor-server-webjars:$ktorVersion")
  implementation("org.jetbrains.kotlinx", "kotlinx-serialization-json", kotlinxVersion)
  // Elasticsearch logging
  implementation("co.elastic.clients:elasticsearch-java:8.14.3")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
  implementation("com.agido:logback-elasticsearch-appender:3.0.11")

  testImplementation("org.mockito:mockito-core:4.11.0")
  testImplementation("org.mockito:mockito-inline:4.11.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
  testImplementation("io.ktor", "ktor-client-mock", ktorVersion)
  testImplementation("io.ktor", "ktor-serialization-kotlinx-json", ktorVersion)
  testImplementation("io.ktor", "ktor-server-test-host", ktorVersion)
  testImplementation("org.jetbrains.kotlin", "kotlin-test-junit", kotlinTestUnit)
}

plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("com.google.devtools.ksp")
  id("io.ktor.plugin")
  id("org.jetbrains.kotlinx.kover")
  id("com.ncorti.ktfmt.gradle") version "0.23.0"
  id("io.gitlab.arturbosch.detekt")
}

version = "0.0.1"

application {
  mainClass.set("org.dallasmakerspace.server.MemberProfileUiApplicationKt")
  val isDevelopment: Boolean = project.ext.has("development")
  applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

kotlin { jvmToolchain(21) }
