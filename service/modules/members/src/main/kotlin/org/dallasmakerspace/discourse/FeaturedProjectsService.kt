package org.dallasmakerspace.discourse

import io.ktor.client.plugins.HttpRequestTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.MemberRepository
import org.dallasmakerspace.models.FeaturedProject
import org.dallasmakerspace.models.displayAvatarUrl
import org.dallasmakerspace.models.normalizeAvatarUrl

private const val MAX_FEATURED_PROJECTS = 10
private const val TOPIC_FETCH_DELAY_MS = 500L
private const val DISCOURSE_REPLY_ACTION = 5
private const val USER_ACTIONS_PAGE_SIZE = 30
private const val USER_ACTIONS_MAX_PAGES = 5
private const val GLOBAL_POST_IDS_CHUNK_SIZE = 50
private const val MAX_FETCH_ATTEMPTS = 3
private const val FEATURED_PROJECT_AVATAR_SIZE = 40
private const val TIMEOUT_RETRY_DELAY_MS = 3_000L

@Singleton
class FeaturedProjectsService
@Inject
constructor(
    private val discourseApiClient: IDiscourseApiClient,
    private val memberRepository: MemberRepository,
    loggerFactory: LoggerFactory,
) {
  private val log = loggerFactory.create(javaClass)

  private val cacheDuration = 1.days
  private val memberLiveCacheDuration = 1.days
  private val topicLookbackDays = 730L // 2 years — covers all member photo history
  private val globalPostLookbackDays = 30L // window for home-page global top-10

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val cachedProjects = AtomicReference<List<FeaturedProject>>(emptyList())
  private val cachedMemberProjects = AtomicReference<Map<String, List<FeaturedProject>>>(emptyMap())
  private val lastFetchTime = AtomicReference<Instant?>(null)
  private val isRefreshing = AtomicBoolean(false)
  private val memberLiveCache = ConcurrentHashMap<String, Pair<Instant, List<FeaturedProject>>>()
  private val refreshingMembers = ConcurrentHashMap.newKeySet<String>()

  // Last successfully fetched post data per topic, used as a fallback when a fresh fetch fails
  // after retries — so a transient Discourse hiccup degrades to "yesterday's data" for that
  // topic instead of dropping all of its images from the cache.
  private val topicSnapshotCache = ConcurrentHashMap<Int, DiscourseTopicDetails>()

  /** Returns the global top-10 featured projects (home page). Always instant — reads from cache. */
  fun getFeaturedProjects(): List<FeaturedProject> {
    triggerRefreshIfStale()
    return cachedProjects.get()
  }

  /**
   * Returns all featured projects for a specific DMS member (profile page). Always instant — reads
   * from the per-member cache populated by the same background refresh as [getFeaturedProjects].
   */
  fun getFeaturedProjectsForMember(dmsUsername: String): List<FeaturedProject> {
    triggerRefreshIfStale()
    return cachedMemberProjects.get()[dmsUsername] ?: emptyList()
  }

  /**
   * Returns cached projects for a specific member and triggers a background refresh if the cache is
   * stale (older than [memberLiveCacheDuration]). Always returns instantly from cache. The first
   * call for a member returns empty until the background fetch completes; subsequent calls within
   * the cache window return the populated list.
   */
  suspend fun getFeaturedProjectsForMemberLive(dmsUsername: String): List<FeaturedProject> {
    val cached = memberLiveCache[dmsUsername]
    return when {
      cached == null -> {
        // No cache yet — one coroutine does the blocking refresh; concurrent callers fall back
        // to the global cache so they don't all hammer Discourse simultaneously.
        if (refreshingMembers.add(dmsUsername)) {
          try {
            refreshMemberProjects(dmsUsername)
          } finally {
            refreshingMembers.remove(dmsUsername)
          }
        }
        memberLiveCache[dmsUsername]?.second
            ?: cachedMemberProjects.get()[dmsUsername]
            ?: emptyList()
      }
      (Clock.System.now() - cached.first) >= memberLiveCacheDuration -> {
        // Stale — return existing data immediately; one background coroutine does the refresh.
        if (refreshingMembers.add(dmsUsername)) {
          serviceScope.launch {
            try {
              refreshMemberProjects(dmsUsername)
            } finally {
              refreshingMembers.remove(dmsUsername)
            }
          }
        }
        cached.second
      }
      else -> cached.second
    }
  }

  @Suppress("TooGenericExceptionCaught")
  private suspend fun refreshMemberProjects(dmsUsername: String) {
    try {
      val member =
          memberRepository.getMembersByUsernames(listOf(dmsUsername))[dmsUsername]
              ?: run {
                log.warn("Member not found in DB for live project fetch: $dmsUsername")
                return
              }

      val discourseUsername =
          member.discourseUsername
              ?: run {
                log.debug("No Discourse username for member: $dmsUsername")
                return
              }

      // Fetch all replies by this member and filter to the show-and-tell category.
      // Paginate until exhausted, the oldest action exceeds 2 years, or we hit the page cap.
      val showAndTellActions = mutableListOf<DiscourseUserAction>()
      val twoYearsAgo = Clock.System.now().minus(topicLookbackDays.days)
      var offset = 0
      var page = 0
      // Tracks whether any API call failed, so a transient failure isn't mistaken for a
      // confirmed "this member has no show-and-tell posts" result and cached as such below.
      var hadFetchFailure = false
      outer@ while (page < USER_ACTIONS_MAX_PAGES) {
        if (page > 0) delay(TOPIC_FETCH_DELAY_MS)
        val actionsResponse =
            fetchWithRetry(
                "live/$dmsUsername",
                "getUserActions(offset=$offset)",
            ) {
              discourseApiClient.getUserActions(discourseUsername, DISCOURSE_REPLY_ACTION, offset)
            }
        if (actionsResponse == null) {
          hadFetchFailure = true
          break
        }

        val actions = actionsResponse.userActions
        if (actions.isEmpty()) break

        for (action in actions) {
          val actionTime = runCatching { Instant.parse(action.createdAt) }.getOrNull()
          if (actionTime != null && actionTime < twoYearsAgo) break@outer
          if (action.categoryId == ShowAndTellRules.CATEGORY_ID &&
              ShowAndTellRules.isProjectPost(action.postNumber)) {
            showAndTellActions.add(action)
          }
        }

        if (actions.size < USER_ACTIONS_PAGE_SIZE) break
        offset += USER_ACTIONS_PAGE_SIZE
        page++
      }

      log.debug(
          "[live/$dmsUsername] Found ${showAndTellActions.size} show-and-tell post(s) across ${showAndTellActions.map { it.topicId }.distinct().size} topic(s)")

      if (showAndTellActions.isEmpty()) {
        if (hadFetchFailure) {
          log.warn(
              "[live/$dmsUsername] Not caching empty result — a fetch failure occurred while " +
                  "checking for show-and-tell posts, will retry on next request")
        } else {
          memberLiveCache[dmsUsername] = Clock.System.now() to emptyList()
        }
        return
      }

      // Group post IDs by topic, then fetch only those specific posts for their cooked content.
      val actionsByTopicId = showAndTellActions.groupBy { it.topicId }

      val projects = mutableListOf<FeaturedProject>()
      for ((index, entry) in actionsByTopicId.entries.withIndex()) {
        if (index > 0) delay(TOPIC_FETCH_DELAY_MS)
        val (topicId, actions) = entry
        val postIds = actions.map { it.postId }
        val title = actions.first().title
        val slug = actions.first().slug

        log.debug(
            "[live/$dmsUsername] Topic $topicId ('$title'): fetching ${postIds.size} post(s) by ID: $postIds")

        val topicPosts =
            fetchWithRetry("live/$dmsUsername", "getTopicPostsByIds($topicId)") {
              discourseApiClient.getTopicPostsByIds(topicId, postIds)
            }
        if (topicPosts == null) {
          hadFetchFailure = true
          continue
        }

        for (post in topicPosts.postStream.posts) {
          val imageUrl = ShowAndTellRules.extractLargeImageUrl(post.cooked)
          if (imageUrl == null) {
            log.debug(
                "[live/$dmsUsername] Topic $topicId post ${post.id} (postNumber=${post.postNumber}): " +
                    "no qualifying image (requires a large image) — skipping")
            continue
          }
          log.debug(
              "[live/$dmsUsername] Topic $topicId post ${post.id}: adding project with image $imageUrl")
          projects.add(
              FeaturedProject(
                  topicId = topicId,
                  postId = post.id,
                  title = title,
                  imageUrl = imageUrl,
                  memberUsername = member.username,
                  memberDisplayName = member.displayName,
                  memberAvatarUrl =
                      normalizeAvatarUrl(
                          member.displayAvatarUrl(), size = FEATURED_PROJECT_AVATAR_SIZE),
                  likeCount = post.resolvedLikeCount,
                  discourseTopicUrl = ShowAndTellRules.postUrl(slug, topicId, post.postNumber),
                  createdAt = post.createdAt,
              ))
        }
      }

      log.debug("[live/$dmsUsername] Refresh complete: ${projects.size} project(s) found")
      if (projects.isEmpty() && hadFetchFailure) {
        log.warn(
            "[live/$dmsUsername] Not caching empty result — all post fetches failed, will retry " +
                "on next request")
        return
      }
      val sorted =
          projects.sortedByDescending { runCatching { Instant.parse(it.createdAt) }.getOrNull() }
      memberLiveCache[dmsUsername] = Clock.System.now() to sorted
    } catch (e: Exception) {
      log.error("Failed to refresh projects for member $dmsUsername", e)
    }
  }

  /**
   * Runs [block], retrying on transient failures up to [MAX_FETCH_ATTEMPTS] total attempts: waits
   * the time Discourse requests on a 429, or a fixed delay on a request timeout, before retrying
   * the same call. Returns null if every attempt fails.
   */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun <T> fetchWithRetry(
      contextLabel: String,
      callName: String,
      block: suspend () -> T,
  ): T? {
    for (attempt in 1..MAX_FETCH_ATTEMPTS) {
      try {
        return block()
      } catch (e: Exception) {
        val isLastAttempt = attempt == MAX_FETCH_ATTEMPTS
        val msg = e.message ?: ""
        val waitSecondsMatch = Regex(""""wait_seconds"\s*:\s*(\d+)""").find(msg)
        when {
          msg.contains("429") && waitSecondsMatch != null -> {
            val waitMs = (waitSecondsMatch.groupValues[1].toLongOrNull() ?: 30L) * 1000L + 2_000L
            log.warn(
                "[$contextLabel] Rate limited on $callName (attempt $attempt/$MAX_FETCH_ATTEMPTS)" +
                    " — waiting ${waitMs}ms")
            delay(waitMs)
          }
          isTimeout(e) && !isLastAttempt -> {
            log.warn(
                "[$contextLabel] Timeout on $callName (attempt $attempt/$MAX_FETCH_ATTEMPTS) — " +
                    "retrying in ${TIMEOUT_RETRY_DELAY_MS}ms")
            delay(TIMEOUT_RETRY_DELAY_MS)
          }
          else -> {
            log.warn("[$contextLabel] Failed $callName (attempt $attempt/$MAX_FETCH_ATTEMPTS)", e)
            if (isLastAttempt) return null
          }
        }
      }
    }
    return null
  }

  /**
   * Drops [dmsUsername]'s cached profile projects so the next profile view refetches them (e.g.
   * after a webhook reports a new Show & Tell post).
   */
  fun invalidateMember(dmsUsername: String) {
    memberLiveCache.remove(dmsUsername)
  }

  private fun isTimeout(e: Throwable): Boolean =
      e is HttpRequestTimeoutException || e.cause is HttpRequestTimeoutException

  private fun triggerRefreshIfStale() {
    val lastFetch = lastFetchTime.get()
    val isStale = lastFetch == null || (Clock.System.now() - lastFetch) >= cacheDuration
    if (isStale && isRefreshing.compareAndSet(false, true)) {
      serviceScope.launch {
        try {
          doRefresh()
        } finally {
          isRefreshing.set(false)
        }
      }
    }
  }

  /**
   * Single background pass that fetches 2 years of Show & Tell posts and populates both caches:
   * - [cachedProjects] — global top-[MAX_FEATURED_PROJECTS] from the last 30 days (home page)
   * - [cachedMemberProjects] — all image posts per member over 2 years (profile pages)
   */
  @Suppress("TooGenericExceptionCaught")
  private suspend fun doRefresh() {
    try {
      log.info("Refreshing featured projects cache (2-year window)")

      val now = Clock.System.now()
      val topicCutoff = now.minus(topicLookbackDays.days)
      val globalPostCutoff = now.minus(globalPostLookbackDays.days)

      // Fetch pages 0 and 1 in parallel — covers ~2 years of monthly Show & Tell topics
      val allTopics = coroutineScope {
        listOf(
                async {
                  runCatching {
                        discourseApiClient.getCategoryTopics(
                            ShowAndTellRules.CATEGORY_SLUG,
                            ShowAndTellRules.CATEGORY_ID,
                            page = 0,
                        )
                      }
                      .getOrNull()
                },
                async {
                  runCatching {
                        discourseApiClient.getCategoryTopics(
                            ShowAndTellRules.CATEGORY_SLUG,
                            ShowAndTellRules.CATEGORY_ID,
                            page = 1,
                        )
                      }
                      .getOrNull()
                },
            )
            .mapNotNull { it.await() }
            .flatMap { it.topicList.topics }
            .distinctBy { it.id }
      }

      val matchingTopics =
          allTopics.filter { topic ->
            ShowAndTellRules.isProjectTopic(topic.title) &&
                runCatching { Instant.parse(topic.createdAt) }
                    .getOrNull()
                    ?.let { it >= topicCutoff } == true
          }

      if (matchingTopics.isEmpty()) {
        log.info("No Show and Tell topics found in past 2 years")
        return
      }

      log.debug("Found ${matchingTopics.size} Show and Tell topics in past 2 years")

      // Fetch topic post streams sequentially with a delay to avoid Discourse 429 rate limiting.
      // After the initial fetch (which Discourse caps at ~20 posts), paginate the remaining post
      // IDs from the stream array in chunks to retrieve the full thread.
      val topicDetails = buildList {
        for ((index, topic) in matchingTopics.withIndex()) {
          if (index > 0) delay(TOPIC_FETCH_DELAY_MS)
          val details = fetchTopicDetailsWithFallback(topic) ?: continue
          add(topic to details)
        }
      }

      // Collect all image-containing posts (exclude intro post #1)
      val allCandidatePosts =
          topicDetails.flatMap { (topic, details) ->
            details.postStream.posts
                .filter { post -> ShowAndTellRules.isProjectPost(post.postNumber) }
                .mapNotNull { post ->
                  val imageUrl =
                      ShowAndTellRules.extractLargeImageUrl(post.cooked) ?: return@mapNotNull null
                  Triple(topic, post, imageUrl)
                }
          }

      if (allCandidatePosts.isEmpty()) {
        log.info("No image-containing posts found in Show and Tell topics")
        return
      }

      // Batch-lookup all Discourse usernames → DMS members in one DB call
      val discourseUsernames = allCandidatePosts.map { (_, post, _) -> post.username }.distinct()
      val membersByDiscourseUsername =
          memberRepository.getMembersByDiscourseUsernames(discourseUsernames)

      // Build per-member map and global candidate list in a single pass
      val memberProjectsMap = mutableMapOf<String, MutableList<FeaturedProject>>()
      val globalCandidates = mutableListOf<FeaturedProject>()

      for ((topic, post, imageUrl) in allCandidatePosts) {
        val member = membersByDiscourseUsername[post.username] ?: continue
        val project =
            FeaturedProject(
                topicId = topic.id,
                postId = post.id,
                title = topic.title,
                imageUrl = imageUrl,
                memberUsername = member.username,
                memberDisplayName = member.displayName,
                memberAvatarUrl =
                    normalizeAvatarUrl(
                        member.displayAvatarUrl(), size = FEATURED_PROJECT_AVATAR_SIZE),
                likeCount = post.resolvedLikeCount,
                discourseTopicUrl = ShowAndTellRules.postUrl(topic.slug, topic.id, post.postNumber),
                createdAt = post.createdAt,
            )

        memberProjectsMap.getOrPut(member.username) { mutableListOf() }.add(project)

        val postTime = runCatching { Instant.parse(post.createdAt) }.getOrNull()
        if (postTime != null && postTime >= globalPostCutoff) {
          globalCandidates.add(project)
        }
      }

      val newMemberProjects =
          memberProjectsMap.mapValues { (_, list) ->
            list.sortedByDescending { runCatching { Instant.parse(it.createdAt) }.getOrNull() }
          }
      val newGlobalProjects =
          globalCandidates.sortedByDescending { it.likeCount }.take(MAX_FEATURED_PROJECTS)

      cachedProjects.set(newGlobalProjects)
      cachedMemberProjects.set(newMemberProjects)
      lastFetchTime.set(Clock.System.now())
      log.info(
          "Featured projects cache updated: ${newGlobalProjects.size} global, " +
              "${newMemberProjects.size} members cached")
      val top5 =
          newMemberProjects.entries
              .sortedByDescending { it.value.size }
              .take(5)
              .joinToString(", ") { "${it.key}(${it.value.size})" }
      log.debug("Top 5 members by photo count: $top5")
    } catch (e: Exception) {
      log.error("Failed to refresh featured projects cache; keeping stale data", e)
    }
  }

  /**
   * Fetches full post details for [topic] (initial page plus any paginated remainder), retrying
   * transient failures via [fetchWithRetry]. If the initial fetch still fails after retries,
   * falls back to the last successfully fetched snapshot of this topic (if any) rather than
   * dropping the topic's images entirely — so a transient Discourse hiccup degrades to stale data
   * for that one topic instead of losing it from the cache for a full day. Returns null only if
   * there's no fallback snapshot available (e.g. the topic has never been fetched successfully).
   */
  private suspend fun fetchTopicDetailsWithFallback(topic: DiscourseCategoryTopic): DiscourseTopicDetails? {
    val initialDetails = fetchWithRetry("global", "getTopicPosts(${topic.id})") {
      discourseApiClient.getTopicPosts(topic.id)
    }

    if (initialDetails == null) {
      val fallback = topicSnapshotCache[topic.id]
      if (fallback != null) {
        log.warn(
            "Using last-known-good snapshot for topic ${topic.id} (fresh fetch failed after " +
                "$MAX_FETCH_ATTEMPTS attempts)")
      } else {
        log.warn(
            "No fallback snapshot for topic ${topic.id}; skipping it this refresh " +
                "(fresh fetch failed after $MAX_FETCH_ATTEMPTS attempts)")
      }
      return fallback
    }

    val fetchedIds = initialDetails.postStream.posts.map { it.id }.toSet()
    val remainingIds = initialDetails.postStream.stream.filter { it !in fetchedIds }

    val merged =
        if (remainingIds.isEmpty()) {
          initialDetails
        } else {
          val extraPosts = mutableListOf<DiscourseTopicPost>()
          for (chunk in remainingIds.chunked(GLOBAL_POST_IDS_CHUNK_SIZE)) {
            delay(TOPIC_FETCH_DELAY_MS)
            val chunkPosts =
                fetchWithRetry("global", "getTopicPostsByIds(${topic.id})") {
                  discourseApiClient.getTopicPostsByIds(topic.id, chunk)
                }
            if (chunkPosts != null) {
              extraPosts.addAll(chunkPosts.postStream.posts)
            } else {
              log.warn(
                  "Failed to fetch post chunk for topic ${topic.id} after $MAX_FETCH_ATTEMPTS " +
                      "attempts: ${chunk.size} IDs — proceeding with partial data")
            }
          }
          initialDetails.copy(
              postStream =
                  initialDetails.postStream.copy(
                      posts = initialDetails.postStream.posts + extraPosts))
        }

    topicSnapshotCache[topic.id] = merged
    return merged
  }
}
