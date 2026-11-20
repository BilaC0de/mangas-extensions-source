package eu.kanade.tachiyomi.extension.fr.scanmanga

import android.content.ComponentName
import android.content.Intent
import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.inflate
import keiyoushi.utils.ownTextOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Source
abstract class ScanManga :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val popularDesktop get() = preferences.getBoolean(POPULAR_DESKTOP_PREF_KEY, false)

    private val domain get() = baseUrl.toHttpUrl().topPrivateDomain()!!
    private val baseImageUrl get() = "https://static.$domain/img/manga"
    private val baseSearchUrl get() = "https://bqj.$domain/search/quick.json"
    private val lazyPlaceholder get() = "https://static.$domain/img/lazy_130x45.jpg"

    // The "lel" API must be called without the shared cookie jar
    private val noCookieClient: OkHttpClient by lazy { client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build() }

    private val stripEmptyXRequestedWith = Interceptor { chain ->
        val request = chain.request()
        val header = request.header("X-Requested-With")
        if (header != null && header.isEmpty()) {
            chain.proceed(request.newBuilder().removeHeader("X-Requested-With").build())
        } else {
            chain.proceed(request)
        }
    }

    // A refused page image redirects to a valid placeholder PNG, which would otherwise be shown and downloaded as the page
    private val rejectPlaceholder = Interceptor { chain ->
        chain.proceed(chain.request()).also {
            if ("/lelsec" in it.request.url.encodedPath) it.use { throw IOException("Le site a refusé l'image de cette page.") }
        }
    }

    // Only the site's own pages and APIs are throttled: the covers and page images come from other hosts
    private val apiHosts get() = setOf(baseUrl.toHttpUrl().host, "www.$domain", "bqj.$domain")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addNetworkInterceptor(stripEmptyXRequestedWith)
        .addInterceptor(rejectPlaceholder)
        .rateLimit(permits = 2, period = 1.seconds) { it.host in apiHosts }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("accept-language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("X-Requested-With", "")

    // Popular
    override suspend fun getPopularManga(page: Int): MangasPage = if (popularDesktop) getTopPage(TOP_ALL_PATH, page) else getPopularMobile()

    private val topConfigs = ConcurrentHashMap<String, TopSeriesConfig>()

    // The desktop TOP pages (Popular and the categories) list vertical covers; the mobile one serves horizontal ones.
    // Page 1 is the page itself, the next ones come from the site's infinite scroll, which the page configures.
    private suspend fun getTopPage(path: String, page: Int): MangasPage {
        val siteUrl = "https://www.$domain"
        val topUrl = "$siteUrl$path"
        val desktopHeaders = headers.newBuilder()
            .set("User-Agent", desktopUserAgent)
            .build()

        if (page == 1) {
            val document = client.get(topUrl, desktopHeaders, Fetch.DOCUMENT).asJsoup()
            val config = document.topConfig(path)
            val list = document.selectFirst("#liste_series")?.toTopList() ?: TopList(emptyList(), 0)
            return MangasPage(list.mangas, config != null && list.total >= config.offset)
        }

        val config = topConfigs[path]
            ?: client.get(topUrl, desktopHeaders, Fetch.DOCUMENT).asJsoup().topConfig(path)
            ?: return MangasPage(emptyList(), false)
        val offset = config.offset + (page - 2) * config.limit
        if (offset >= config.max) return MangasPage(emptyList(), false)

        val scrollHeaders = desktopHeaders.newBuilder()
            .set("Origin", siteUrl)
            .set("Referer", topUrl)
            .set("Accept", "text/html")
            .build()
        val body = TopRequest(offset, config.limit, config.top).toJsonRequestBody()

        val fragment = client.post("https://bqj.$domain/top.json", scrollHeaders, Fetch.XHR, body)
            .use { it.body.string().trim() }
        val list = Jsoup.parseBodyFragment(fragment, siteUrl).toTopList()

        return MangasPage(list.mangas, list.total >= config.limit && offset + config.limit < config.max)
    }

    // The page configures its infinite scroll: entries it already holds, step, maximum and the category sent to top.json
    private fun Document.topConfig(path: String): TopSeriesConfig? {
        val config = select("script").firstNotNullOfOrNull { TOP_CONFIG_REGEX.find(it.data())?.groupValues?.get(1) }
            ?.parseAs<TopSeriesConfig>()
            ?: TopSeriesConfig(TOP_HTML_SIZE, TOP_PAGE_SIZE, TOP_MAX, "").takeIf { path == TOP_ALL_PATH }
        return config?.also { topConfigs[path] = it }
    }

    /** [total] counts the entries received, novels included, since it tells whether another page follows. */
    private class TopList(val mangas: List<SManga>, val total: Int)

    private fun Element.toTopList(): TopList {
        val mangas = select("div.image_listing").mapNotNull { element ->
            val link = element.selectFirst("a[href]") ?: return@mapNotNull null
            val mangaTitle = element.selectFirst("img")?.attr("title")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            SManga.create().apply {
                title = mangaTitle
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = element.extractThumbnail()
            }
        }.distinctBy { it.url }

        return TopList(mangas.withoutNovels(), mangas.size)
    }

    // The desktop TOP pages are served to a desktop User-Agent: the version follows the WebView's
    private val desktopUserAgent: String
        get() {
            val chrome = Regex("""Chrome/(\d+)""").find(headers["User-Agent"].orEmpty())?.groupValues?.get(1) ?: "140"
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.0.0 Safari/537.36"
        }

    private suspend fun getPopularMobile(): MangasPage {
        val mangas = client.get("$baseUrl/TOP-Manga-Webtoon-47.html", Fetch.DOCUMENT).asJsoup().select("#carouselTOPContainer div.top").mapNotNull { element ->
            val titleElement = element.selectFirst("a.atop") ?: return@mapNotNull null

            SManga.create().apply {
                title = titleElement.text()
                setUrlWithoutDomain(titleElement.attr("href"))
                thumbnail_url = element.extractThumbnail()
            }
        }

        return MangasPage(mangas.withoutNovels(), false)
    }

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val mangas = client.get(baseUrl, Fetch.DOCUMENT).asJsoup().select("#content_news .publi").mapNotNull { element ->
            val mangaElement = element.selectFirst("a.l_manga") ?: return@mapNotNull null

            SManga.create().apply {
                title = mangaElement.text()
                setUrlWithoutDomain(mangaElement.attr("href"))
                thumbnail_url = element.extractThumbnail()
            }
        }

        return MangasPage(mangas.withoutNovels(), false)
    }

    // The site also lists a few novels, which Mihon can't read; their title ends with "(Novel)"
    private fun List<SManga>.withoutNovels(): List<SManga> = filterNot { it.title.trimEnd().endsWith(NOVEL_TITLE_SUFFIX, ignoreCase = true) }

    // Thumbnails are lazy-loaded through `data-original`, but the first entries of a page carry the real URL in `src`
    private fun Element.extractThumbnail(): String? {
        val img = selectFirst("img") ?: return null
        img.attr("data-original").takeIf { it.isNotEmpty() }?.let { return it }
        return img.absUrl("src").takeIf { it.isNotEmpty() && it != lazyPlaceholder }
    }

    // Search
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.topPrivateDomain() != domain || !MANGA_PATH_REGEX.matches(url.encodedPath)) return null

        return mangaDetailsParse(client.get(baseUrl + url.encodedPath, Fetch.DOCUMENT).asJsoup()).apply {
            this.url = url.encodedPath
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // The site has no advanced search: a chosen TOP lists that top, unless a text is typed
        val topPath = filters.filterIsInstance<TopFilter>().firstOrNull()?.let { TOP_CATEGORIES.getOrNull(it.state - 1)?.second }
        if (query.isBlank() && topPath != null) return getTopPage(topPath, page)

        val url = baseSearchUrl.toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .build()

        val searchHeaders = headers.newBuilder()
            .add("Content-type", "application/json; charset=UTF-8")
            .build()

        // No results come back as `[]` instead of an object
        val mangas = client.get(url, searchHeaders, Fetch.XHR).parseAs<MangaSearchDto> { if (it == "[]") """{"title":null}""" else it }
            .title?.map {
                SManga.create().apply {
                    title = it.nomMatch
                    setUrlWithoutDomain(it.url)
                    thumbnail_url = "$baseImageUrl/${it.image}"
                }
            }.orEmpty()

        return MangasPage(mangas.withoutNovels(), false)
    }

    // Details + chapters (same page)
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        return SMangaUpdate(
            mangaDetailsParse(document),
            if (fetchChapters) chapterListParse(document) else chapters,
        )
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("meta[itemprop=name]")!!.attr("content")
        // The itemprop div only holds the "Auteur/Artiste" label
        author = document.selectFirst("div[itemprop=author]")?.parent()?.ownTextOrNull()
        description = document.selectFirst("div.titres_desc[itemprop=description]")?.text()
        genre = document.selectFirst("span[itemprop=genre]")?.let { demo ->
            (listOf(demo.text()) + demo.parent()?.ownText().orEmpty().split(","))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString()
        }

        val statutText = document.selectFirst("div.titres_souspart:has(div:containsOwn(Statut))")?.ownText()?.lowercase().orEmpty()
        status = when {
            "en cours" in statutText -> SManga.ONGOING
            "terminé" in statutText -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        thumbnail_url = document.selectFirst("meta[itemprop=image]")?.absUrl("content")
    }

    private fun chapterListParse(document: Document): List<SChapter> {
        // The scanlation team is a link next to its "Team" label (itemprop=translator)
        val scanlatorTeam = document.selectFirst("div[itemprop=translator]")?.parent()?.selectFirst("a")?.text()

        return document.select("div.chapt_m").mapNotNull { element ->
            val linkEl = element.selectFirst("td.publimg span.i a") ?: return@mapNotNull null
            val href = linkEl.absUrl("href").ifEmpty { return@mapNotNull null }
            val extraTitle = element.selectFirst("td.publititle, td.publititle_ext")?.text()

            val chapterName = linkEl.text().replaceFirst(CHAPTER_PREFIX_REGEX, "Chapitre ")
            // Licensed chapters link to a publisher's own reader (izneo, mangaplus, ono.live...) instead of this site
            val licensed = href.toHttpUrlOrNull()?.topPrivateDomain() != domain || "/lecture-en-ligne/" !in href

            SChapter.create().apply {
                name = buildString {
                    if (licensed) append("🔒 ")
                    append(chapterName)
                    if (!extraTitle.isNullOrEmpty()) append(" - $extraTitle")
                }
                scanlator = scanlatorTeam
                // setUrlWithoutDomain() would strip the publisher's host, so its absolute URL is kept as is
                if (licensed) url = href else setUrlWithoutDomain(href)
            }
        }
    }

    // Licensed chapters keep the publisher's absolute URL in chapter.url
    private fun SChapter.isLicensed() = url.startsWith("http")

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.isLicensed()) chapter.url else super.getChapterUrl(chapter)

    // Pages
    private val multipleSpaces = Regex("""\s+""")

    // A valid answer is plain base64: anything else is an error message from the API
    private fun String.isDataApiError(): Boolean {
        val answer = trim()
        return answer.startsWith("error", ignoreCase = true) || !BASE64_REGEX.matches(answer)
    }

    private fun dataAPI(data: String, idc: Int): UrlPayload {
        val answer = data.trim()
        if (answer.isDataApiError()) {
            error("Received error response from data API: ${multipleSpaces.replace(answer, " ")}")
        }

        val inflated = String(Base64.decode(answer, Base64.NO_WRAP or Base64.NO_PADDING).inflate())

        // Remove trailing hex string and reverse
        val reversed = inflated.removeSuffix(idc.toString(16)).reversed()

        return String(Base64.decode(reversed, Base64.DEFAULT)).parseAs<UrlPayload>()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.isLicensed()) {
            val publisher = chapter.url.toHttpUrlOrNull()?.topPrivateDomain() ?: "le site de l'éditeur"
            throw Exception("Chapitre licencié : disponible uniquement sur $publisher.")
        }

        val chapterUrl = getChapterUrl(chapter)
        val chapterId = chapterUrl.substringAfterLast('_').substringBefore('.').toInt()
        val lelCall = captureLelCall(chapterUrl, chapterId)
            ?: throw Exception("Challenge Cloudflare. Résolvez-le dans la WebView, fermez-la et réessayez.")

        // An error answer gets one more try
        var data = sendLelCall(lelCall)
        if (data.isDataApiError()) {
            delay(500)
            data = sendLelCall(lelCall)
        }

        return dataAPI(data, chapterId)
            .generateImageUrls()
            .map { Page(it.first, url = chapterUrl, imageUrl = it.second) }
    }

    /**
     * The page's own request is blocked by CORS in a WebView, so the captured call is sent from the app instead. The API
     * answers HTTP 200 with an error object unless the call looks like a browser's: its origin, Sec-Fetch headers, no cookies.
     */
    private suspend fun sendLelCall(lelCall: LelCall): String = withContext(Dispatchers.IO) {
        val origin = lelCall.headers["source"]?.toHttpUrlOrNull()?.run { "$scheme://$host" } ?: baseUrl
        val request = Request.Builder()
            .url(lelCall.url)
            .headers(
                headers.newBuilder().apply {
                    lelCall.headers.forEach { (name, value) -> set(name, value) }
                    set("Origin", origin)
                    set("Referer", "$origin/")
                    set("Accept", "*/*")
                    set("Sec-Fetch-Dest", "empty")
                    set("Sec-Fetch-Mode", "cors")
                    set("Sec-Fetch-Site", "same-site")
                }.build(),
            )
            .post(lelCall.body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()

        noCookieClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("La liste des pages a été refusée (HTTP ${response.code}).")
            response.body?.string().orEmpty()
        }
    }

    // The image host answers with a placeholder unless the Referer is the chapter page in full, as the reader sends it.
    // Page lists cached before this have no url.
    override fun getImageRequest(page: Page): Request = super.getImageRequest(page).run {
        if (page.url.isEmpty()) this else newBuilder().header("Referer", page.url).build()
    }

    // The site answers app WebViews with a fake 404 on every request carrying their default value
    private val siteOrigins get() = setOf("https://*.$domain")

    private val webViewMutex = Mutex()

    // A download queue fails its chapters within moments, which would otherwise load and show the challenge for each
    private var challengeRetryAt = TimeSource.Monotonic.markNow()

    /** Null while the user has to solve a challenge. */
    private suspend fun captureLelCall(chapterUrl: String, chapterId: Int): LelCall? = webViewMutex.withLock {
        if (challengeRetryAt.hasNotPassedNow()) return@withLock null

        val lelCall = readLelCall(chapterUrl, chapterId)
        if (lelCall == null) {
            challengeRetryAt = TimeSource.Monotonic.markNow() + 3.seconds
            openChallengeWebView(chapterUrl)
        }
        lelCall
    }

    /**
     * Cloudflare only accepts the reader's cf_clearance from a WebView, so the site's own reader runs in a hidden one
     * and the lel call it makes is captured. Null as soon as a challenge shows up.
     */
    private suspend fun readLelCall(chapterUrl: String, chapterId: Int): LelCall? {
        val bridge = List(16) { ('a'..'z').random() }.joinToString("")

        return runWebView<LelCall?>(45.seconds) {
            userAgent = headers["User-Agent"]!!
            blockImages = true
            setOriginMatchedHeader("X-Requested-With", "", siteOrigins)
            webMessageBridge(bridge, siteOrigins) { message ->
                val lelCall = message.parseAs<LelCall>()
                // Third-party scripts share this page and can call the hook too
                if (lelCall.url.toHttpUrlOrNull()?.topPrivateDomain() == domain) resolve(lelCall)
            }
            injectAtDocumentStart(lelHook(bridge, chapterId), siteOrigins)
            onReceivedError { request, error ->
                if (request.isForMainFrame) reject(Exception(error.description.toString()))
            }
            poll {
                // On mature titles the reader only starts once this warning is accepted
                evaluateJs("""[...document.querySelectorAll(".oui-avertissement-btn")].find((button) => button.offsetParent)?.click()""")
                // "challenge", or the page's HTTP status once it has loaded: a challenge page is a 403 too
                evaluateJs("""window._cf_chl_opt ? "challenge" : document.readyState == "complete" && performance.getEntriesByType("navigation")[0].responseStatus""") {
                    if (it == "\"challenge\"") resolve(null)
                    if ((it.toIntOrNull() ?: 0) >= 400) reject(Exception("Le site a répondu HTTP $it pour ce chapitre."))
                }
            }
            loadUrl(chapterUrl)
        }
    }

    private fun lelHook(bridge: String, chapterId: Int) = """
        (function () {
            const original = window.fetch;
            window.fetch = function (input, init) {
                if (!String(input.url || input).includes("/lel/$chapterId.json")) return original.call(this, input, init);
                const request = new Request(input, init);
                request.text().then((body) => window.$bridge.postMessage(JSON.stringify({ url: request.url, headers: Object.fromEntries(request.headers), body })));
                return new Promise(() => {});
            };
        })();
    """.trimIndent()

    private fun openChallengeWebView(chapterUrl: String) {
        val intent = Intent().apply {
            component = ComponentName(applicationContext, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("url_key", chapterUrl)
            putExtra("source_key", id)
        }
        // Apps without this activity (Suwayomi etc.) still get the error message
        runCatching { applicationContext.startActivity(intent) }
    }

    // The reader downloads pages with fetch(); the image host blocks requests sent as images
    override val imageFetch get() = Fetch.XHR

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Le Top est ignoré dès qu'une recherche est saisie."),
        TopFilter(),
    )

    private class TopFilter : Filter.Select<String>("Top", arrayOf("Aucun") + TOP_CATEGORIES.map { it.first })

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = POPULAR_DESKTOP_PREF_KEY
            title = "Populaire : version PC du site"
            summary = "Charge la page Populaire avec un user agent PC pour obtenir les covers verticales. Si désactivé, la version mobile du site est utilisée."
            setDefaultValue(false)
        }.also { screen.addPreference(it) }
    }

    companion object {
        private const val POPULAR_DESKTOP_PREF_KEY = "popular_desktop"
        private const val NOVEL_TITLE_SUFFIX = "(Novel)"

        private const val TOP_ALL_PATH = "/TOP-Manga-Webtoon-47.html"
        private val TOP_CONFIG_REGEX = Regex("""topSeriesConfig\s*=\s*(\{[^}]*\})""")

        // The categories of the site's TOP, as listed on its pages (the novels are left out)
        private val TOP_CATEGORIES = listOf(
            "BD" to "/TOP-BD-7.html",
            "One Shot" to "/TOP-One-Shot-3.html",
            "Webcomic" to "/TOP-Webcomics-14.html",
            "Shonen" to "/TOP-Shonen-56.html",
            "Seinen" to "/TOP-Seinen-13.html",
            "Ecchi" to "/TOP-Ecchi-11.html",
            "Isekai" to "/TOP-Isekai-58.html",
            "Shojo" to "/TOP-Shojo-40.html",
            "Josei" to "/TOP-Josei-28.html",
            "Shonen-Ai" to "/TOP-Shonen-Ai-8.html",
            "Shojo-Ai" to "/TOP-Shojo-Ai-3.html",
            "Yuri" to "/TOP-Yuri-34.html",
            "Yaoi" to "/TOP-Yaoi-111.html",
            "Adulte" to "/TOP-Adulte-57.html",
            "Licencié" to "/TOP-Licencie-37.html",
        )

        // The desktop TOP page holds the first 100 entries, the infinite scroll then loads 50 at a time up to 5000
        private const val TOP_HTML_SIZE = 100
        private const val TOP_PAGE_SIZE = 50
        private const val TOP_MAX = 5000
        private val MANGA_PATH_REGEX = Regex("""/\d+(?:-\d+)?/[^/]+\.html""")
        private val CHAPTER_PREFIX_REGEX = Regex("""^Ch\.\s*""")

        // Base64 (standard or URL-safe alphabet), possibly wrapped over several lines
        private val BASE64_REGEX = Regex("""[A-Za-z0-9+/_=\s-]+""")
    }
}
