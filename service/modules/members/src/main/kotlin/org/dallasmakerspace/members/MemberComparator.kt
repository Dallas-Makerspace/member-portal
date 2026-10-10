package org.dallasmakerspace.members

import io.ktor.util.logging.*
import javax.inject.Inject
import org.dallasmakerspace.activedirectory.ADUser
import org.dallasmakerspace.members.observers.IMemberPropChangeObserver
import org.dallasmakerspace.models.DMSMember

class MemberComparator
@Inject
constructor(
    private val memberRepository: MemberRepository,
    private val memberPropChangeObservers: Set<@JvmSuppressWildcards IMemberPropChangeObserver>
) {

  suspend fun process(
      memberList: List<Pair<DMSMember, ADUser?>>,
      log: Logger,
      isRunningInShadowMode: Boolean
  ): Pair<Int, Int> {

    log.debug(
        "MemberComparator.process: memberList=${memberList.joinToString(separator = ",") { it.first.username }}")

    val disabledMembers =
        memberList.filter { (dbMember, adUser) -> dbMember.enabled && adUser?.enabled == false }

    if (disabledMembers.isNotEmpty()) {
      log.info(
          "Found newly disabled members (${disabledMembers.size}): " +
              disabledMembers.joinToString(separator = ",") { it.first.username })
    }

    val enabledMembers =
        memberList.filter { (dbMember, adUser) -> adUser?.enabled == true && !dbMember.enabled }

    if (enabledMembers.isNotEmpty()) {
      log.info(
          "Found newly enabled members (${enabledMembers.size}): " +
              enabledMembers.joinToString(separator = ",") { it.first.username })
    }

    if (isRunningInShadowMode) {
      log.debug("Running in shadow mode, skipping observer.onMemberPropChange")
      return Pair(disabledMembers.size, enabledMembers.size)
    }
    // Notify observers of any changes
    memberPropChangeObservers.forEach { observer ->
      // Notify for disabled members
      val resultDisabled =
          observer.onMemberPropChange(
              "enabled",
              oldValue = true,
              newValue = false,
              affectedMembers = disabledMembers.map { it.first })
      if (resultDisabled) {
        val updatedMembers = disabledMembers.map { it.first }
        // Set .enabled to false for all members in the list
        updatedMembers.forEach { it.enabled = false }
        log.debug(
            "Updating disabled members: ${updatedMembers.joinToString(separator = ",") { it.username }}")
        memberRepository.updateMembers(updatedMembers)
      }

      // Notify for enabled members
      val resultEnabled =
          observer.onMemberPropChange(
              "enabled",
              oldValue = false,
              newValue = true,
              affectedMembers = enabledMembers.map { it.first })
      if (resultEnabled) {
        val updatedMembers = enabledMembers.map { it.first }
        // Set .enabled to false for all members in the list
        updatedMembers.forEach { it.enabled = true }
        log.debug(
            "Updating enabled members: ${updatedMembers.joinToString(separator = ",") { it.username }}")
        memberRepository.updateMembers(updatedMembers)
      }
    }
    return Pair(disabledMembers.size, enabledMembers.size)
  }
}
