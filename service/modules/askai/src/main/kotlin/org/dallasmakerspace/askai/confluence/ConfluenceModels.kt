package org.dallasmakerspace.askai.confluence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response from Confluence search API (CQL search). */
@Serializable
data class ConfluenceSearchResponse(
    val results: List<ConfluenceSearchResult>,
    val start: Int = 0,
    val limit: Int = 25,
    val size: Int = 0,
    @SerialName("totalSize") val totalSize: Int = 0,
    @SerialName("_links") val links: ConfluenceLinks? = null,
)

@Serializable
data class ConfluenceSearchResult(
    val id: String = "",
    val type: String = "", // "page", "attachment", "blogpost", etc.
    val status: String = "",
    val content: ConfluenceContent? = null,
    val title: String = "",
    val excerpt: String = "",
    val url: String = "",
    @SerialName("_links") val links: ConfluenceContentLinks? = null,
    val resultGlobalContainer: ConfluenceContainer? = null,
)

@Serializable
data class ConfluenceContent(
    val id: String,
    val type: String,
    val title: String,
    @SerialName("_links") val links: ConfluenceContentLinks? = null,
)

@Serializable
data class ConfluenceContentLinks(
    val webui: String? = null,
    val self: String? = null,
)

@Serializable
data class ConfluenceContainer(
    val title: String = "",
    val displayUrl: String = "",
)

@Serializable
data class ConfluenceLinks(
    val base: String = "",
    val context: String = "",
)

/** Response from Confluence content API for fetching page details. */
@Serializable
data class ConfluenceContentResponse(
    val id: String,
    val type: String,
    val title: String,
    val body: ConfluenceBody? = null,
    @SerialName("_links") val links: ConfluenceContentLinks? = null,
)

@Serializable
data class ConfluenceBody(
    @SerialName("view") val view: ConfluenceBodyContent? = null,
    @SerialName("storage") val storage: ConfluenceBodyContent? = null,
)

@Serializable data class ConfluenceBodyContent(val value: String = "")
