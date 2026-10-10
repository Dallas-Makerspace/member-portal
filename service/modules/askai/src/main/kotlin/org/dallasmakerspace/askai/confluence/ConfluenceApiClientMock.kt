package org.dallasmakerspace.askai.confluence

import javax.inject.Inject
import org.dallasmakerspace.core.LoggerFactory

/**
 * Mock implementation of IConfluenceApiClient for development and testing. Returns canned responses
 * instead of calling the real Confluence API.
 */
class ConfluenceApiClientMock @Inject constructor(loggerFactory: LoggerFactory) :
    IConfluenceApiClient {
  private val log = loggerFactory.create(javaClass)

  override suspend fun search(
      query: String,
      limit: Int,
      contentType: ContentTypeFilter,
  ): ConfluenceSearchResponse {
    log.info("[MOCK] Searching Confluence for: $query (limit: $limit, filter: $contentType)")

    // Return mock search results based on content type filter
    val pageResults =
        listOf(
            ConfluenceSearchResult(
                id = "12345",
                type = "page",
                content =
                    ConfluenceContent(
                        id = "12345",
                        type = "page",
                        title = "Safety Guidelines for Makerspace",
                        links = ConfluenceContentLinks(webui = "/wiki/spaces/DMS/pages/12345"),
                    ),
                title = "Safety Guidelines for Makerspace",
                excerpt =
                    "...safety rules and guidelines for using the <b>$query</b> equipment at Dallas Makerspace...",
                url = "/wiki/spaces/DMS/pages/12345",
            ),
            ConfluenceSearchResult(
                id = "12346",
                type = "page",
                content =
                    ConfluenceContent(
                        id = "12346",
                        type = "page",
                        title = "Equipment Booking and Reservations",
                        links = ConfluenceContentLinks(webui = "/wiki/spaces/DMS/pages/12346"),
                    ),
                title = "Equipment Booking and Reservations",
                excerpt =
                    "...how to book and reserve equipment including <b>$query</b> related tools...",
                url = "/wiki/spaces/DMS/pages/12346",
            ),
        )

    val attachmentResults =
        listOf(
            ConfluenceSearchResult(
                id = "12347",
                type = "attachment",
                title = "Equipment-Manual.pdf",
                excerpt = "",
                links =
                    ConfluenceContentLinks(
                        webui = "/wiki/download/attachments/12347/Equipment-Manual.pdf"),
            ))

    val mockResults =
        when (contentType) {
          ContentTypeFilter.ALL -> pageResults + attachmentResults
          ContentTypeFilter.PAGES_ONLY -> pageResults
          ContentTypeFilter.ATTACHMENTS_ONLY -> attachmentResults
        }

    return ConfluenceSearchResponse(
        results = mockResults.take(limit),
        start = 0,
        limit = limit,
        size = mockResults.size.coerceAtMost(limit),
        totalSize = mockResults.size,
        links = ConfluenceLinks(base = "https://source.dallasmakerspace.org", context = "/"),
    )
  }

  override suspend fun getContent(contentId: String): ConfluenceContentResponse {
    log.info("[MOCK] Getting Confluence content: $contentId")

    return ConfluenceContentResponse(
        id = contentId,
        type = "page",
        title = "Mock Confluence Page",
        body =
            ConfluenceBody(
                view =
                    ConfluenceBodyContent(
                        value =
                            "<p>This is mock content for page $contentId.</p><p>In production, this would contain the actual Confluence page content.</p>")),
        links = ConfluenceContentLinks(webui = "/wiki/spaces/DMS/pages/$contentId"),
    )
  }
}
