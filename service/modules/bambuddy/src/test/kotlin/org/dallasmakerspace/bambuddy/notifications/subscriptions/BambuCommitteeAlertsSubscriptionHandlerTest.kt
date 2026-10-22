package org.dallasmakerspace.bambuddy.notifications.subscriptions

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.dallasmakerspace.bambuddy.notifications.db.BambuCommitteeSubscriptionsRepository
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.notifications.topics.BambuCommitteeAlertsTopic
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BambuCommitteeAlertsSubscriptionHandlerTest {

  private lateinit var repository: BambuCommitteeSubscriptionsRepository
  private lateinit var handler: BambuCommitteeAlertsSubscriptionHandler

  @Before
  fun setUp() {
    repository = mock()
    handler = BambuCommitteeAlertsSubscriptionHandler(repository)
  }

  @Test
  fun `the handler is bound to the committee alerts topic, its flag, and no args`() {
    assertEquals(BambuCommitteeAlertsTopic, handler.topic)
    assertEquals("bambu.committee-alerts", handler.topic.key)
    assertEquals(ConfigRegistry.BAMBU_NOTIFICATIONS_ENABLED, handler.featureFlag)
    assertTrue(handler.argNames.isEmpty())
  }

  @Test
  fun `subscribe, unsubscribe and status delegate to the repository`() = runBlocking {
    whenever(repository.isSubscribed("jdoe")).thenReturn(true, false)

    handler.subscribe("jdoe", emptyMap())
    assertTrue(handler.isSubscribed("jdoe", emptyMap()))
    handler.unsubscribe("jdoe", emptyMap())
    assertTrue(!handler.isSubscribed("jdoe", emptyMap()))

    verify(repository).subscribe("jdoe")
    verify(repository).unsubscribe("jdoe")
  }

  @Test
  fun `copy carries the notify-me labels, hint and confirm`() {
    val copy = handler.copy(emptyMap())

    assertEquals("Notify me", copy.subscribeLabel)
    assertEquals("Notifying", copy.subscribedLabel)
    assertEquals("Stop notifying", copy.unsubscribeLabel)
    assertTrue(copy.hint!!.contains("offline"))
    val confirm = assertNotNull(copy.subscribeConfirm)
    assertTrue(confirm.message.contains("5 minutes"))
  }
}
