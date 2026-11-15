package org.dallasmakerspace.dashboards

import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/** The small calculations behind the rendered dashboard, kept pure so they can be tested. */
object DashboardMath {
  const val TREND_WEEKS = 12

  /**
   * Group size at the end of each of the past [weeks] weeks (oldest first, [weeks] + 1 points, the
   * last one [current]), reconstructed by undoing [changes] (UTC time, +1 added / -1 removed).
   * Group history only goes back as far as it was recorded, so early points are approximate.
   */
  fun weeklyTrend(
      current: Int,
      changes: List<Pair<LocalDateTime, Int>>,
      now: LocalDateTime,
      weeks: Int = TREND_WEEKS,
  ): List<Int> {
    val points = ArrayList<Int>(weeks + 1)
    var value = current
    points += value
    for (k in 0 until weeks) {
      val end = now.minusWeeks(k.toLong())
      val start = now.minusWeeks(k + 1L)
      val net = changes.filter { it.first > start && it.first <= end }.sumOf { it.second }
      value = (value - net).coerceAtLeast(0)
      points += value
    }
    return points.reversed()
  }

  /** The soonest of [candidates] after [nowMs], never later than [nowMs] + [capMs]. */
  fun nextChangeAt(nowMs: Long, candidates: List<Long>, capMs: Long): Long =
      (candidates.filter { it > nowMs } + (nowMs + capMs)).min()
}

/** A small expiring map for values that are costly to fetch and fine to show a little stale. */
internal class TtlCache<K : Any, V>(
    private val ttl: Duration,
    private val now: () -> Long = System::currentTimeMillis,
) {
  private class Entry<V>(val value: V, val expiresAt: Long)

  private val map = ConcurrentHashMap<K, Entry<V>>()

  suspend fun getOrLoad(key: K, load: suspend () -> V): V {
    val cached = map[key]
    if (cached != null && cached.expiresAt > now()) return cached.value
    val value = load()
    map[key] = Entry(value, now() + ttl.toMillis())
    return value
  }

  fun clear() = map.clear()
}
