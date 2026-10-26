val koogVersion = "1.3.0"

dependencies {
  implementation(project(":service:core"))
  api("ai.koog:prompt-executor-bedrock-client:$koogVersion")
  api("ai.koog:agents-tools:$koogVersion")
}
