package org.dallasmakerspace.keyvalue.di

import dagger.Binds
import dagger.Module
import javax.inject.Singleton
import org.dallasmakerspace.keyvalue.ExposedKeyValueStore
import org.dallasmakerspace.keyvalue.KeyValueStore

@Module
abstract class KeyValueModule {
  @Binds @Singleton abstract fun bindKeyValueStore(impl: ExposedKeyValueStore): KeyValueStore
}
