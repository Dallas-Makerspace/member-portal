package org.dallasmakerspace.server.attention.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey
import org.dallasmakerspace.server.attention.AttentionHandler
import org.dallasmakerspace.server.attention.AttentionSource
import org.dallasmakerspace.server.attention.ContactPreferenceAttentionSource
import org.dallasmakerspace.server.attention.UnreadNotificationsAttentionSource
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.routes.IRouteHandler

@Module
abstract class AttentionModule {

  @Binds
  @IntoSet
  abstract fun bindContactPreference(impl: ContactPreferenceAttentionSource): AttentionSource

  @Binds
  @IntoSet
  abstract fun bindUnreadNotifications(impl: UnreadNotificationsAttentionSource): AttentionSource

  companion object {
    @IntoMap
    @Provides
    @StringKey("/api/attention")
    fun providesAttentionHandler(
        loggerFactory: LoggerFactory,
        userInfoProvider: UserInfoProvider,
        sources: Set<@JvmSuppressWildcards AttentionSource>,
    ): IRouteHandler = AttentionHandler(loggerFactory, userInfoProvider, sources)
  }
}
