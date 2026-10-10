package org.dallasmakerspace.members.observers

import org.dallasmakerspace.models.DMSMember

interface IMemberPropChangeObserver {
  suspend fun onMemberPropChange(
      propName: String,
      oldValue: Any?,
      newValue: Any?,
      affectedMembers: List<DMSMember>
  ): Boolean
}
