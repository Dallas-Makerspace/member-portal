package org.dallasmakerspace.smartwaiver

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import org.dallasmakerspace.core.LoggerFactory

private const val THURSDAY = 4
private const val SATURDAY = 6
private const val SUNDAY = 7
private const val THURSDAY_WAIVERS_MIN = 15
private const val THURSDAY_WAIVERS_MAX = 30
private const val SATURDAY_WAIVERS_MIN = 20
private const val SATURDAY_WAIVERS_MAX = 40
private const val SUNDAY_WAIVERS_MIN = 5
private const val SUNDAY_WAIVERS_MAX = 15
private const val OTHER_DAYS_WAIVERS_MIN = 0
private const val OTHER_DAYS_WAIVERS_MAX = 10
private const val BUSINESS_HOURS_START = 9
private const val BUSINESS_HOURS_END = 21
private const val MINUTES_IN_HOUR = 60
private const val MOCK_DAYS_3 = 3L
private const val MOCK_DAYS_4 = 4L
private const val MOCK_DAYS_5 = 5L
private const val MOCK_DAYS_6 = 6L
private const val MOCK_CHECKIN_ID_BASE = 1000L
private const val MOCK_TEMPLATE_ID = "tmpl-1"
private const val MOCK_WAIVER_TITLE = "DMS Waiver"
// Minimal valid 1x1 pixel JPEG, used as placeholder mock photo data.
private const val MOCK_JPEG_BASE64 =
    "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJ" +
        "DQ0MDhAQEBAQCg0NEBAQEBAQEBD/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAAv/EABQQAQAAAAAA" +
        "AAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q=="

