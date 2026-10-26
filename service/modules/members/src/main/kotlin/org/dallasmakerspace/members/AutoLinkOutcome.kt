package org.dallasmakerspace.members

enum class AutoLinkOutcome {
  LINKED,
  ALREADY_LINKED,
  MEMBER_HAS_OTHER_LINK,
  TALK_ACCOUNT_TAKEN,
  NOT_ENABLED_IN_AD,
  LOST_RACE,
}
