package org.dallasmakerspace.activitybot.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import javax.inject.Provider
import javax.inject.Singleton
import org.dallasmakerspace.activitybot.ActivityAccess
import org.dallasmakerspace.activitybot.DoorSwipeSearch
import org.dallasmakerspace.activitybot.InterlockSessionSearch
import org.dallasmakerspace.activitybot.MockSessionSearch
import org.dallasmakerspace.activitybot.MockSwipeSearch
import org.dallasmakerspace.activitybot.SessionSearch
import org.dallasmakerspace.activitybot.SwipeSearch
import org.dallasmakerspace.activitybot.tools.ActivityBotPrompt
import org.dallasmakerspace.activitybot.tools.AreaPresenceTool
import org.dallasmakerspace.activitybot.tools.BadgeSwipesTool
import org.dallasmakerspace.activitybot.tools.InterlockSessionsTool
import org.dallasmakerspace.agent.BotAudience
import org.dallasmakerspace.agent.BotDefinition
import org.dallasmakerspace.agent.MemberAccess
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig

/** The activity assistant: who was in an area or using a tool. */
@Module
class ActivityBotModule {

  @Provides
  @Singleton
  fun provideSwipeSearch(appConfig: AppConfig, real: Provider<DoorSwipeSearch>): SwipeSearch =
      if (useMocks(appConfig)) MockSwipeSearch() else real.get()

  @Provides
  @Singleton
  fun provideSessionSearch(appConfig: AppConfig, real: Provider<InterlockSessionSearch>): SessionSearch =
      if (useMocks(appConfig)) MockSessionSearch() else real.get()

  @Provides
  @IntoSet
  fun provideActivityBot(
      areaPresence: AreaPresenceTool,
      interlockSessions: InterlockSessionsTool,
      badgeSwipes: BadgeSwipesTool,
      access: ActivityAccess,
  ): BotDefinition =
      BotDefinition(
          key = ACTIVITY_BOT_KEY,
          displayName = "Activity Assistant",
          systemPrompt = ActivityBotPrompt.TEXT,
          tools = listOf(areaPresence, interlockSessions, badgeSwipes),
          memberAccess = MemberAccess.ANY_MEMBER,
          featureFlag = ConfigRegistry.AGENT_ACTIVITY_BOT_ENABLED,
          audience = BotAudience.Groups { groups -> access.areasFor(groups).isNotEmpty() },
          startsPrivate = true,
          maxToolCallsPerTurn = MAX_TOOL_CALLS,
          starterPrompts = ActivityBotPrompt.STARTER_PROMPTS,
          intro = "Ask who was in an area or using a tool.",
          placeholder = "Ask about an area and a time, e.g. Thursday evening…",
      )

  private fun useMocks(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")

  companion object {
    const val ACTIVITY_BOT_KEY = "activity-assistant"
    private const val MAX_TOOL_CALLS = 8
  }
}
