val koogVersion = "1.3.0"

dependencies {
  implementation(project(":service:core"))
  implementation(project(":service:config"))
  implementation(project(":service:activedirectory"))
  api("ai.koog:prompt-executor-bedrock-client:$koogVersion")
  api("ai.koog:agents-tools:$koogVersion")
  testImplementation("com.h2database:h2:2.2.220")
}
