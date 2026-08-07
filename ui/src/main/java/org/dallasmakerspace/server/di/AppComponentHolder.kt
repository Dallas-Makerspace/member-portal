package org.dallasmakerspace.server.di

/**
 * Single app-wide instance of [AppComponent]. `@Singleton`-scoped Dagger bindings are only
 * singletons *within one component instance* — calling `DaggerAppComponent.create()` directly
 * (as many call sites throughout this codebase used to) builds an entirely separate object graph
 * each time, with its own separate copies of everything scoped `@Singleton`, including
 * [org.dallasmakerspace.server.common.DMSHttpClient]'s shared HTTP client. Route through
 * [instance] instead of calling `DaggerAppComponent.create()` directly, so the app actually has
 * one of each singleton instead of one per distinct call site.
 */
object AppComponentHolder {
  val instance: AppComponent by lazy { DaggerAppComponent.create() }
}
