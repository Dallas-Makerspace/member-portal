package org.dallasmakerspace.server.memberservice

/** One page of a committee's followers (newest first) and the total across all pages. */
data class CommitteeFollowers(val total: Int, val usernames: List<String>)