@Singleton
class SmartwaiverApiClientMock @Inject constructor(loggerFactory: LoggerFactory) :
    ISmartwaiverApiClient {
  private val log = loggerFactory.create(javaClass)

  override suspend fun getWaivers(fromDate: LocalDate, toDate: LocalDate): List<WaiverSigningData> {
    log.info("Mock: Fetching waivers from $fromDate to $toDate")

    val waivers = mutableListOf<WaiverSigningData>()
    var currentDate = fromDate

    while (!currentDate.isAfter(toDate)) {
      val dayOfWeek = currentDate.dayOfWeek.value // 1=Monday, 7=Sunday

      // Generate different patterns for different days
      val waiverCount =
          when (dayOfWeek) {
            THURSDAY -> Random.nextInt(THURSDAY_WAIVERS_MIN, THURSDAY_WAIVERS_MAX)
            SATURDAY -> Random.nextInt(SATURDAY_WAIVERS_MIN, SATURDAY_WAIVERS_MAX)
            SUNDAY -> Random.nextInt(SUNDAY_WAIVERS_MIN, SUNDAY_WAIVERS_MAX)
            else -> Random.nextInt(OTHER_DAYS_WAIVERS_MIN, OTHER_DAYS_WAIVERS_MAX)
          }

      // Generate waivers for this day
      repeat(waiverCount) { index ->
        val randomHour = Random.nextInt(BUSINESS_HOURS_START, BUSINESS_HOURS_END)
        val randomMinute = Random.nextInt(0, MINUTES_IN_HOUR)
        val dateTime = LocalDateTime.of(currentDate, LocalTime.of(randomHour, randomMinute))

        waivers.add(
            WaiverSigningData(
                date = dateTime,
                dayOfWeek = dayOfWeek,
                waiverId = "mock-waiver-${currentDate.toString()}-$index"))
      }

      currentDate = currentDate.plusDays(1)
    }

    log.info("Mock: Generated ${waivers.size} waivers")
    return waivers
  }

  override suspend fun getWaiverDetails(
      fromDate: LocalDate,
      toDate: LocalDate,
  ): List<SmartwaiverSummary> {
    log.info("Mock: Fetching waiver details from $fromDate to $toDate")
    val today = LocalDate.now()
    // Two entries intentionally have mismatches to exercise the mismatch-spotting use case.
    return listOf(
        w(today, 0, "10:15:00", "Alice", "Smith", "alice.smith@example.com"),
        w(today, 0, "11:30:00", "Bob", "Johnson", "bjohnson@example.com"),
        w(today, 1, "14:20:00", "Carol", "Williams", "cwilliams@example.com"),
        w(today, 1, "16:05:00", "David", "Brown", "david.brown@example.com"),
        w(today, 2, "09:45:00", "Eve", "Davis", "eve.davis@example.com"),
        w(today, MOCK_DAYS_3, "18:00:00", "Frank", "Miller", "frank.miller@example.com"),
        w(today, MOCK_DAYS_4, "13:10:00", "Maria", "Garcia", "mgarcia@example.com"),
        w(today, MOCK_DAYS_4, "20:30:00", "Henry", "Wilson", "henry.wilson@example.com"),
        w(today, MOCK_DAYS_5, "11:00:00", "Isabella", "Moore", "imoore@exampl.com"),
        w(today, MOCK_DAYS_6, "15:45:00", "James", "Taylor", "james.taylor@example.com"),
    )
  }

  override suspend fun getWaiver(waiverId: String): SmartwaiverFullWaiver {
    log.info("Mock: Fetching waiver $waiverId")
    return SmartwaiverFullWaiver(
        waiverId = waiverId,
        title = MOCK_WAIVER_TITLE,
        createdOn = "2024-01-01 10:00:00",
        firstName = "Test",
        lastName = "User",
        email = "test.user@example.com",
        dob = "1990-01-01",
        templateId = MOCK_TEMPLATE_ID,
        verified = true,
        kiosk = true,
        isMinor = false,
        tags = listOf("mock-tag"),
        participants =
            listOf(
                SmartwaiverParticipant(
                    firstName = "Test",
                    lastName = "User",
                    dob = "1990-01-01",
                    isMinor = false,
                    minorExpired = false,
                    phone = "555-0100",
                )),
        addressLineOne = "1 Example Street",
        addressCity = "Exampleville",
        addressState = "TX",
        addressZip = "00000",
        addressCountry = "US",
        emergencyContactName = "Pat Example",
        emergencyContactRelation = "Friend",
        emergencyContactPhone = "555-0101",
        photos = 3,
        customWaiverFields =
            mapOf(
                "field-1" to
                    SmartwaiverCustomField(value = "Online", displayText = "Where did you hear about us?"),
                "field-2" to
                    SmartwaiverCustomField(
                        value = "Friend", displayText = "Did anyone refer you?  If so, who?"),
            ),
    )
  }

  override suspend fun getCheckins(from: LocalDateTime, to: LocalDateTime): SmartwaiverCheckinPage {
    log.info("Mock: Fetching check-ins")
    val today = LocalDate.now()
    return SmartwaiverCheckinPage(
        checkins =
            (0L until 3L).map { i ->
              SmartwaiverCheckin(
                  checkinId = MOCK_CHECKIN_ID_BASE + i,
                  waiverId = "mock-Alice-Smith-0",
                  date = "${today.minusDays(i)} 12:00:00",
                  dateSigned = "${today.minusDays(MOCK_DAYS_6)} 12:00:00",
                  position = 0,
                  templateId = MOCK_TEMPLATE_ID,
                  isMinor = 0,
              )
            },
        moreCheckins = false,
    )
  }

  override suspend fun getWaiversByName(firstName: String, lastName: String): List<SmartwaiverSummary> {
    log.info("Mock: Fetching waivers for name")
    val today = LocalDate.now()
    val email = "$firstName.$lastName@example.com".lowercase()
    return listOf(
        w(today, 0, "10:15:00", firstName, lastName, email),
        w(today, MOCK_DAYS_3, "14:20:00", firstName, lastName, email),
    )
  }

  override suspend fun getWaiverPhotos(waiverId: String): List<SmartwaiverPhotoEntry> {
    log.info("Mock: Fetching photos for waiver $waiverId")
    return listOf(
        SmartwaiverPhotoEntry(
            type = "kiosk",
            date = "2024-01-01 10:00:00",
            tag =
                "Photo captured when the participant clicked on the Continue button immediately " +
                    "after selecting if an Adult or Minor(s) would be participating in the activity",
            fileType = "jpg",
            photoId = "mock-photo-$waiverId-1",
            photo = MOCK_JPEG_BASE64,
        ),
        SmartwaiverPhotoEntry(
            type = "kiosk",
            date = "2024-01-01 10:00:05",
            tag = "Photo captured when the first participant accepted their signature",
            fileType = "jpg",
            photoId = "mock-photo-$waiverId-2",
            photo = MOCK_JPEG_BASE64,
        ),
        SmartwaiverPhotoEntry(
            type = "kiosk",
            date = "2024-01-01 10:00:10",
            tag =
                "Photo captured when the participant signing the waiver checked the Electronic " +
                    "Signature Consent box",
            fileType = "jpg",
            photoId = "mock-photo-$waiverId-3",
            photo = MOCK_JPEG_BASE64,
        ),
    )
  }

  private fun w(
      today: LocalDate,
      daysAgo: Long,
      time: String,
      firstName: String,
      lastName: String,
      email: String,
  ) =
      SmartwaiverSummary(
          waiverId = "mock-$firstName-$lastName-$daysAgo",
          templateId = MOCK_TEMPLATE_ID,
          title = MOCK_WAIVER_TITLE,
          createdOn = "${today.minusDays(daysAgo)} $time",
          firstName = firstName,
          lastName = lastName,
          email = email,
      )
}
