dependencies {
  implementation(project(":service:core"))
  implementation(project(":service:agent"))
  implementation(project(":service:agenttools"))
  implementation(project(":service:calendar"))
  implementation(project(":service:config"))
  implementation(project(":service:members"))
  implementation(project(":service:moodle"))
  implementation("software.amazon.awssdk:bedrockruntime:2.55.1")
}
