package org.dallasmakerspace.server.common

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig

// Single place selecting the Ktor engine every HttpClient(...) in this module uses. CIO showed
// multi-second tail-latency spikes under sustained load (see EngineBenchmark.kt); OkHttp didn't.
val DmsHttpEngine: HttpClientEngineFactory<OkHttpConfig> = OkHttp
