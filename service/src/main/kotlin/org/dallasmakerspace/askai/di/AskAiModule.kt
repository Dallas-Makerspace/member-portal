package org.dallasmakerspace.askai.di

import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import javax.inject.Singleton
import org.dallasmakerspace.askai.AskAiConfig
import org.dallasmakerspace.askai.bedrock.BedrockClient
import org.dallasmakerspace.askai.bedrock.BedrockEmbeddingClient
import org.dallasmakerspace.askai.bedrock.IEmbeddingClient
import org.dallasmakerspace.askai.bedrock.MockEmbeddingClient
import org.dallasmakerspace.askai.confluence.ConfluenceApiClient
import org.dallasmakerspace.askai.confluence.ConfluenceApiClientMock
import org.dallasmakerspace.askai.confluence.IConfluenceApiClient
import org.dallasmakerspace.askai.openrouter.IOpenRouterClient
import org.dallasmakerspace.askai.openrouter.OpenRouterClient
import org.dallasmakerspace.askai.openrouter.OpenRouterClientMock
import org.dallasmakerspace.askai.search.CalendarSearchSource
import org.dallasmakerspace.askai.search.ConfluenceSearchSource
import org.dallasmakerspace.askai.search.DiscourseSearchSource
import org.dallasmakerspace.askai.search.SearchSource
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory

/**
 * Dagger module for the Ask AI feature. Provides all dependencies for the AI-powered Q&A system.
 */
@Module
class AskAiModule {

  // OpenRouterClient/BedrockClient build their own HttpClient(CIO) — without @Singleton here,
  // Dagger built a brand-new one for every injection site (same bug found and fixed for AD's LDAP
  // pool).
  @Provides
  @Singleton
  fun provideOpenRouterClient(
      appConfig: AppConfig,
      loggerFactory: LoggerFactory
  ): IOpenRouterClient {
    if (useMockServices(appConfig)) return OpenRouterClientMock(loggerFactory)
    return when (appConfig.getStringProperty("app.llm.provider", "openrouter")) {
      "bedrock" -> BedrockClient(appConfig, loggerFactory)
      else -> OpenRouterClient(appConfig, loggerFactory)
    }
  }

  // ConfluenceApiClient builds its own HttpClient(CIO) — without @Singleton here, Dagger built a
  // brand-new one for every injection site (same bug found and fixed for AD's LDAP pool).
  @Provides
  @Singleton
  fun provideConfluenceApiClient(
      appConfig: AppConfig,
      loggerFactory: LoggerFactory
  ): IConfluenceApiClient =
      if (useMockServices(appConfig)) {
        ConfluenceApiClientMock(loggerFactory)
      } else {
        ConfluenceApiClient(appConfig, loggerFactory)
      }

  @Provides
  @Singleton
  fun provideEmbeddingClient(appConfig: AppConfig, loggerFactory: LoggerFactory): IEmbeddingClient =
      if (useMockServices(appConfig)) MockEmbeddingClient(loggerFactory)
      else BedrockEmbeddingClient(loggerFactory)

  /**
   * Provides AskAI service configuration. Uses default values but could be extended to read from
   * AppConfig.
   */
  @Provides
  @Singleton
  fun provideAskAiConfig(appConfig: AppConfig): AskAiConfig {
    val monthlyBudget =
        appConfig.getStringProperty("app.askai.monthly-budget-usd", "12.0")?.toDoubleOrNull()
            ?: 12.0
    return AskAiConfig(monthlyBudgetUsd = monthlyBudget)
  }

  /**
   * Provides the set of search sources. To add a new search source:
   * 1. Create a class implementing SearchSource
   * 2. Add it as a parameter here
   * 3. Add it to the returned set
   */
  @Provides
  @ElementsIntoSet
  fun provideSearchSources(
      discourseSearchSource: DiscourseSearchSource,
      confluenceSearchSource: ConfluenceSearchSource,
      calendarSearchSource: CalendarSearchSource,
  ): Set<SearchSource> {
    return setOf(discourseSearchSource, confluenceSearchSource, calendarSearchSource)
  }

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
