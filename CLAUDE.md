# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Structure

This is a Gradle multi-module monorepo containing two Kotlin/Ktor applications for the Dallas Makerspace member portal:

- **service/** - Backend REST API service (Ktor 3.0.1, Kotlin 2.0.21)
- **ui/** - Web UI with SSO (Ktor 2.3.5, Kotlin 1.9.23, Thymeleaf)

## Git Workflow

This repo uses **trunk-based development**: all work happens directly on `master`, not on feature branches. `master` has CI/CD wired to it (`Service CD` / `UI CD` workflows), so every commit pushed to `master` is built and deployed automatically — keep it green and keep changes small enough to land safely on their own.

- **Do not create feature branches for normal work.** Commit and push directly to `master`.
- **Guard incomplete/WIP features behind a `FeatureFlagType` site config**, not behind a branch. Add a `ConfigKey` in `service/src/main/kotlin/org/dallasmakerspace/config/ConfigRegistry.kt`:
  ```kotlin
  val MY_NEW_FEATURE_ENABLED =
      ConfigKey(
          key = "my-feature.enabled",
          description = "Enable my new feature",
          category = "My Feature",
          defaultValue = FeatureFlagValue(enabled = false),
          type = ConfigValueType.FeatureFlagType,
      )
  ```
  This lets a feature ship to `master`/production in an off state, then be piloted for specific members/AD groups (`enabledUsernames`/`enabledGroupNames`) before a full rollout (`enabled = true`) — see `GROUP_SETTINGS_ENABLED` or `REMOTE_ACCESS_ENABLED` for existing examples, and `service/src/main/kotlin/org/dallasmakerspace/config/FeatureFlagService.kt` (`isEnabledForUsername`) plus `MemberServiceClient.isFeatureEnabled(...)` in the UI module for how to check the flag for the current user at runtime, rather than the plain `ConfigOverrideService.get(...)` boolean lookup (that pattern has no per-user piloting and is only for simple `BooleanType` toggles).
- A short-lived local branch is fine if you need one for your own workflow (e.g. an editor requirement), but merge/rebase it back into `master` and push promptly — don't let it become a long-running feature branch that master diverges from.

## Browser Testing Workflow

To test UI changes in the browser, start both modules in the background, wait for startup, then use browser tools.

### Start both modules
```bash
# Run both concurrently (cross-platform, no external tools required)
./gradlew runAll        # Linux/macOS
.\gradlew.bat runAll    # Windows
```

Wait ~60 seconds for Gradle + JVM startup, then verify both are up:
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8081  # service — expect non-connection-error
curl -s -o /dev/null -w "%{http_code}" http://localhost:8000  # ui — expect 200 or 302
```

### URLs
- **UI:** http://localhost:8000
- **Service API:** http://localhost:8081

### Stop both modules
Press `Ctrl-C` — the shutdown hook in `runAll` kills both child processes.

## Common Commands

### Root Level (Both Projects)
```bash
# Build all projects
./gradlew build

# Run tests for all projects
./gradlew test

# Clean all projects
./gradlew clean

# List all available tasks
./gradlew tasks
```

### Member Profile Service
```bash
# Build service only
./gradlew :service:build

# Run service
./gradlew :service:run

# Run service tests
./gradlew :service:test

# Run single test class
./gradlew :service:test --tests "org.dallasmakerspace.YourTestClass"

# Run detekt (static analysis)
./gradlew :service:detekt

# Generate code coverage report
./gradlew :service:koverHtmlReport
```

### Member Profile UI
```bash
# Build UI only
./gradlew :ui:build

# Run UI
./gradlew :ui:run

# Run UI tests
./gradlew :ui:test

# Run single test class
./gradlew :ui:test --tests "org.dallasmakerspace.YourTestClass"

# Run detekt (static analysis)
./gradlew :ui:detekt

# Generate code coverage report
./gradlew :ui:koverHtmlReport
```

## Architecture Overview

### Member Profile Service (Backend API)

**Entry Point:** `service/src/main/kotlin/org/dallasmakerspace/Application.kt`

**Key Packages:**
- `di/` - Dagger DI setup with `@Singleton` scoped `AppComponent`
- `plugins/` - Ktor plugin configuration (Routing, Database, HTTP, Serialization)
- `routing/` - REST API routes using Ktor Resources
- `members/` - Business logic with Observer pattern for member changes
- `repositories/` - Data access layer using Exposed ORM
- `activedirectory/` - LDAP/AD integration for user sync
- `discourse/` - Discourse API client
- `core/` - DB connections (MariaDB), AppConfig, logging
- `cron/` - Scheduled jobs (member refresh)
- `webhook/` - Webhook routing and handlers
- `dataviz/` - Data visualization reports

**Key Patterns:**
- **Dagger DI** with lazy initialization in routing to avoid circular dependencies
- **Observer Pattern** for member property changes (e.g., `IMemberPropChangeObserver`)
- **Repository + Service** separation with Exposed ORM (suspend transactions)
- **Conditional Mocking** via `app.use-mock-services` config flag
- **Router Pattern** for webhooks and data viz (name-based handler registration)

**API Authentication:** X-Api-Key and X-Api-Client headers

**Configuration:** `service/src/main/resources/application.conf`

### Member Profile UI (Web Frontend)

**Entry Point:** `ui/src/main/java/org/dallasmakerspace/server/MemberProfileUiApplication.kt`

**Key Packages:**
- `server/di/` - Dagger DI with route handler multi-binding via `@IntoMap`
- `server/routes/` - HTTP request handlers implementing `IRouteHandler`
- `server/plugins/` - Ktor plugins (Templating, Sessions, HTTP, Routing)
- `server/auth/` - OAuth/OIDC authentication
- `server/memberservice/` - Backend API client
- `server/discourse/` - Discourse SSO integration
- `server/common/` - AppConfig, DMSHttpClient, logging
- `server/models/` - Domain models (DMSMember, DMSGroup)
- `thymeleaf/` - Template utilities

**Key Patterns:**
- **AuthRouteHandler** base class for protected routes with session validation
- **Route Factory** enum defining all paths with parameter templates
- **Session-based Authentication** with OAuth/OIDC (Keycloak)
- **Thymeleaf Templating** with FileResolver (dev) vs ClassLoaderResolver (prod)

**Templates:** `ui/src/main/resources/templates/` (index, profile, group, reports, fragments)

**Configuration:** `ui/src/main/resources/application.conf`

### UI Template Requirements

**IMPORTANT:** All HTML templates in `ui/src/main/resources/templates/` must include the following scripts before the closing `</body>` tag:

```html
<!-- Bootstrap JS -->
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"
        integrity="sha384-YvpcrYf0tY3lHB60NNkmXc5s9fDVZLESaAA55NDzOxhy9GkcIdslK1eN7N6jIeHz"
        crossorigin="anonymous"></script>
<!-- App JS -->
<script src="/static/js/app.js"></script>
<!-- Page Loader (REQUIRED for all pages) -->
<script src="/static/js/dms-page-loader.js" defer></script>
```

The page loader (`dms-page-loader.js`) provides:
- Red progress bar at the top of the page during navigation
- Automatic handling of link clicks, form submissions, and back/forward navigation
- YouTube-style loading animation (0% → 30% → 50% → 70% → 85% → 100%)

**When creating new templates:** Always include `dms-page-loader.js` to ensure consistent loading feedback across all pages.

## Development Setup

### IntelliJ IDEA
1. Open IntelliJ IDEA
2. File → Open → Select the `member-portal` directory
3. Import as Gradle project
4. Both modules will be automatically recognized

### Gradle Configuration
- Root `settings.gradle.kts` includes both subprojects
- JDK 21 configured via `gradle.properties` (org.gradle.java.home)
- Each subproject maintains its own build configuration

Both projects use:
- **Detekt** for static code analysis (`config/detekt/detekt.yml`)
- **Kover** for code coverage
- **Docker** for deployment (`prod.Dockerfile`, `docker-compose.dev.yml`)

**Service runs on port 8081 by default**
**UI runs on port 8000 by default**

## Timestamp Handling

**Important:** All timestamps in the database are stored in UTC. When reading timestamps from the database, always convert to Central Time (America/Chicago) before returning to the client or performing date comparisons.

**In Kotlin (Exposed ORM):**
```kotlin
import java.time.ZoneId

val UTC_ZONE = ZoneId.of("UTC")
val CHICAGO_ZONE = ZoneId.of("America/Chicago")

// Convert UTC to Chicago time
val chicagoTimestamp = utcTimestamp
    .atZone(UTC_ZONE)
    .withZoneSameInstant(CHICAGO_ZONE)
    .toLocalDateTime()
```

**In Raw SQL:**
```sql
CONVERT_TZ(timestamp_column, 'UTC', 'America/Chicago')
-- or
CONVERT_TZ(timestamp_column, '+00:00', 'America/Chicago')
```

See `GroupHistoryMapping.kt` for Kotlin example and `CalendarRepository.kt` for raw SQL example.

## External Integrations

- **Active Directory/LDAP** - User authentication and group management
- **Discourse** - Forum SSO integration
- **Keycloak** - OAuth/OIDC provider
- **MariaDB** - Primary database
- **Elasticsearch** - Centralized logging
- **WHMCS/MakerManager** - External data sources (service only)
