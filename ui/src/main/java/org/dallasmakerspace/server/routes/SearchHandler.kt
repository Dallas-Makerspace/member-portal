package org.dallasmakerspace.server.routes

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberService
import org.dallasmakerspace.server.search.SearchRecords

/** `GET /search?q=&alt=`: server-side results for the search dialog (upcoming classes for now). */
class SearchHandler
@Inject
constructor(
    loggerFactory: LoggerFactory,
    userInfoProvider: UserInfoProvider,
    private val memberService: MemberService,
) : AuthenticatedHandler(loggerFactory, userInfoProvider) {

  override suspend fun handleAuthenticated(call: ApplicationCall) {
    val keywords =
        keywords(call.request.queryParameters["q"], call.request.queryParameters.getAll("alt").orEmpty())
    if (keywords.isEmpty()) {
      call.respond(mapOf("data" to emptyList<Any>()))
      return
    }
    val cacheKey = keywords.joinToString("\u0000")
    val now = System.currentTimeMillis()
    val cached = cache[cacheKey]?.takeIf { now - it.first < CACHE_MS }?.second
    val data =
        cached
            ?: memberService
                .getUpcomingEventsByKeywords(keywords, session.sessionId)
                .map(SearchRecords::event)
                .also {
                  if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
                  cache[cacheKey] = now to it
                }
    call.respond(mapOf("data" to data))
  }

  companion object {
    const val MIN_QUERY_LENGTH = 2
    const val MAX_QUERY_LENGTH = 100
    const val MAX_ALT_TERMS = 10
    private const val MAX_CACHE_ENTRIES = 500
    private val CACHE_MS = TimeUnit.SECONDS.toMillis(60)
    private val cache = ConcurrentHashMap<String, Pair<Long, List<Map<String, Any?>>>>()

    /** The query first, then distinct alternative terms; empty when the query is too short. */
    fun keywords(q: String?, alt: List<String>): List<String> {
      val query = q.orEmpty().trim().lowercase().take(MAX_QUERY_LENGTH)
      if (query.length < MIN_QUERY_LENGTH) return emptyList()
      val alts =
          alt.asSequence()
              .map { it.trim().lowercase().take(MAX_QUERY_LENGTH) }
              .filter { it.length >= MIN_QUERY_LENGTH && it != query }
              .distinct()
              .take(MAX_ALT_TERMS)
      return listOf(query) + alts
    }
  }
}
