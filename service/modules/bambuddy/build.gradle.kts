dependencies {
  implementation(project(":service:core"))
  // Watch domain consumes the notifications platform (NotificationService, topics/templates) and
  // site config (feature flag + hold/retention values); the feature -> platform direction keeps
  // the dependency graph acyclic (notifications never depends on bambuddy).
  implementation(project(":service:notifications"))
  implementation(project(":service:config"))
  // ProfileResolver: watch/subscription rows are keyed by profile_id like the notifications
  // repositories are.
  implementation(project(":service:members"))
}
