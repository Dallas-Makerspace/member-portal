package org.dallasmakerspace.itbot.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import javax.inject.Provider
import javax.inject.Singleton
import org.dallasmakerspace.agent.BotAudience
import org.dallasmakerspace.agent.BotDefinition
import org.dallasmakerspace.askai.tools.ReadTalkTopicTool
import org.dallasmakerspace.config.ConfigRegistry
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.itbot.ElasticLogSearch
import org.dallasmakerspace.itbot.LogSearch
import org.dallasmakerspace.itbot.MockLogSearch
import org.dallasmakerspace.itbot.tools.GitBlameTool
import org.dallasmakerspace.itbot.tools.GitLogTool
import org.dallasmakerspace.itbot.tools.GitShowTool
import org.dallasmakerspace.itbot.tools.ItBotPrompt
import org.dallasmakerspace.itbot.tools.ListFilesTool
import org.dallasmakerspace.itbot.tools.QueryLogsTool
import org.dallasmakerspace.itbot.tools.ReadFileTool
import org.dallasmakerspace.itbot.tools.SearchCodeTool
import org.dallasmakerspace.itbot.tools.SyncReposTool

/** The IT assistant: reads DMS code, git history and logs. */
@Module
class ItBotModule {

  @Provides
  @Singleton
  fun provideLogSearch(appConfig: AppConfig, elastic: Provider<ElasticLogSearch>): LogSearch =
      if (appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")) {
        MockLogSearch()
      } else {
        elastic.get()
      }

  @Provides
  @IntoSet
  @Suppress("LongParameterList")
  fun provideItBot(
      searchCode: SearchCodeTool,
      listFiles: ListFilesTool,
      readFile: ReadFileTool,
      gitLog: GitLogTool,
      gitShow: GitShowTool,
      gitBlame: GitBlameTool,
      queryLogs: QueryLogsTool,
      syncRepos: SyncReposTool,
      readTalkTopic: ReadTalkTopicTool,
  ): BotDefinition =
      BotDefinition(
          key = IT_BOT_KEY,
          displayName = "IT Assistant",
          systemPrompt = ItBotPrompt.TEXT,
          tools = listOf(searchCode, listFiles, readFile, gitLog, gitShow, gitBlame, queryLogs, syncRepos, readTalkTopic),
          featureFlag = ConfigRegistry.AGENT_IT_BOT_ENABLED,
          audience = BotAudience.AdGroup(ConfigRegistry.AGENT_IT_BOT_AD_GROUP),
          startsPrivate = true,
          maxToolCallsPerTurn = MAX_TOOL_CALLS,
          starterPrompts = ItBotPrompt.STARTER_PROMPTS,
          intro = "Ask about DMS code, history and logs.",
          placeholder = "Ask about an error, a feature or a change…",
      )

  companion object {
    const val IT_BOT_KEY = "it-assistant"
    private const val MAX_TOOL_CALLS = 14
  }
}
