# Door Controller Event Storage

This package implements badge swipe event storage for the Dallas Makerspace door access control system.

## Overview

The door controller system reads badge swipe events from RFID controllers and stores them in a MariaDB table for
reporting and analysis.

## Architecture

### Components

1. **DoorEvent** - Data class representing a door swipe event
2. **DoorEventsRepository** - Data access layer for events and controllers
3. **DoorEventUtils** - Business logic for determining access grant status and event types
4. **DoorControllerService** - Main service that:
    - Reads swipes from controllers via HTTP
    - Converts to DoorEvent format
    - Looks up user IDs from badge numbers
    - Persists events to database

### Data Flow

```
RFID Controller
    ↓ (HTTP scraping)
BadgeSwipeEvent (in-memory)
    ↓ (conversion + enrichment)
DoorEvent (with user ID lookup)
    ↓ (batch insert)
AccessControl.events table
    ↓ (queried by)
Data Visualization Reports
```

## Badge Number Formats

The system handles two badge number formats:

1. **10-digit format** (0015362878)
    - Standard format
    - Stored in `cardNumber` field
    - Used for user ID lookups

2. **CN/comma format** (23427454 or 234,27454)
    - Stored in `cardCN` field
    - Conversion: RfidUtils.tenDigitToCommaFormat()

## Event Source Types

Events are marked with `dbindextype`:

- **0** = Legacy MDB (Microsoft Access) import
    - Imported by Python upload.py script
    - Has detailed status/options from controller firmware

- **1** = HTTP scraping
    - Read from controller web interface
    - Limited to simplified "Allow" / "Forbid" status
    - Implemented in Kotlin HttpRfidClient

## User ID Lookup

User IDs are looked up from the MakerManager database:

1. Check `badge_histories` table for historical badge assignment at the event date
2. Verify badge was 'active' at that time
3. Fallback to current `badges` table if no history found
4. Strip leading zeros from badge numbers for comparison

See `MakerManagerDataRepository.getUserIdsByBadgeNumbers()` for implementation.

## Event Status Codes

For legacy MDB events with detailed status/options fields:

- **Valid Swipe** - Badge authorized, door opened
- **Invalid Swipe** - Badge not authorized
- **Remote Open** - Door opened remotely (not by badge)
- **Super Password Open** - Emergency override password
- **Push Button** - Manual exit button pressed
- **Door Status** - Door state change event
- **Warn** - Security warning/alert

See `DoorEventUtils` for the bit manipulation logic to determine these statuses.

## HTTP Scraping Limitations

Events read via HTTP have simplified data:

- `status` = 1 (success) or 128 (failure)
- `options` = 0 (not available)
- `note` = "Allow IN[#3DOOR]" or "Forbid IN[#3DOOR]"
- `rawData` = raw status string

Full event details are only available from MDB imports.

## Usage

### Persisting Events

```kotlin
val service: DoorControllerService = // injected

// Read recent swipes from all controllers
val result = service.readRecentSwipes(minutes = 60)

// Persist to database
val insertedCount = service.persistEvents(result.events)
```

### Cron Job

The `DoorSwipesCronJob` periodically reads and persists events:

- Configured via `app.door-swipes-cron.enabled` and `app.door-swipes-cron.schedule`
- Reads recent swipes and automatically persists them
- Duplicate events are ignored by unique constraint

## Configuration

Door controllers are configured in `application.conf`:

```
app.door-controller {
  timeout-seconds = 10
  username = "admin"
  password = "password"

  controller-1-name = "doors_normal_102"
  controller-1-ip = "10.16.1.102"
  controller-1-serial = 123456
  controller-1-doors = "1,2,3,4"
}
```
