package org.dallasmakerspace

import org.dallasmakerspace.models.DMSMember

object TestUtils {
  private var id = 100

  fun generateTestDMSMember(): DMSMember {
    id += 1
    return DMSMember(id, "user$id", firstName = "first$id", discourseUsername = "discourseUser$id")
  }

  fun generateTestDMSMembers(count: Int): List<DMSMember> {
    return (1..count).map { generateTestDMSMember() }
  }
}
