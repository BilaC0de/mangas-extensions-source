package eu.kanade.tachiyomi.extension.fr.aniverse

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Aniverse : KeiSource() {

    // Images are served from another host and stay unlimited.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 3, period = 1.seconds) { it.host.endsWith("aniverse.fr") }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchSearchList(page)

    // Sorted by the release date of the latest chapter.
    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchMangaList(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchSearchList(page, filters, query.trim())

    private suspend fun fetchSearchList(
        page: Int,
        filters: FilterList = FilterList(),
        query: String = "",
    ): MangasPage {
        val url = "$baseUrl/api/anime/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("media", "manga")
            if (query.isNotEmpty()) addQueryParameter("q", query)
            filters.firstInstanceOrNull<KindFilter>()?.value?.let { addQueryParameter("kind", it) }
            filters.firstInstanceOrNull<FormatFilter>()?.values?.forEach { addQueryParameter("type", it) }
            filters.firstInstanceOrNull<GenreFilter>()?.values?.forEach { addQueryParameter("genres", it) }
            filters.firstInstanceOrNull<StatusFilter>()?.values?.forEach { addQueryParameter("status", it) }
            filters.firstInstanceOrNull<YearFilter>()?.value?.let { addQueryParameter("seasonYear", it.toString()) }
            filters.firstInstanceOrNull<MinRatingFilter>()?.value?.let { addQueryParameter("minRating", it.toString()) }
            filters.firstInstanceOrNull<MaxRatingFilter>()?.value?.let { addQueryParameter("maxRating", it.toString()) }
            addQueryParameter("sort", filters.firstInstanceOrNull<SortFilter>()?.value ?: "popularity")
            addQueryParameter("page", page.toString())
            addQueryParameter("pageSize", "32")
        }.build()
        val dto = client.get(url).parseAs<SearchResultDto>()
        return MangasPage(dto.data.map { it.toSManga() }, dto.hasNextPage)
    }

    private suspend fun fetchMangaList(page: Int): MangasPage {
        val url = "$baseUrl/api/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        val dto = client.get(url).parseAs<MangaListDto>()
        return MangasPage(dto.items.map { it.toSManga() }, dto.hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val dto = fetchMangaPage(manga.url)
        return SMangaUpdate(
            manga = dto.manga.toSManga(currentTitle = manga.title),
            chapters = dto.chapters
                .filterNot { it.isLocked }
                .map { it.toSChapter(dto.manga.slug) }
                .reversed(),
        )
    }

    override val supportsRelatedMangas get() = true

    // Only the relations that exist on the site (href != null) are returned.
    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val id = fetchMangaPage(manga.url).manga.databaseId ?: return emptyList()
        return client.get("$baseUrl/api/manga-extras/$id").parseAs<ExtrasDto>().relations.mapNotNull { it.toSMangaOrNull() }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/manga/${manga.url}"

    private suspend fun fetchMangaPage(slug: String): MangaPageDto = client.get("$baseUrl/manga/$slug").extractNextJs<MangaPageDto>()
        ?: throw Exception("Impossible d'extraire les données du manga")

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() !in listOf("manga", "read")) return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return fetchMangaPage(slug).manga.toSManga()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter))
        // Locked chapters redirect to the sign-in page.
        if (response.request.url.encodedPath.startsWith("/sign-in")) {
            response.close()
            return emptyList()
        }

        val reader = response.extractNextJs<ReaderDto>()
            ?: throw Exception("Impossible d'extraire la liste des pages")
        if (reader.locked) return emptyList()

        return reader.pages.mapIndexed { index, page -> Page(index, imageUrl = page.src) }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        KindFilter(),
        FormatFilter(),
        StatusFilter(),
        GenreFilter(),
        Filter.Separator(),
        YearFilter(),
        MinRatingFilter(),
        MaxRatingFilter(),
    )
}
