package org.dallasmakerspace.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import dagger.multibindings.StringKey
import javax.inject.Singleton
import org.dallasmakerspace.core.AppConfig
import org.dallasmakerspace.workflows.ArtifactLinks
import org.dallasmakerspace.workflows.DefaultWorkflowExecutor
import org.dallasmakerspace.workflows.DefaultWorkflowService
import org.dallasmakerspace.workflows.InMemoryWorkflowStore
import org.dallasmakerspace.workflows.InProcessWorkflowEventBus
import org.dallasmakerspace.workflows.ObjectStoreArtifactLinks
import org.dallasmakerspace.workflows.GroupAdminAuthorizer
import org.dallasmakerspace.workflows.Workflow
import org.dallasmakerspace.workflows.WorkflowAuthorization
import org.dallasmakerspace.workflows.WorkflowEventBus
import org.dallasmakerspace.workflows.WorkflowExecutor
import org.dallasmakerspace.workflows.WorkflowService
import org.dallasmakerspace.workflows.WorkflowStartAuthorizer
import org.dallasmakerspace.workflows.WorkflowStore
import org.dallasmakerspace.workflows.groups.GroupArchiveWorkflow
import org.dallasmakerspace.workflows.groups.GroupExportMembersWorkflow
import org.dallasmakerspace.workflows.store.ExposedWorkflowStore

@Module
abstract class WorkflowsModule {

  /** Workflows are added with `@Binds @IntoSet` in the module that owns them. */
  @Multibinds abstract fun workflows(): Set<@JvmSuppressWildcards Workflow<*>>

  @Binds
  @IntoSet
  abstract fun bindGroupExportMembers(workflow: GroupExportMembersWorkflow): Workflow<*>

  @Binds
  @IntoSet
  abstract fun bindGroupArchive(workflow: GroupArchiveWorkflow): Workflow<*>

  /** Authorizers by the `authorization` mode a workflow names; no authorizer means denied. */
  @Multibinds
  abstract fun startAuthorizers(): Map<String, @JvmSuppressWildcards WorkflowStartAuthorizer>

  @Binds
  @IntoMap
  @StringKey(WorkflowAuthorization.GROUP_ADMIN)
  abstract fun bindGroupAdminAuthorizer(authorizer: GroupAdminAuthorizer): WorkflowStartAuthorizer

  @Binds abstract fun bindArtifactLinks(links: ObjectStoreArtifactLinks): ArtifactLinks

  @Binds abstract fun bindEventBus(bus: InProcessWorkflowEventBus): WorkflowEventBus

  @Binds abstract fun bindService(service: DefaultWorkflowService): WorkflowService

  @Binds abstract fun bindExecutor(executor: DefaultWorkflowExecutor): WorkflowExecutor

  companion object {
    @Provides
    @Singleton
    fun provideWorkflowStore(appConfig: AppConfig): WorkflowStore =
        if (useMockServices(appConfig)) InMemoryWorkflowStore() else ExposedWorkflowStore()

    private fun useMockServices(appConfig: AppConfig) =
        appConfig.requireBooleanProperty("ktor.development") &&
            appConfig.requireBooleanProperty("app.use-mock-services")
  }
}
