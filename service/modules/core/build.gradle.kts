plugins { `java-test-fixtures` }

dependencies {
  // Exposed in core's public API (ElasticsearchClientManager.client)
  api("co.elastic.clients:elasticsearch-java:8.14.3")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
  implementation("com.agido:logback-elasticsearch-appender:3.0.11")
  implementation("org.mariadb.jdbc:mariadb-java-client:3.4.0")
  implementation("com.h2database:h2:2.2.220")
  implementation("com.ucasoft.ktor:ktor-simple-cache:0.57.7")
  implementation("com.ucasoft.ktor:ktor-simple-memory-cache:0.57.7")

  testFixturesImplementation(project(":common-models"))
}
