dependencies {
  implementation(project(":service:core"))
  implementation(project(":service:activedirectory"))
  implementation(project(":service:config"))
  implementation(project(":service:doorcontroller"))
  implementation(project(":service:storage"))
  implementation("com.googlecode.libphonenumber:libphonenumber:8.13.5")
  testImplementation("com.h2database:h2:2.2.220")
}
