package org.dallasmakerspace.di

import dagger.Module
import dagger.Provides
import dagger.Reusable
import javax.inject.Singleton
import org.dallasmakerspace.activedirectory.ActiveDirectoryClient
import org.dallasmakerspace.activedirectory.ActiveDirectoryClientMock
import org.dallasmakerspace.activedirectory.ActiveDirectoryService
import org.dallasmakerspace.activedirectory.ActiveDirectoryServiceMock
import org.dallasmakerspace.activedirectory.IActiveDirectoryClient
import org.dallasmakerspace.activedirectory.IActiveDirectoryService
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory

@Module
class ActiveDirectoryModule {

  // Scope must be declared on the @Provides method itself, not just on the ActiveDirectoryClient
  // class — Dagger only honors the class-level @Singleton annotation when a type is bound via
  // constructor injection. Because this module binds IActiveDirectoryClient through an explicit
  // factory method (needed for the mock/real branch below), the missing @Singleton here meant
  // Dagger built a brand-new ActiveDirectoryClient — and a brand-new LDAP connection pool — for
  // every single injection site, even within a single AppComponent instance. Observed in
  // production: 6 separate "LDAP connection pool initialized" log lines from one process, and
  // dozens of concurrent LDAP connections against a pool meant to cap at 5.
  @Provides
  @Singleton
  fun providesActiveDirectoryClient(
      appConfig: AppConfig,
      loggerFactory: LoggerFactory
  ): IActiveDirectoryClient =
      if (useMockServices(appConfig)) {
        ActiveDirectoryClientMock()
      } else {
        ActiveDirectoryClient(appConfig, loggerFactory)
      }

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")

  // Stateless wrapper around the singleton client above — @Reusable (matching the class-level
  // annotation on ActiveDirectoryService) lets Dagger share an instance where convenient without
  // requiring it, which is fine since it holds no resources of its own.
  @Provides
  @Reusable
  fun provideActiveDirectoryService(
      appConfig: AppConfig,
      activeDirectoryClient: IActiveDirectoryClient
  ): IActiveDirectoryService =
      if (useMockServices(appConfig)) {
        ActiveDirectoryServiceMock()
      } else {
        ActiveDirectoryService(activeDirectoryClient)
      }
}
