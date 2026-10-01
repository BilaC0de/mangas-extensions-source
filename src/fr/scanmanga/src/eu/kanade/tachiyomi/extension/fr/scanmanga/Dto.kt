package eu.kanade.tachiyomi.extension.fr.scanmanga

import kotlinx.serialization.Serializable

internal const val TOP_PAGE_SIZE = 50
internal const val TOP_MAX = 5000
internal const val TOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

@Serializable
class ChapterPage(
    val f: String, // filename
    val e: String, // extension
)

@Serializable
class UrlPayload(
    private val dN: String,
    private val s: String,
    private val v: String,
    private val c: String,
    private val p: Map<String, ChapterPage>,
) {
    fun generateImageUrls(): List<Pair<Int, String>> {
        val baseUrl = "https://$dN/$s/$v/$c"
        return p.entries.mapNotNull { (key, page) ->
            key.toIntOrNull()?.let { pageIndex ->
                pageIndex to "$baseUrl/${page.f}.${page.e}"
            }
        }.sortedBy { it.first }
    }
}

@Serializable
class MangaSearchDto(
    val title: List<MangaItemDto>?,
)

@Serializable
class MangaItemDto(
    val nom_match: String,
    val url: String,
    val image: String,
)

@Serializable
class TopRequest(
    private val offset: Int,
    private val limit: Int,
    private val top: String,
)
