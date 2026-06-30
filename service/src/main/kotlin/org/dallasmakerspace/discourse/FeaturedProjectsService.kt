package org.dallasmakerspace.discourse

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

private const val SHOW_AND_TELL_CATEGORY_SLUG = "show-and-tell"
private const val SHOW_AND_TELL_CATEGORY_ID = 49
private const val MAX_FEATURED_PROJECTS = 10
private const val MIN_IMAGE_WIDTH = 300
private const val TOPIC_FETCH_DELAY_MS = 500L
private const val DISCOURSE_REPLY_ACTION = 5
private const val USER_ACTIONS_PAGE_SIZE = 30
private const val USER_ACTIONS_MAX_PAGES = 5

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

  // Capture each full <img ...> tag so we can inspect width and src together
  private val imgTagRegex = Regex("""<img([^>]+)>""")
  private val srcAttrRegex = Regex("""src="([^"]+)"""")
  private val largeSrcAttrRegex = Regex("""data-large-src="([^"]+)"""")
  private val widthAttrRegex = Regex("""width="(\d+)"""")

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
      outer@ while (page < USER_ACTIONS_MAX_PAGES) {
        if (page > 0) delay(TOPIC_FETCH_DELAY_MS)
        val actionsResponse =
            fetchWithRateLimitBackoff(
                dmsUsername,
                "getUserActions(offset=$offset)",
            ) {
              discourseApiClient.getUserActions(discourseUsername, DISCOURSE_REPLY_ACTION, offset)
            } ?: break

        val actions = actionsResponse.userActions
        if (actions.isEmpty()) break

        for (action in actions) {
          val actionTime = runCatching { Instant.parse(action.createdAt) }.getOrNull()
          if (actionTime != null && actionTime < twoYearsAgo) break@outer
          if (action.categoryId == SHOW_AND_TELL_CATEGORY_ID && action.postNumber != 1) {
            showAndTellActions.add(action)
          }
        }

        if (actions.size < USER_ACTIONS_PAGE_SIZE) break
        offset += USER_ACTIONS_PAGE_SIZE
        page++
      }

      log.debug(
          "[live/$dmsUsername] Found ${showAndTellActions.size} show-and-tell post(s) across ${showAndTellActions.map { it.topicId }.distinct().size} topic(s)"
      )

      if (showAndTellActions.isEmpty()) {
        memberLiveCache[dmsUsername] = Clock.System.now() to emptyList()
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
            "[live/$dmsUsername] Topic $topicId ('$title'): fetching ${postIds.size} post(s) by ID: $postIds"
        )

        val topicPosts =
            fetchWithRateLimitBackoff(dmsUsername, "getTopicPostsByIds($topicId)") {
              discourseApiClient.getTopicPostsByIds(topicId, postIds)
            } ?: continue

        for (post in topicPosts.postStream.posts) {
          val imageUrl = extractLargeImageUrl(post.cooked)
          if (imageUrl == null) {
            log.debug(
                "[live/$dmsUsername] Topic $topicId post ${post.id} (postNumber=${post.postNumber}): " +
                    "no qualifying image (requires width>=${MIN_IMAGE_WIDTH}px) — skipping"
            )
            continue
          }
          log.debug(
              "[live/$dmsUsername] Topic $topicId post ${post.id}: adding project with image $imageUrl"
          )
          projects.add(
              FeaturedProject(
                  topicId = topicId,
                  postId = post.id,
                  title = title,
                  imageUrl = imageUrl,
                  memberUsername = member.username,
                  memberDisplayName = member.displayName,
                  memberAvatarUrl = resolveAvatarUrl(member.discourseAvatarUrl),
                  likeCount = post.resolvedLikeCount,
                  discourseTopicUrl =
                      "https://talk.dallasmakerspace.org/t/$slug/$topicId/${post.postNumber}",
                  createdAt = post.createdAt,
              )
          )
        }
      }

      log.debug("[live/$dmsUsername] Refresh complete: ${projects.size} project(s) found")
      val sorted = projects.sortedByDescending {
        runCatching { Instant.parse(it.createdAt) }.getOrNull()
      }
      memberLiveCache[dmsUsername] = Clock.System.now() to sorted
    } catch (e: Exception) {
      log.error("Failed to refresh projects for member $dmsUsername", e)
    }
  }

  // Runs [block], returning null on failure. On a 429 response, waits the time Discourse
  // requests before returning so the next call in the loop doesn't immediately re-trigger it.
  @Suppress("TooGenericExceptionCaught")
  private suspend fun <T> fetchWithRateLimitBackoff(
      dmsUsername: String,
      callName: String,
      block: suspend () -> T,
  ): T? {
    return try {
      block()
    } catch (e: Exception) {
      val msg = e.message ?: ""
      val waitSecondsMatch = Regex(""""wait_seconds"\s*:\s*(\d+)""").find(msg)
      if (msg.contains("429") && waitSecondsMatch != null) {
        val waitMs = (waitSecondsMatch.groupValues[1].toLongOrNull() ?: 30L) * 1000L + 2_000L
        log.warn(
            "[live/$dmsUsername] Rate limited on $callName — waiting ${waitMs}ms before continuing"
        )
        delay(waitMs)
      } else {
        log.warn("[live/$dmsUsername] Failed $callName", e)
      }
      null
    }
  }

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
   * Finds the first large image URL from Discourse's rendered HTML. Prefers `data-large-src`
   * (full-resolution Discourse upload) over `src`. Skips images whose `width` attribute is below
   * [MIN_IMAGE_WIDTH] (filters out emojis, avatars, inline icons).
   */
  private fun extractLargeImageUrl(cooked: String): String? {
    for (match in imgTagRegex.findAll(cooked)) {
      val attrs = match.groupValues[1]
      val width = widthAttrRegex.find(attrs)?.groupValues?.get(1)?.toIntOrNull()
      if (width != null && width < MIN_IMAGE_WIDTH) continue
      val url =
          largeSrcAttrRegex.find(attrs)?.groupValues?.get(1)
              ?: srcAttrRegex.find(attrs)?.groupValues?.get(1)
              ?: continue
      if (url.isBlank()) continue
      return when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://talk.dallasmakerspace.org$url"
        else -> url
      }
    }
    return null
  }

  private fun resolveAvatarUrl(raw: String?): String? =
      raw?.let { url ->
            when {
              url.startsWith("//") -> "https:$url"
              url.startsWith("/") -> "https://talk.dallasmakerspace.org$url"
              else -> url
            }
          }
          ?.replace("{size}", "40")

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
                        SHOW_AND_TELL_CATEGORY_SLUG,
                        SHOW_AND_TELL_CATEGORY_ID,
                        page = 0,
                    )
                  }
                      .getOrNull()
                },
                async {
                  runCatching {
                    discourseApiClient.getCategoryTopics(
                        SHOW_AND_TELL_CATEGORY_SLUG,
                        SHOW_AND_TELL_CATEGORY_ID,
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

      val matchingTopics = allTopics.filter { topic ->
        topic.title.startsWith("Show and Tell") &&
            runCatching { Instant.parse(topic.createdAt) }.getOrNull()?.let { it >= topicCutoff } ==
                true
      }

      if (matchingTopics.isEmpty()) {
        log.info("No Show and Tell topics found in past 2 years")
        return
      }

      log.debug("Found ${matchingTopics.size} Show and Tell topics in past 2 years")

      // Fetch topic post streams sequentially with a delay to avoid Discourse 429 rate limiting
      val topicDetails = buildList {
        for ((index, topic) in matchingTopics.withIndex()) {
          if (index > 0) delay(TOPIC_FETCH_DELAY_MS)
          runCatching { discourseApiClient.getTopicPosts(topic.id) }
              .onFailure { log.warn("Failed to fetch posts for topic ${topic.id}", it) }
              .getOrNull()
              ?.let { details -> add(topic to details) }
        }
      }

      // Collect all image-containing posts (exclude intro post #1)
      val allCandidatePosts = topicDetails.flatMap { (topic, details) ->
        details.postStream.posts
            .filter { post -> post.postNumber != 1 }
            .mapNotNull { post ->
              val imageUrl = extractLargeImageUrl(post.cooked) ?: return@mapNotNull null
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
                memberAvatarUrl = resolveAvatarUrl(member.discourseAvatarUrl),
                likeCount = post.resolvedLikeCount,
                discourseTopicUrl =
                    "https://talk.dallasmakerspace.org/t/${topic.slug}/${topic.id}/${post.postNumber}",
                createdAt = post.createdAt,
            )

        memberProjectsMap.getOrPut(member.username) { mutableListOf() }.add(project)

        val postTime = runCatching { Instant.parse(post.createdAt) }.getOrNull()
        if (postTime != null && postTime >= globalPostCutoff) {
          globalCandidates.add(project)
        }
      }

      val newMemberProjects = memberProjectsMap.mapValues { (_, list) ->
        list.sortedByDescending { runCatching { Instant.parse(it.createdAt) }.getOrNull() }
      }
      val newGlobalProjects =
          globalCandidates.sortedByDescending { it.likeCount }.take(MAX_FEATURED_PROJECTS)

      cachedProjects.set(newGlobalProjects)
      cachedMemberProjects.set(newMemberProjects)
      lastFetchTime.set(Clock.System.now())
      log.info(
          "Featured projects cache updated: ${newGlobalProjects.size} global, " +
              "${newMemberProjects.size} members cached"
      )
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
}
