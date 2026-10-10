package org.dallasmakerspace.db.master

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.core.Time
import org.dallasmakerspace.voterregistration.UserGracePeriod
import org.dallasmakerspace.voterregistration.VoterRegistrationManager
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

@RunWith(JUnit4::class)
class WhmcsDataServiceTest {

  private val time: Time = mock()
  private val whmcsDataRepository: WhmcsDataRepository = mock()
  private val voterRegistrationManager: VoterRegistrationManager = mock()
  private lateinit var whmcsDataService: WhmcsDataService

  @Before
  fun setup() {
    whenever(time.getToday()).thenReturn(TODAY)
    whmcsDataService = WhmcsDataService(time, whmcsDataRepository, voterRegistrationManager)
  }

  @Test
  fun `getAccountInfoMap returns true for active product`() = runBlocking {
    val whmcsId = 1
    val products = listOf(ACTIVE_PRODUCT)

    whenever(whmcsDataRepository.getAccountProductInfoMap(listOf(whmcsId), START_DATE))
        .thenReturn(mapOf(whmcsId to products))
    whenever(voterRegistrationManager.getUserGracePeriods()).thenReturn(emptyList())

    val result = whmcsDataService.getAccountInfoMap(listOf(whmcsId))
    assertTrue(result[whmcsId]!!.wasActiveInRange)
  }

  @Test
  fun `getAccountInfoMap returns false for terminated product outside grace period`() =
      runBlocking {
        val whmcsId = 1
        val products = listOf(TERMINATED_PRODUCT)

        whenever(whmcsDataRepository.getAccountProductInfoMap(listOf(whmcsId), START_DATE))
            .thenReturn(mapOf(whmcsId to products))
        whenever(voterRegistrationManager.getUserGracePeriods()).thenReturn(emptyList())

        val result = whmcsDataService.getAccountInfoMap(listOf(whmcsId))
        assertFalse(result[whmcsId]!!.wasActiveInRange)
        assertEquals(result[whmcsId]!!.lastInactiveDate, TODAY)
      }

  @Test
  fun `getAccountInfoMap returns true for terminated product within grace period`() = runBlocking {
    val whmcsId = 1
    val products = listOf(PRODUCT_PARTIAL_1, TERMINATED_PRODUCT, PRODUCT_PARTIAL_3)

    whenever(whmcsDataRepository.getAccountProductInfoMap(listOf(whmcsId), START_DATE))
        .thenReturn(mapOf(whmcsId to products))
    whenever(voterRegistrationManager.getUserGracePeriods())
        .thenReturn(getValidGracePeriods(whmcsId))

    val result = whmcsDataService.getAccountInfoMap(listOf(whmcsId))
    assertTrue(result[whmcsId]!!.wasActiveInRange)
  }

  @Test
  fun `getAccountInfoMap returns false for terminated product within expired grace period`() =
      runBlocking {
        val whmcsId = 1
        val products = listOf(PRODUCT_PARTIAL_1, TERMINATED_PRODUCT, PRODUCT_PARTIAL_3)

        whenever(whmcsDataRepository.getAccountProductInfoMap(listOf(whmcsId), START_DATE))
            .thenReturn(mapOf(whmcsId to products))
        whenever(voterRegistrationManager.getUserGracePeriods())
            .thenReturn(listOf(getExpiredGracePeriod(whmcsId)))

        val result = whmcsDataService.getAccountInfoMap(listOf(whmcsId))
        assertTrue(result[whmcsId]!!.wasActiveInRange)
      }

  @Test
  fun `getAccountInfoMap returns false for future product`() = runBlocking {
    val whmcsId = 1
    val products = listOf(FUTURE_PRODUCT)

    whenever(whmcsDataRepository.getAccountProductInfoMap(listOf(whmcsId), START_DATE))
        .thenReturn(mapOf(whmcsId to products))
    whenever(voterRegistrationManager.getUserGracePeriods()).thenReturn(emptyList())

    val result = whmcsDataService.getAccountInfoMap(listOf(whmcsId))
    assertFalse(result[whmcsId]!!.wasActiveInRange)
    assertEquals(result[whmcsId]!!.lastInactiveDate, TODAY)
  }

