package org.dallasmakerspace.di

import dagger.Module
import dagger.Provides
import javax.inject.Named
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.core.LoggerFactory
import org.dallasmakerspace.smartwaiver.SMARTWAIVER_STORE
import org.dallasmakerspace.storage.IObjectStore
import org.dallasmakerspace.storage.InMemoryObjectStore
import org.dallasmakerspace.storage.S3ObjectStore

@Module
class StorageModule {

  // S3ObjectStore builds its own S3AsyncClient/S3Presigner — without @Singleton here, Dagger would
  // build a brand-new one for every injection site (same bug found and fixed for AD's LDAP pool).
  @Provides
  @Singleton
  fun provideObjectStore(appConfig: AppConfig, loggerFactory: LoggerFactory): IObjectStore =
      if (useMockServices(appConfig)) {
        InMemoryObjectStore()
      } else {
        S3ObjectStore(appConfig, loggerFactory)
      }

  /** Dedicated bucket for the SmartWaiver mirror; reaches [SMARTWAIVER_STORE] consumers only. */
  @Provides
  @Singleton
  @Named(SMARTWAIVER_STORE)
  fun provideSmartwaiverObjectStore(appConfig: AppConfig, loggerFactory: LoggerFactory): IObjectStore =
      if (useMockServices(appConfig)) {
        InMemoryObjectStore()
      } else {
        S3ObjectStore(appConfig, loggerFactory, "app.smartwaiver.object-store.bucket")
      }

  private fun useMockServices(appConfig: AppConfig) =
      appConfig.requireBooleanProperty("ktor.development") &&
          appConfig.requireBooleanProperty("app.use-mock-services")
}
