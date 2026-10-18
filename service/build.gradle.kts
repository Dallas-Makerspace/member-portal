val ktorVersion: String by project
val kotlinVersion: String by project
val logbackVersion: String by project
val exposedVersion: String by project
val daggerVersion: String by project

plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("io.ktor.plugin")
  id("com.google.devtools.ksp")
  id("org.jetbrains.kotlinx.kover")
  id("com.ncorti.ktfmt.gradle")
  id("io.gitlab.arturbosch.detekt")
}

version = "0.0.1"

application {
  mainClass.set("org.dallasmakerspace.ApplicationKt")

  val isDevelopment: Boolean = project.ext.has("development")
  applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
  mavenCentral()
  flatDir { dirs("libs") }
}

dependencies {
  implementation(project(":common-models"))
  implementation(project(":service:core"))
  implementation(project(":service:askai"))
  implementation(project(":service:dataviz"))
  implementation(project(":service:notifications"))
  implementation(project(":service:keyvalue"))
  implementation(project(":service:smartwaiver"))
  implementation(project(":service:directory"))
  implementation(project(":service:members"))
  implementation(project(":service:config"))
  implementation(project(":service:activedirectory"))
  implementation(project(":service:bambuddy"))
  implementation(project(":service:moodle"))
  implementation(project(":service:shortlinks"))
  implementation(project(":service:doorcontroller"))
  implementation(project(":service:calendar"))
  implementation(project(":service:storage"))
  implementation(project(":service:workflows-api"))
  implementation(project(":service:workflows"))
  implementation(project(":service:workflows-store"))
  implementation(project(":service:guacamole"))
  implementation(project(":service:winrm"))
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.google.dagger:dagger:$daggerVersion")
  ksp("com.google.dagger:dagger-compiler:$daggerVersion")
  implementation("com.googlecode.libphonenumber:libphonenumber:8.13.5")
  implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
  // Ktor common packages
  implementation("io.ktor:ktor-utils:$ktorVersion")
  // Ktor client packages used for making HTTP requests
  implementation("io.ktor:ktor-client-core:$ktorVersion")
  implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
  implementation("io.ktor:ktor-client-json:$ktorVersion")
  implementation("io.ktor:ktor-client-logging:$ktorVersion")
  implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
  // Ktor server packages
  implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
  implementation("io.ktor:ktor-server-cors:$ktorVersion")
  implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
  implementation("io.ktor:ktor-server-content-negotiation-jvm")
  implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
  implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
  implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
  implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
  implementation("com.h2database:h2:2.2.220")
  implementation("io.ktor:ktor-server-call-id-jvm:$ktorVersion")
  implementation("io.ktor:ktor-serialization-gson-jvm:$ktorVersion")
  implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
  implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
  implementation("io.ktor:ktor-server-swagger-jvm:$ktorVersion")
  implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
  implementation("io.ktor:ktor-server-caching-headers-jvm:$ktorVersion")
  implementation("io.ktor:ktor-server-compression-jvm:$ktorVersion")
  implementation("io.ktor:ktor-server-resources:$ktorVersion")
  implementation("io.ktor:ktor-server-auth:$ktorVersion")
  implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
  implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
  implementation("com.unboundid:unboundid-ldapsdk:6.0.7") // LDAP Integration
  implementation("com.ucasoft.ktor:ktor-simple-cache:0.57.7") // In memory cache
  implementation("com.ucasoft.ktor:ktor-simple-memory-cache:0.57.7") // In memory cache
  implementation("ch.qos.logback:logback-classic:$logbackVersion")
  implementation("org.codehaus.janino:janino:3.1.12") // For conditional logback config
  implementation("org.mariadb.jdbc:mariadb-java-client:3.4.0")
  // WinRM client - used to detect direct (non-Guacamole) RDP sessions on target machines.
  // Vendored jar, not a normal dependency - see libs/README.md for why (not on Maven Central,
  // JitPack can't build it) and why this library instead of the CXF-based winrm4j we tried first
  // (a confirmed, unfixed upstream NTLM bug - see the README for the full story).
  implementation(":winrm-java-2.1.00")
  // AWS Bedrock
  implementation("software.amazon.awssdk:bedrockruntime:2.55.1")
  // AWS S3 - generic object storage (profile photos, etc.)
  implementation("software.amazon.awssdk:s3:2.55.1")
  // Elasticsearch logging
  implementation("co.elastic.clients:elasticsearch-java:8.14.3")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
  implementation("com.agido:logback-elasticsearch-appender:3.0.11")

  testImplementation(testFixtures(project(":service:core")))
  testImplementation("io.ktor:ktor-server-test-host")
  testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.mockito:mockito-core:4.11.0")
  testImplementation("org.mockito:mockito-inline:4.11.0")
  testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

// Whole-service coverage: merge the feature modules into :service:koverHtmlReport.
rootProject.subprojects
    .filter { it.path.startsWith(":service:") }
    .forEach { dependencies { kover(it) } }

kotlin { jvmToolchain(21) }
