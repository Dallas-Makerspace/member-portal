package org.dallasmakerspace.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.members.GroupHistoryRepository
import org.dallasmakerspace.members.GroupHistoryRepositoryMock
import org.dallasmakerspace.members.GroupSettingsRepository
import org.dallasmakerspace.members.GroupSettingsRepositoryMock
import org.dallasmakerspace.members.GroupSettingsService
import org.dallasmakerspace.members.IGroupHistoryRepository
import org.dallasmakerspace.members.IGroupSettingsRepository
import org.dallasmakerspace.members.IMemberRepository
import org.dallasmakerspace.members.IMemberSettingsRepository
import org.dallasmakerspace.members.MemberRepository
import org.dallasmakerspace.members.MemberRepositoryMock
import org.dallasmakerspace.members.MemberSettingsRepository
import org.dallasmakerspace.members.MemberSettingsRepositoryMock
import org.dallasmakerspace.members.MemberSettingsService
import org.dallasmakerspace.members.ProfilePhotoService
import org.dallasmakerspace.members.observers.DiscourseMemberStatusObserver
import org.dallasmakerspace.members.observers.IMemberPropChangeObserver
import org.dallasmakerspace.storage.ObjectStoreFactory

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

    @Provides
    fun provideGroupSettingsRepository(
        appConfig: AppConfig,
        loggerFactory: LoggerFactory,
        groupHistoryRepository: IGroupHistoryRepository,
    ): IGroupSettingsRepository =
        if (useMockServices(appConfig)) {
          GroupSettingsRepositoryMock()
        } else {
          GroupSettingsRepository(loggerFactory, groupHistoryRepository)
        }

    @Provides
    fun provideGroupSettingsService(
        repository: IGroupSettingsRepository,
        loggerFactory: LoggerFactory,
    ): GroupSettingsService = GroupSettingsService(repository, loggerFactory)

    // Holds an in-memory rate-limit map keyed by username - must stay a single instance.
    @Provides
    @Singleton
    fun provideProfilePhotoService(
        memberRepository: IMemberRepository,
        objectStoreFactory: ObjectStoreFactory,
        loggerFactory: LoggerFactory,
    ): ProfilePhotoService =
        ProfilePhotoService(memberRepository, objectStoreFactory, loggerFactory)

    private fun useMockServices(appConfig: AppConfig) =
        appConfig.requireBooleanProperty("ktor.development") &&
            appConfig.requireBooleanProperty("app.use-mock-services")
  }
}
