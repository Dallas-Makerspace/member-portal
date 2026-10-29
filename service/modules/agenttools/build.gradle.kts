dependencies {
  implementation(project(":service:core"))
  implementation(project(":service:agent"))
  implementation(project(":service:config"))
  implementation(project(":service:members"))
  implementation(project(":service:activedirectory"))
  implementation(project(":service:moodle"))
  implementation(project(":service:calendar"))
  implementation(project(":service:workflows-api"))
  testImplementation("com.h2database:h2:2.2.220")
}
