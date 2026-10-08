package org.dallasmakerspace.server.moodle.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.committees.CommitteePageSectionProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.moodle.CommitteeCoursesHandler
import org.dallasmakerspace.server.moodle.MoodleCommitteeCoursesLoader
import org.dallasmakerspace.server.moodle.MoodleCoursesCommitteeSection
import org.dallasmakerspace.server.moodle.SuggestedCoursesHandler
import org.dallasmakerspace.server.routes.IRouteHandler

@Module
abstract class MoodleModule {

  @Binds
  @IntoSet
  abstract fun bindMoodleCoursesCommitteeSection(
      impl: MoodleCoursesCommitteeSection
  ): CommitteePageSectionProvider

  companion object {
    @IntoMap
    @Provides
    @StringKey("/api/suggested-courses")
    fun providesSuggestedCoursesHandler(
        loggerFactory: LoggerFactory,
        userInfoProvider: UserInfoProvider,
        memberServiceClient: MemberServiceClient,
    ): IRouteHandler = SuggestedCoursesHandler(loggerFactory, userInfoProvider, memberServiceClient)

    @IntoMap
    @Provides
    @StringKey("/api/committee-courses")
    fun providesCommitteeCoursesHandler(
        loggerFactory: LoggerFactory,
        userInfoProvider: UserInfoProvider,
        loader: MoodleCommitteeCoursesLoader,
    ): IRouteHandler = CommitteeCoursesHandler(loggerFactory, userInfoProvider, loader)
  }
}
