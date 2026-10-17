dependencies {
  implementation(project(":service:core"))
  implementation(project(":service:config"))
  implementation(project(":service:storage"))
  testImplementation("com.h2database:h2:2.2.220")
}
