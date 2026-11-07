package org.dallasmakerspace.agenttools.activity

import dagger.Module
import dagger.Provides
import javax.inject.Provider
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig

/** Where the activity tools read door swipes and interlock sessions: canned data in mock mode. */
@Module
class ActivityToolsModule {

  @Provides
  @Singleton
  fun provideSwipeSearch(appConfig: AppConfig, real: Provider<DoorSwipeSearch>): SwipeSearch =
      if (useMocks(appConfig)) MockSwipeSearch() else real.get()

  @Provides
  @Singleton
  fun provideSessionSearch(appConfig: AppConfig, real: Provider<InterlockSessionSearch>): SessionSearch =
      if (useMocks(appConfig)) MockSessionSearch() else real.get()

  private fun useMocks(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
