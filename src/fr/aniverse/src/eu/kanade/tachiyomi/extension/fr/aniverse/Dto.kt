package eu.kanade.tachiyomi.extension.fr.aniverse

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import kotlin.time.Clock
import kotlin.time.Instant

private val blankLinesRegex = """\n{3,}""".toRegex()

// Chapter titles come prefixed with their number, e.g. "Ch.70 - Kidnapping".
private val chapterPrefixRegex = """^Ch\.?\s*[\d.]+\s*-?\s*""".toRegex(RegexOption.IGNORE_CASE)

// Descriptions come as HTML (<p>, <br>, <i>...): keep the line breaks and drop the tags.
private fun String.htmlToText(): String {
    val body = Jsoup.parseBodyFragment(this).body()
    body.select("br").after("\\n")
    body.select("p").after("\\n\\n")
    return body.text()
        .replace("\\n", "\n")
        .lines()
        .joinToString("\n") { it.trim() }
        .replace(blankLinesRegex, "\n\n")
        .trim()
}

@Serializable
class MangaListDto(
    val items: List<MangaItemDto>,
    val hasNextPage: Boolean,
)

@Serializable
class SearchResultDto(
    val data: List<MangaItemDto>,
    private val total: Int,
    private val page: Int,
    private val pageSize: Int,
) {
    val hasNextPage get() = page * pageSize < total
}

@Serializable
class MangaItemDto(
    private val slug: String,
    private val title: String,
    private val image: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@MangaItemDto.title
        thumbnail_url = image
    }
}

@Serializable
class MangaPageDto(
    val manga: MangaDto,
    val chapters: List<ChapterDto>,
)

@Serializable
class MangaDto(
    val slug: String,
    val databaseId: String? = null,
    private val title: TitleDto,
    private val description: String? = null,
    private val status: String? = null,
    private val authors: List<String> = emptyList(),
    private val artists: List<String> = emptyList(),
    private val genres: List<String> = emptyList(),
    private val coverImage: CoverDto? = null,
    private val savedMetadata: MetadataDto? = null,
) {
    // currentTitle keeps the title shown in the lists so the details screen doesn't rename the entry.
    fun toSManga(currentTitle: String? = null) = SManga.create().apply {
        val mainTitle = currentTitle ?: this@MangaDto.title.userPreferred
        url = slug
        title = mainTitle
        thumbnail_url = coverImage?.large
        description = buildDescription(mainTitle)
        author = authors.joinToString().ifEmpty { null }
        artist = artists.joinToString().ifEmpty { null }
        genre = (genres + savedMetadata?.tags.orEmpty()).distinct().joinToString().ifEmpty { null }
        status = when (this@MangaDto.status) {
            "RELEASING" -> SManga.ONGOING
            "FINISHED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "CANCELLED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun buildDescription(mainTitle: String): String? = buildString {
        description?.htmlToText()?.takeIf { it.isNotEmpty() }?.let { append(it) }

        val altTitles = (
            listOfNotNull(title.userPreferred, title.english, title.romaji, title.native) +
                savedMetadata?.otherInfo?.formerTitles.orEmpty()
            )
            .filterNot { it.equals(mainTitle, ignoreCase = true) }
            .distinctBy { it.lowercase() }
        if (altTitles.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("Titres alternatifs : ").append(altTitles.joinToString(" • "))
        }
    }.ifEmpty { null }
}

@Serializable
class TitleDto(
    val userPreferred: String,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable
class CoverDto(val large: String)

@Serializable
class MetadataDto(
    val tags: List<String> = emptyList(),
    val otherInfo: OtherInfoDto? = null,
)

@Serializable
class OtherInfoDto(val formerTitles: List<String> = emptyList())

@Serializable
class ExtrasDto(val relations: List<RelationDto> = emptyList())

@Serializable
class RelationDto(
    // Only relations available on the site have an href.
    private val href: String? = null,
    private val node: RelationNodeDto,
) {
    fun toSMangaOrNull(): SManga? {
        val slug = href?.takeIf { it.startsWith("/manga/") }?.removePrefix("/manga/") ?: return null
        return SManga.create().apply {
            url = slug
            title = node.title.userPreferred
            thumbnail_url = node.coverImage?.large
        }
    }
}

@Serializable
class RelationNodeDto(
    val title: TitleDto,
    val coverImage: RelationCoverDto? = null,
)

@Serializable
class RelationCoverDto(val large: String? = null)

@Serializable
class ChapterDto(
    private val id: String,
    private val number: String,
    private val title: String? = null,
    private val updatedAt: String,
    private val premiumUntil: String? = null,
) {
    // Locked chapters redirect to the sign-in page until premiumUntil has passed.
    val isLocked get() = Instant.tryParse(premiumUntil) > Clock.System.now().toEpochMilliseconds()

    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        url = "/read/$mangaSlug/$id"
        name = buildString {
            append("Chapitre ").append(number)
            title?.replace(chapterPrefixRegex, "")?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
        }
        date_upload = Instant.tryParse(updatedAt)
        chapter_number = number.toFloatOrNull() ?: -1f
    }
}

@Serializable
class ReaderDto(
    val pages: List<ReaderPageDto>,
    val locked: Boolean,
)

@Serializable
class ReaderPageDto(val src: String)
