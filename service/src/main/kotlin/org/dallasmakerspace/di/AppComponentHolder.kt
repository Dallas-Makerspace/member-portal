package org.dallasmakerspace.di

/**
 * Single app-wide instance of [AppComponent]. `@Singleton`-scoped Dagger bindings are only
 * singletons *within one component instance* — calling `DaggerAppComponent.create()` directly
 * (as many call sites throughout this codebase used to) builds an entirely separate object graph
 * each time, with its own separate copies of everything scoped `@Singleton`, including
 * [org.dallasmakerspace.activedirectory.ActiveDirectoryClient]'s LDAP connection pool. With ~29
 * separate call sites, that meant every distinct route/service that got exercised for the first
 * time could spin up its own independent LDAP pool (up to [org.dallasmakerspace.activedirectory
 * .MAX_LDAP_CONNECTIONS] connections each) that never got reclaimed, growing unbounded as traffic
 * diversity increased — this is what caused the runaway LDAP connection growth (18 -> 30+ within
 * minutes on a fresh process) observed in production on 2026-08-07.
 *
 * Route through [instance] instead of calling `DaggerAppComponent.create()` directly.
 */
object AppComponentHolder {
  val instance: AppComponent by lazy { DaggerAppComponent.create() }
}
