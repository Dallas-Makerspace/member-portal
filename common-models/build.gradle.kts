plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("com.ncorti.ktfmt.gradle")
}

repositories { mavenCentral() }

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")

  testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }
