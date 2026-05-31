plugins { kotlin("jvm") }

val playwrightVersion = "1.60.0"

repositories { mavenCentral() }

val playwright by configurations.creating

dependencies {
  playwright("com.microsoft.playwright:playwright:$playwrightVersion")
  testImplementation("com.microsoft.playwright:playwright:$playwrightVersion")
  testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(21) }

tasks.test {
  useJUnitPlatform()
  if (System.getenv("CI") == null) {
    dependsOn("installPlaywrightBrowsers")
  }

  if (System.getenv("CI") != null) {
    addTestListener(
        object : TestListener {
          override fun beforeSuite(suite: TestDescriptor?) {}

          override fun afterSuite(suite: TestDescriptor?, result: TestResult?) {
            if (suite?.parent == null && result != null && result.skippedTestCount > 0) {
              throw GradleException(
                  "Skipped ${result.skippedTestCount} e2e test(s) in CI; skipped tests are treated as failures.")
            }
          }

          override fun beforeTest(testDescriptor: TestDescriptor?) {}

          override fun afterTest(testDescriptor: TestDescriptor?, result: TestResult?) {}
        })
  }
}

tasks.register("playwrightClasspath") {
  group = "playwright"
  description = "Prints the classpath for com.microsoft.playwright.CLI"
  doLast { println(playwright.asPath) }
}

tasks.register<JavaExec>("installPlaywrightBrowsers") {
  group = "playwright"
  description = "Download Playwright Chromium browser binaries for local development"
  classpath = playwright
  mainClass.set("com.microsoft.playwright.CLI")
  args("install", "chromium")
}
