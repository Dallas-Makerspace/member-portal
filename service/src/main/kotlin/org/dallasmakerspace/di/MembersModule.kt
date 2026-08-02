package org.dallasmakerspace.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.GroupHistoryRepository
import org.dallasmakerspace.members.GroupHistoryRepositoryMock
import org.dallasmakerspace.members.IGroupHistoryRepository
import org.dallasmakerspace.members.IMemberRepository
import org.dallasmakerspace.members.IMemberSettingsRepository
import org.dallasmakerspace.members.MemberRepository
import org.dallasmakerspace.members.MemberRepositoryMock
import org.dallasmakerspace.members.MemberSettingsRepository
import org.dallasmakerspace.members.MemberSettingsRepositoryMock
import org.dallasmakerspace.members.MemberSettingsService
import org.dallasmakerspace.members.observers.DiscourseMemberStatusObserver
import org.dallasmakerspace.members.observers.IMemberPropChangeObserver

@Module
abstract class MembersModule {

  @Binds
  @IntoSet
  abstract fun bindDiscourseMemberObservers(
      observer: DiscourseMemberStatusObserver
  ): IMemberPropChangeObserver

  companion object {
    @Provides
    fun provideMemberRepository(
        appConfig: AppConfig,
        loggerFactory: LoggerFactory,
    ): IMemberRepository =
        if (useMockServices(appConfig)) MemberRepositoryMock() else MemberRepository(loggerFactory)

    @Provides
    fun provideGroupHistoryRepository(appConfig: AppConfig): IGroupHistoryRepository =
        if (useMockServices(appConfig)) GroupHistoryRepositoryMock() else GroupHistoryRepository()

    @Provides
    fun provideMemberSettingsRepository(
        appConfig: AppConfig,
        loggerFactory: LoggerFactory,
    ): IMemberSettingsRepository =
        if (useMockServices(appConfig)) {
          MemberSettingsRepositoryMock()
        } else {
          MemberSettingsRepository(loggerFactory)
        }

    @Provides
    fun provideMemberSettingsService(
        repository: IMemberSettingsRepository,
        loggerFactory: LoggerFactory,
    ): MemberSettingsService = MemberSettingsService(repository, loggerFactory)

    private fun useMockServices(appConfig: AppConfig) =
        appConfig.requireBooleanProperty("ktor.development") &&
            appConfig.requireBooleanProperty("app.use-mock-services")
  }
}
