dependencies {
  implementation(project(":service:core"))
  implementation(project(":service:activedirectory"))
  implementation(project(":service:directory"))
  implementation(project(":service:config"))
  implementation(project(":service:members"))
  implementation(project(":service:moodle"))
  implementation(project(":service:smartwaiver"))
  implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
}
