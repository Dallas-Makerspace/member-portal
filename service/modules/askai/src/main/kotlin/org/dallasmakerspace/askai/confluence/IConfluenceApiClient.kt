package org.dallasmakerspace.askai.confluence

/**
 * Interface for the Confluence API client. Provides methods for searching Confluence wiki content.
 */
interface IConfluenceApiClient {

  /**
   * Search Confluence for content matching the query.
   *
   * @param query The search query (uses CQL - Confluence Query Language)
   * @param limit Maximum number of results to return
   * @param contentType Optional content type filter: "page", "attachment", or null for all types
   * @return Search response containing matching pages/content
   */
  suspend fun search(
      query: String,
      limit: Int = 10,
      contentType: ContentTypeFilter = ContentTypeFilter.ALL
  ): ConfluenceSearchResponse

  /**
   * Get the content/body of a specific page by ID.
   *
   * @param contentId The Confluence content ID
   * @return The page content
   */
  suspend fun getContent(contentId: String): ConfluenceContentResponse
}

/** Filter for Confluence content types in search. */
enum class ContentTypeFilter {
  /** All content types (no filter). */
  ALL,
  /** Pages only (excludes attachments, blog posts). */
  PAGES_ONLY,
  /** Attachments only (PDFs, images, etc.). */
  ATTACHMENTS_ONLY
}
