rootProject.name = "member-portal"

include("common-models")
include("service")
include("ui")
include("e2e")

// Feature modules of the service live under service/modules/<name> (project path :service:<name>).
listOf("core", "winrm", "guacamole", "storage", "calendar", "doorcontroller", "shortlinks", "moodle", "bambuddy", "activedirectory", "config", "members", "directory", "smartwaiver", "notifications", "dataviz", "askai").forEach { name ->
  include(":service:$name")
  project(":service:$name").projectDir = file("service/modules/$name")
}
