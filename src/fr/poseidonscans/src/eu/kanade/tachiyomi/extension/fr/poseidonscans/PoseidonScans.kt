package eu.kanade.tachiyomi.extension.fr.poseidonscans

import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class PoseidonScans :
    KeiSource(),
    ConfigurableSource {

    val rscHeaders: Headers get() = headers.newBuilder().add("RSC", "1").build()

    private val preferences: SharedPreferences by getPreferencesLazy()

    // /series is protected by Cloudflare: opening it in the WebView lets the user solve the challenge once.
    override fun getHomeUrl(): String = "$baseUrl/series"

    private fun String.toAbsoluteUrl(): String = if (this.startsWith("http")) this else baseUrl + this

    private fun String.toApiCoverUrl(): String {
        if (this.startsWith("http")) return this
        if (this.contains("storage/covers/")) return "$baseUrl/api/covers/${this.substringAfter("storage/covers/")}"
        if (this.startsWith("/api/covers/")) return baseUrl + this
        if (this.startsWith("/")) return baseUrl + this
        return "$baseUrl/api/covers/$this"
    }

    // found /manga/all too

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val apiResponse = client.get("$baseUrl/api/manga/lastchapters?limit=16&page=$page").parseAs<LatestApiResponse>()

        val mangas = apiResponse.data.map { apiManga ->
            SManga.create().apply {
                title = apiManga.title
                url = "/serie/${apiManga.slug}"
                thumbnail_url = apiManga.slug.toApiCoverUrl() + ".webp"
            }
        }
        val hasNextPage = mangas.size == 16
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Popular ===============================

    // The RSC home page only contains ~5 featured manga, so we use the full paginated list instead.
    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("series")
            addQueryParameter("sortBy", "popular")
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()

        return parseMangaList(client.get(url).asJsoup())
    }

    // =========================== Manga Details ============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = if (fetchDetails) async { fetchDetails(manga) } else null
        val chapterList = if (fetchChapters) async { fetchChapterList(manga) } else null

        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    private suspend fun fetchDetails(manga: SManga): SManga {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val mangaDto = document.extractNextJs<MangaDetailsData>() ?: throw Exception("Cant scape data from Next.js")

        return SManga.create().apply {
            title = mangaDto.title
            thumbnail_url = "$baseUrl/api/covers/${mangaDto.slug}.webp"
            author = mangaDto.author
            artist = mangaDto.artist

            genre = mangaDto.categories.mapNotNull { it.name.trim().takeIf { name -> name.isNotBlank() } }.joinToString {
                it.replaceFirstChar { char -> char.titlecase(Locale.FRENCH) }
            }

            status = parseStatus(mangaDto.status)

            description = mangaDto.description.trim().takeIf { it.isNotEmpty() }

            setUrlWithoutDomain("/serie/${mangaDto.slug}")
        }
    }

    private fun parseStatus(statusString: String?): Int = when (statusString?.trim()?.lowercase(Locale.FRENCH)) {
        "en cours" -> SManga.ONGOING
        "terminé" -> SManga.COMPLETED
        "en pause", "hiatus" -> SManga.ON_HIATUS
        "annulé", "abandonné" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val url = getMangaUrl(manga).toHttpUrl()
        val rscBody = client.get(url, rscHeaders).use { it.body.string() }
        val chapters = chapterListRsc(rscBody)
        if (chapters.isNotEmpty()) return chapters

        // RSC data can be partial on first load; retry with cache-busting
        val retryUrl = url.newBuilder().addQueryParameter("_", System.currentTimeMillis().toString()).build()
        val retryBody = client.get(retryUrl, rscHeaders, CacheControl.Builder().noCache().build(), ensureSuccess = false)
            .use { it.body.string() }
        return chapterListRsc(retryBody)
    }

    fun chapterListRsc(rscBody: String): List<SChapter> {
        val mangaPageDto = rscBody.extractNextJsRsc<MangaPageDetailsData>() ?: throw Exception("Cant scape data from Next.js")

        val showPremium = preferences.getBoolean(
            SHOW_PREMIUM_KEY,
            SHOW_PREMIUM_DEFAULT,
        )
        return mangaPageDto.manga.chapters.mapNotNull { ch ->
            val isLocked = ch.isPremium == true && mangaPageDto.isPremiumUser != true

            if (isLocked && !showPremium) {
                val premiumUntilDate = ch.premiumUntil?.time ?: 0L
                if (System.currentTimeMillis() <= premiumUntilDate) return@mapNotNull null
            }
            SChapter.create().apply {
                val chapterNumberString = ch.number.toString().removeSuffix(".0")
                val isVolume = ch.isVolume == true || (ch.number % 1 == 0f && ch.title?.contains("volume", ignoreCase = true) == true)

                val baseName = if (isVolume) {
                    "Volume $chapterNumberString"
                } else {
                    "Chapitre $chapterNumberString"
                }
                val title = ch.title?.trim()?.takeIf { it.isNotBlank() }

                name = buildString {
                    if (isLocked) append("🔒 ")

                    append(
                        if (title != null) {
                            "$baseName - $title"
                        } else {
                            baseName
                        },
                    )

                    if (isLocked) {
                        val dateParts = formatTimestamp(
                            ch.premiumUntil?.time ?: 0L,
                        ).split(" ")
                        // formatTimestamp gives: [dd, MMMM, HH:mm]
                        append(
                            " - Free the ${dateParts.take(2).joinToString(" ")} at ${dateParts.getOrNull(2) ?: ""}",
                        )
                    }
                }.trim()
                setUrlWithoutDomain(
                    "/serie/${mangaPageDto.manga.slug}/chapter/$chapterNumberString",
                )
                date_upload = ch.createdAt.time
                chapter_number = ch.number
            }
        }.sortedByDescending { it.chapter_number }
    }

    fun formatTimestamp(timestamp: Long): String = DateTimeFormatter.ofPattern("dd MMMM HH:mm", Locale.getDefault())
        .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pageDataDto = client.get(getChapterUrl(chapter), rscHeaders).extractNextJs<PageData>() ?: throw Exception("Cant scape data from Next.js")
        if (pageDataDto.currentChapter.isPremium) {
            if (pageDataDto.sessionStatus == "unauthenticated") {
                throw Exception("This chapter is premium. Please connect via the WebView to view.")
            }
            if (!pageDataDto.isPremiumUser) {
                throw Exception("This chapter is premium. You are not a premium user.")
            }
        }
        return pageDataDto.initialData.images.map { pageDto ->
            Page(
                index = pageDto.order,
                imageUrl = pageDto.originalUrl.toAbsoluteUrl(),
            )
        }.sortedBy { it.index }
    }

    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .build()

    // =============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("series")
            if (query.isNotBlank()) {
                addQueryParameter("search", query)
            }
            if (page > 1) {
                addQueryParameter("page", page.toString())
            }

            filters.firstInstanceOrNull<SortFilter>()?.let { addQueryParameter("sortBy", it.toUriPart()) }
            filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()?.let { addQueryParameter("status", it) }

            // Type and genres share the same "tags" query parameter
            val tags = buildList {
                filters.firstInstanceOrNull<TypeFilter>()?.let { addAll(it.getValues()) }
                filters.firstInstanceOrNull<GenreFilter>()?.let { addAll(it.getValues()) }
            }
            if (tags.isNotEmpty()) addQueryParameter("tags", tags.joinToString(","))

            filters.firstInstanceOrNull<MinChaptersFilter>()?.state?.takeIf { it.isNotBlank() }
                ?.let { addQueryParameter("minChapters", it) }
            filters.firstInstanceOrNull<MaxChaptersFilter>()?.state?.takeIf { it.isNotBlank() }
                ?.let { addQueryParameter("maxChapters", it) }
        }.build()

        return parseMangaList(client.get(url).asJsoup())
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("div.grid a.block.group").map { element ->
            val href = element.attr("href")
            val title = element.selectFirst("h2")?.text()!!

            val thumbnailUrlPath = element.selectFirst("img[alt]")?.attr("srcset")?.substringBefore(" ")?.let {
                URLDecoder.decode(it, "UTF-8").substringAfter("url=").substringBefore("&")
            }

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = thumbnailUrlPath?.takeIf { it.isNotBlank() }?.toApiCoverUrl()
            }
        }

        val hasNextPage = document.select("nav[aria-label=Pagination] a:contains(Suivant)").isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Filters ===============================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Filters are ignored when searching by text"),
        Filter.Separator(),
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
        MinChaptersFilter(),
        MaxChaptersFilter(),
    )

    // Default state = "Popularité" so the filter sheet matches the Popular tab
    private class SortFilter :
        Filter.Select<String>(
            "Tri",
            arrayOf("Ajout Récent (Série)", "Dernier Chapitre", "Plus de chapitres", "Popularité", "Ordre alphabétique"),
            3,
        ) {
        fun toUriPart() = when (state) {
            1 -> "latest_chapter"
            2 -> "most_chapters"
            3 -> "popular"
            4 -> "alpha"
            else -> "recent"
        }
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Statut",
            arrayOf("Tous", "En cours", "Terminé", "En pause", "Annulé"),
        ) {
        fun toUriPart(): String? = when (state) {
            1 -> "en cours"
            2 -> "terminé"
            3 -> "en pause"
            4 -> "annulé"
            else -> null
        }
    }

    private class TagCheckBox(name: String) : Filter.CheckBox(name)

    private class TypeFilter :
        Filter.Group<TagCheckBox>(
            "Type",
            listOf("MANGA", "MANHUA", "MANHWA", "WEBTOON").map(::TagCheckBox),
        ) {
        fun getValues() = state.filter { it.state }.map { it.name }
    }

    private class GenreFilter :
        Filter.Group<TagCheckBox>(
            "Genres",
            listOf(
                "Délinquant",
                "Détective",
                "Drama",
                "Ecchi",
                "Fantaisie",
                "Fantastique",
                "Mystère",
                "Necromancer",
                "Portail/Donjon",
                "Psychologique",
                "Réincarnation",
                "Regression",
                "Romance",
                "Shojo",
                "Shonen",
                "Sports",
                "Super pouvoirs",
                "Surnaturel",
                "Systeme",
                "Tour",
                "Tragique",
                "Vengeance",
                "Vie scolaire",
            ).map(::TagCheckBox),
        ) {
        fun getValues() = state.filter { it.state }.map { it.name }
    }

    // No default state: an empty value means "no limit"
    private class MinChaptersFilter : Filter.Text("Chapitres min")
    private class MaxChaptersFilter : Filter.Text("Chapitres max")

    // ========================== Preference =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = SHOW_PREMIUM_KEY
            title = "Show premium chapters"
            summary = "Show paid chapters (identified by 🔒) in the list."
            setDefaultValue(SHOW_PREMIUM_DEFAULT)
        }.also(screen::addPreference)
    }

    companion object {
        private const val SHOW_PREMIUM_KEY = "show_premium_chapters"
        private const val SHOW_PREMIUM_DEFAULT = false
    }
}
