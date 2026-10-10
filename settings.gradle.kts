rootProject.name = "member-portal"

include("common-models")
include("service")
include("ui")
include("e2e")

// Feature modules of the service live under service/modules/<name> (project path :service:<name>).
listOf("core").forEach { name ->
  include(":service:$name")
  project(":service:$name").projectDir = file("service/modules/$name")
}
