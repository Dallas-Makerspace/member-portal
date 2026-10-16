package org.dallasmakerspace.di

import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import org.dallasmakerspace.workflows.groups.references.CalendarReferenceFinder
import org.dallasmakerspace.workflows.groups.references.ConfigReferenceFinder
import org.dallasmakerspace.workflows.groups.references.GroupReferenceFinder
import org.dallasmakerspace.workflows.groups.references.ManagedObjectsReferenceFinder
import org.dallasmakerspace.workflows.groups.references.MoodleReferenceFinder
import org.dallasmakerspace.workflows.groups.references.ProtectedGroupReferenceFinder
import org.dallasmakerspace.workflows.groups.references.RemoteAccessReferenceFinder

@Module
abstract class GroupReferencesModule {

  /** Finders are added with `@Binds @IntoSet`; the empty declaration keeps the graph valid. */
  @Multibinds abstract fun finders(): Set<@JvmSuppressWildcards GroupReferenceFinder>

  @Binds @IntoSet abstract fun calendar(finder: CalendarReferenceFinder): GroupReferenceFinder

  @Binds
  @IntoSet
  abstract fun managedObjects(finder: ManagedObjectsReferenceFinder): GroupReferenceFinder

  @Binds @IntoSet abstract fun moodle(finder: MoodleReferenceFinder): GroupReferenceFinder

  @Binds @IntoSet abstract fun config(finder: ConfigReferenceFinder): GroupReferenceFinder

  @Binds
  @IntoSet
  abstract fun remoteAccess(finder: RemoteAccessReferenceFinder): GroupReferenceFinder

  @Binds
  @IntoSet
  abstract fun protectedGroups(finder: ProtectedGroupReferenceFinder): GroupReferenceFinder
}
