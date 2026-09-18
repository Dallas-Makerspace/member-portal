package org.dallasmakerspace.server.bambustatus.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey
import org.dallasmakerspace.server.auth.UserInfoProvider
import org.dallasmakerspace.server.bambustatus.BambuCameraApiHandler
import org.dallasmakerspace.server.bambustatus.BambuCameraProxyClient
import org.dallasmakerspace.server.bambustatus.BambuModelIconApiHandler
import org.dallasmakerspace.server.bambustatus.BambuPrinterStatusHandler
import org.dallasmakerspace.server.bambustatus.BambuStatusCommitteeSection
import org.dallasmakerspace.server.committees.CommitteePageSectionProvider
import org.dallasmakerspace.server.common.logging.LoggerFactory
import org.dallasmakerspace.server.memberservice.MemberServiceClient
import org.dallasmakerspace.server.routes.IRouteHandler

@Module
abstract class BambuStatusModule {

  // Registers the Bambu summary as a committee-page section without CommitteeDetailHandler or
  // committee-detail.html ever importing this package - see CommitteePageSectionProvider.
  @Binds
  @IntoSet
  abstract fun bindBambuStatusCommitteeSection(
      impl: BambuStatusCommitteeSection
  ): CommitteePageSectionProvider

  companion object {
    @IntoMap
    @Provides
    @StringKey("/committees/3d-fab/printers")
    fun providesBambuPrinterStatusHandler(
        loggerFactory: LoggerFactory,
        userInfoProvider: UserInfoProvider,
        memberServiceClient: MemberServiceClient,
    ): IRouteHandler =
        BambuPrinterStatusHandler(loggerFactory, userInfoProvider, memberServiceClient)

    @IntoMap
    @Provides
    @StringKey("/bambu-api/printers/{id}/camera")
    fun providesBambuCameraApiHandler(
        loggerFactory: LoggerFactory,
        userInfoProvider: UserInfoProvider,
        bambuCameraProxyClient: BambuCameraProxyClient,
        memberServiceClient: MemberServiceClient,
    ): IRouteHandler =
        BambuCameraApiHandler(loggerFactory, userInfoProvider, bambuCameraProxyClient, memberServiceClient)

    @IntoMap
    @Provides
    @StringKey("/bambu-api/models/{model}/icon")
    fun providesBambuModelIconApiHandler(
        loggerFactory: LoggerFactory,
        userInfoProvider: UserInfoProvider,
        bambuCameraProxyClient: BambuCameraProxyClient,
        memberServiceClient: MemberServiceClient,
    ): IRouteHandler =
        BambuModelIconApiHandler(loggerFactory, userInfoProvider, bambuCameraProxyClient, memberServiceClient)
  }
}
