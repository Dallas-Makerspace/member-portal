repositories { flatDir { dirs(rootProject.file("service/libs")) } }

dependencies {
  implementation(project(":service:core"))
  implementation(":winrm-java-2.1.00")
}
