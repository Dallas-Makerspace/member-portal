package org.dallasmakerspace.server.di

import dagger.Component
import io.ktor.server.application.*
import javax.inject.Singleton
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.AppConfig
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.agent.AgentBotAccess
import org.dallasmakerspace.server.memberservice.MemberServiceClient

@Singleton
@Component(
    modules =
        [
            DiscordModule::class,
            DiscourseModule::class,
            RoutesModule::class,
            ServerModule::class,
            VoterRegistrationModule::class])
interface AppComponent {
  fun inject(application: Application)

  fun getAppConfig(): AppConfig

  fun getLoggerFactory(): LoggerFactory

  fun getUserInfoProvider(): UserInfoProvider

  fun getMemberServiceClient(): MemberServiceClient

  fun getAgentBotAccess(): AgentBotAccess
}