  @Test
  fun `getAccountInfoMap returns false for non-existent user`() = runBlocking {
    val whmcsId = 1

    whenever(whmcsDataRepository.getAccountProductInfoMap(listOf(whmcsId), START_DATE))
        .thenReturn(emptyMap())
    whenever(voterRegistrationManager.getUserGracePeriods()).thenReturn(emptyList())

    val result = whmcsDataService.getAccountInfoMap(listOf(whmcsId))
    assertFalse(result[whmcsId]!!.wasActiveInRange)
    assertEquals(result[whmcsId]!!.lastInactiveDate, TODAY)
  }

  @Test
  fun `getAccountInfoMap handles multiple users correctly`() = runBlocking {
    val activeUserId = 1
    val inactiveUserId = 2
    val userIds = listOf(activeUserId, inactiveUserId)

    whenever(whmcsDataRepository.getAccountProductInfoMap(userIds, START_DATE))
        .thenReturn(
            mapOf(
                activeUserId to listOf(ACTIVE_PRODUCT),
                inactiveUserId to listOf(TERMINATED_PRODUCT)))
    whenever(voterRegistrationManager.getUserGracePeriods()).thenReturn(emptyList())

    val result = whmcsDataService.getAccountInfoMap(userIds)
    assertTrue(result[activeUserId]!!.wasActiveInRange)
    assertFalse(result[inactiveUserId]!!.wasActiveInRange)
  }

  @Test
  fun `getAccountInfoMap handles product transitions correctly`() = runBlocking {
    val whmcsId = 1
    val products =
        listOf(
            PRODUCT_PARTIAL_1,
            PRODUCT_PARTIAL_2,
            PRODUCT_PARTIAL_3,
        )

    whenever(whmcsDataRepository.getAccountProductInfoMap(listOf(whmcsId), START_DATE))
        .thenReturn(mapOf(whmcsId to products))
    whenever(voterRegistrationManager.getUserGracePeriods()).thenReturn(emptyList())

    val result = whmcsDataService.getAccountInfoMap(listOf(whmcsId))
    assertTrue(result[whmcsId]!!.wasActiveInRange)
  }

  // Test grace periods
  private fun getValidGracePeriods(whmcsId: Int) =
      listOf(
          UserGracePeriod(
              whmcsUserId = whmcsId,
              username = "abc",
              startDate = START_DATE.minusDays(1),
              endDate = START_DATE.plusDays(2)),
          UserGracePeriod(
              whmcsUserId = whmcsId,
              username = "abc",
              startDate = START_DATE.plusDays(5),
              endDate = START_DATE.plusDays(8)),
          UserGracePeriod(
              whmcsUserId = whmcsId,
              username = "abc",
              startDate = START_DATE.plusDays(89),
              endDate = START_DATE.plusDays(93)))

  private fun getExpiredGracePeriod(whmcsId: Int) =
      UserGracePeriod(
          whmcsUserId = whmcsId,
          username = "abc",
          startDate = START_DATE.minusDays(10),
          endDate = START_DATE.minusDays(5))

  companion object {
    private val TODAY = LocalDate.of(2025, 1, 4)
    private val START_DATE = TODAY.minusDays(VoterRegistrationManager.MEMBER_IN_GOOD_STANDING_DAYS)

    // Test products
    private val ACTIVE_PRODUCT =
        WhmcsProductInfo(
            regDate = START_DATE.minusDays(10),
            terminationDate = null,
            domainStatus = WhmcsDomainStatus.Active)

    private val PRODUCT_PARTIAL_1 =
        WhmcsProductInfo(
            regDate = START_DATE.minusDays(10),
            terminationDate = START_DATE.plusDays(5),
            domainStatus = WhmcsDomainStatus.Terminated)

    private val PRODUCT_PARTIAL_2 =
        WhmcsProductInfo(
            regDate = START_DATE.plusDays(5),
            terminationDate = START_DATE.plusDays(7),
            domainStatus = WhmcsDomainStatus.Terminated)

    private val TERMINATED_PRODUCT =
        WhmcsProductInfo(
            regDate = START_DATE.plusDays(5),
            terminationDate = START_DATE.plusDays(7),
            domainStatus = WhmcsDomainStatus.Terminated)

    private val PRODUCT_PARTIAL_3 =
        WhmcsProductInfo(
            regDate = START_DATE.plusDays(7),
            terminationDate = null,
            domainStatus = WhmcsDomainStatus.Active)

    private val FUTURE_PRODUCT =
        WhmcsProductInfo(
            regDate = TODAY.plusDays(5),
            terminationDate = null,
            domainStatus = WhmcsDomainStatus.Active)
  }
}
