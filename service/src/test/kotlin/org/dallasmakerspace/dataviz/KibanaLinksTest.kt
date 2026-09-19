package org.dallasmakerspace.dataviz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.dallasmakerspace.core.AppConfig
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class KibanaLinksTest {

  private fun fakeAppConfig(baseUrl: String = "", space: String = ""): AppConfig {
    val appConfig = mock<AppConfig>()
    whenever(appConfig.getStringProperty("app.kibana.base-url", "")).thenReturn(baseUrl)
    whenever(appConfig.getStringProperty("app.kibana.space", "")).thenReturn(space)
    return appConfig
  }

  @Test
  fun `buildDiscoverUrl returns null when dataView is blank, before ever reading config`() {
    val appConfig = fakeAppConfig(baseUrl = "https://kibana.example.com")
    assertNull(KibanaLinks.buildDiscoverUrl(appConfig, "kql", 7, dataView = ""))
  }

  @Test
  fun `buildDiscoverUrl returns null when base-url is unconfigured`() {
    val appConfig = fakeAppConfig(baseUrl = "")
    assertNull(KibanaLinks.buildDiscoverUrl(appConfig, "kql", 7, dataView = "my-data-view"))
  }

  @Test
  fun `buildDiscoverUrl composes base-url, default space, data view, window, and query`() {
    val appConfig = fakeAppConfig(baseUrl = "https://kibana.example.com")

    val url = KibanaLinks.buildDiscoverUrl(appConfig, "host.keyword : \"dc1\"", 7, dataView = "my-data-view")

    assertEquals(
        "https://kibana.example.com/s/default/app/discover#/" +
            "?_g=(time:(from:now-7d,to:now))" +
            "&_a=(index:'my-data-view',query:(language:kuery,query:'host.keyword%20:%20%22dc1%22'))",
        url)
  }

  @Test
  fun `buildDiscoverUrl uses the configured space when set`() {
    val appConfig = fakeAppConfig(baseUrl = "https://kibana.example.com", space = "members-read-only")

    val url = KibanaLinks.buildDiscoverUrl(appConfig, "", 1, dataView = "my-data-view")

    assertEquals(
        "https://kibana.example.com/s/members-read-only/app/discover#/" +
            "?_g=(time:(from:now-1d,to:now))" +
            "&_a=(index:'my-data-view',query:(language:kuery,query:''))",
        url)
  }

  @Test
  fun `buildDiscoverUrl trims a trailing slash from base-url`() {
    val appConfig = fakeAppConfig(baseUrl = "https://kibana.example.com/")

    val url = KibanaLinks.buildDiscoverUrl(appConfig, "", 1, dataView = "my-data-view")

    assertEquals(true, url?.startsWith("https://kibana.example.com/s/default"))
  }
}
