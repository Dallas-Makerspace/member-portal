dependencies {
  implementation(project(":service:core"))
  implementation(project(":service:workflows-api"))

  testImplementation("com.h2database:h2:2.2.220")
}
