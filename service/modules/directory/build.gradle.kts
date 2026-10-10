dependencies {
  implementation(project(":service:core"))
  implementation(project(":service:config"))
  implementation(project(":service:guacamole"))
  implementation(project(":service:winrm"))
  implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
}
