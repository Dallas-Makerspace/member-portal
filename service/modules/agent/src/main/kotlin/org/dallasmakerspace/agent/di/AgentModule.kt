package org.dallasmakerspace.agent.di

import ai.koog.prompt.executor.clients.bedrock.BedrockAPIMethod
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet
import javax.inject.Singleton
import org.dallasmakerspace.agent.BotDefinition
import org.dallasmakerspace.agent.ChatModelClient
import org.dallasmakerspace.agent.KoogChatModelClient
import org.dallasmakerspace.agent.MockChatModelClient
import org.dallasmakerspace.core.AppConfig

/** AI assistant platform. Feature modules add bots with `@Provides @IntoSet BotDefinition`. */
@Module
class AgentModule {

  // Owns the Bedrock HTTP client; must stay a singleton.
  @Provides
  @Singleton
  fun provideChatModelClient(appConfig: AppConfig): ChatModelClient =
      if (useMockServices(appConfig)) {
        MockChatModelClient()
      } else {
        KoogChatModelClient(
            BedrockLLMClient(
                bedrockClient = BedrockRuntimeClient { region = BEDROCK_REGION },
                apiMethod = BedrockAPIMethod.Converse,
            ))
      }

  /** Declares the bot set so the app starts even before any module contributes one. */
  @Provides @ElementsIntoSet fun provideNoBots(): Set<BotDefinition> = emptySet()

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")

  companion object {
    private const val BEDROCK_REGION = "us-east-1"
  }
}
