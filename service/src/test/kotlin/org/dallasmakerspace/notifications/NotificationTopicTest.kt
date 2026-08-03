package org.dallasmakerspace.notifications

import kotlin.test.assertEquals
import org.junit.Test

class NotificationTopicTest {

  @Test
  fun `only ACCOUNT allows inactive members by default`() {
    assertEquals(true, NotificationTopic.ACCOUNT.allowInactiveMembers)
    assertEquals(false, NotificationTopic.EVENT_ANNOUNCEMENT.allowInactiveMembers)
    assertEquals(false, NotificationTopic.SYSTEM.allowInactiveMembers)
  }
}
