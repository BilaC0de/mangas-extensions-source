package eu.kanade.tachiyomi.extension.fr.scanmanga

import android.content.ComponentName
import android.content.Intent
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.inflate
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicBoolean

@Source
abstract class ScanManga :
    KeiSource(),
    ConfigurableSource {

    // Registrable domain (baseUrl may be a subdomain like "m."), used to build the
    // static.<domain> image host and bqj.<domain> API host.
    private val domain by lazy { baseUrl.toHttpUrl().let { it.topPrivateDomain() ?: it.host } }
    private val baseImageUrl by lazy { "https://static.$domain/img/manga" }
    private val lazyPlaceholder by lazy { "https://static.$domain/img/lazy_130x45.jpg" }

    private val preferences by getPreferencesLazy()

    private val stripEmptyXRequestedWith = Interceptor { chain ->
        val request = chain.request()
        if (request.header("X-Requested-With")?.isEmpty() == true) {
            chain.proceed(request.newBuilder().removeHeader("X-Requested-With").build())
        } else {
            chain.proceed(request)
        }
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addNetworkInterceptor(stripEmptyXRequestedWith)

    // Reader-page fetches reuse the app client (cache, DoH, cookie jar...) but drop the host's
    // CloudflareInterceptor: it burns ~30 s per call on its own headless solve before throwing,
    // which would ruin our polling.
    private val readerClient: OkHttpClient by lazy {
        client.newBuilder().apply { interceptors().removeAll { it.javaClass.simpleName == "CloudflareInterceptor" } }.build()
    }

    // The "lel" API must be called without the shared cookie jar.
    private val noCookieClient: OkHttpClient by lazy {
        client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    }

    // "Referer" and "Origin" are already set by KeiSource; only add what's left.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .add("upgrade-insecure-requests", "1")
        .add(
            "accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        )
        .add("sec-fetch-site", "none")
        .add("accept-language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("X-Requested-With", "")

    // Popular
    override suspend fun getPopularManga(page: Int): MangasPage {
        val offset = (page - 1) * TOP_PAGE_SIZE
        if (offset >= TOP_MAX) return MangasPage(emptyList(), false)

        val siteUrl = "https://www.$domain"
        val topHeaders = headers.newBuilder()
            .set("User-Agent", TOP_USER_AGENT)
            .set("Origin", siteUrl)
            .set("Referer", "$siteUrl/TOP-Manga-Webtoon-47.html")
            .set("Accept", "text/html")
            .build()
        val requestBody = TopRequest(offset, TOP_PAGE_SIZE, "").toJsonRequestBody()

        val fragment = client.post("https://bqj.$domain/top.json", topHeaders, requestBody)
            .use { it.body.string().trim() }

        val document = Jsoup.parseBodyFragment(fragment, siteUrl)

        val mangas = document.select("div.image_listing").mapNotNull { element ->
            val link = element.selectFirst("a[href]") ?: return@mapNotNull null
            val mangaTitle = element.selectFirst("img")?.attr("title")
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            SManga.create().apply {
                title = mangaTitle
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = element.extractThumbnail()
            }
        }.ifEmpty {
            document.select("div.top").mapNotNull { element ->
                val link = element.selectFirst("a.atop") ?: return@mapNotNull null
                val mangaTitle = link.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null

                SManga.create().apply {
                    title = mangaTitle
                    setUrlWithoutDomain(link.absUrl("href"))
                    thumbnail_url = element.extractThumbnail()
                }
            }
        }

        val hasNextPage = mangas.size >= TOP_PAGE_SIZE && offset + TOP_PAGE_SIZE < TOP_MAX
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    /**
     * Thumbnails are lazy-loaded via `data-original`, but the first few entries of a page are
     * rendered without the lazy-load wrapper and carry the real URL in `src`. Fall back to
     * `src`, ignoring the fixed lazy placeholder image.
     */
    private fun Element.extractThumbnail(): String? {
        val img = selectFirst("img") ?: return null
        img.attr("data-original").takeIf { it.isNotEmpty() }?.let { return it }
        return img.absUrl("src").takeIf { it.isNotEmpty() && it != lazyPlaceholder }
    }

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()

        val mangas = document.select("#content_news .publi").mapNotNull { element ->
            val mangaElement = element.selectFirst("a.l_manga") ?: return@mapNotNull null

            SManga.create().apply {
                title = mangaElement.text()
                setUrlWithoutDomain(mangaElement.absUrl("href"))
                thumbnail_url = element.extractThumbnail()
            }
        }

        return MangasPage(mangas, false)
    }

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "https://bqj.$domain/search/quick.json"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("term", query)
            .build()

        // The API only answers when the request carries these exact headers (incl. Content-Type on a GET).
        val searchHeaders = headers.newBuilder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .set("Content-Type", "application/json; charset=UTF-8")
            .build()

        val json = client.get(url, searchHeaders).use { it.body.string().trim() }

        // The API answers with a bare array when nothing matches, which isn't the DTO shape.
        if (json.isEmpty() || json == "[]") {
            return MangasPage(emptyList(), false)
        }

        val mangas = json.parseAs<MangaSearchDto>().title.orEmpty().map {
            SManga.create().apply {
                title = it.nomMatch
                setUrlWithoutDomain(it.url)
                thumbnail_url = "$baseImageUrl/${it.image}"
            }
        }

        return MangasPage(mangas, false)
    }

    // URL search (e.g. pasting a manga link)
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.topPrivateDomain() != domain) return null

        val mangaUrl = SManga.create().apply { setUrlWithoutDomain(url.toString()) }.url
        val manga = SManga.create().apply { this.url = mangaUrl }

        // fetchMangaUpdate() leaves `url` untouched, so restore it on the returned entity.
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga.apply {
            this.url = mangaUrl
        }
    }

    // Details & Chapters
    // Both come from the same manga page, so fetch it once regardless of the flags.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get("$baseUrl${manga.url}").asJsoup()

        val updatedManga = SManga.create().apply {
            // itemprop may hold several values ("name headline"), so match on presence only; the
            // other h1.main_title ("Lecture en ligne - ...") never carries an itemprop.
            title = document.selectFirst("h1.main_title[itemprop]")?.text()
                ?: error("Failed to find manga title.")

            // The itemprop=author div only holds the label; the names are a text node on its parent.
            author = document.selectFirst("div[itemprop=author]")?.parent()?.ownText()?.takeIf { it.isNotEmpty() }

            description = document.selectFirst("div.titres_desc[itemprop=description]")?.text()

            // Same label/value split: the span holds the demographic, the tags are the parent's own text.
            genre = document.selectFirst("div.titres_souspart:has(span[itemprop=genre])")?.let { el ->
                listOf(el.selectFirst("span[itemprop=genre]")?.text(), el.ownText())
                    .filter { !it.isNullOrEmpty() }
                    .joinToString()
            }?.takeIf { it.isNotEmpty() }

            // Several div.titres_souspart blocks exist (author, genre, year, status, team...);
            // pick the one whose label says "Statut".
            val statutText = document.select("div.titres_souspart")
                .firstOrNull { it.selectFirst("> div")?.text()?.startsWith("Statut", ignoreCase = true) == true }
                ?.ownText()?.lowercase()
            status = when {
                statutText?.contains("en cours") == true -> SManga.ONGOING
                statutText?.contains("terminé") == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            thumbnail_url = document.selectFirst("div.full_img_serie img[itemprop=image]")
                ?.absUrl("src")
                ?.takeIf { it.isNotEmpty() }
        }

        // The "Team" block (itemprop=translator) holds the scanlation team as a link next to it.
        val scanlatorTeam = document.selectFirst("div[itemprop=translator]")?.parent()?.selectFirst("a")?.text()

        val newChapters = document.select("div.chapt_m").mapNotNull { element ->
            val linkEl = element.selectFirst("td.publimg span.i a") ?: return@mapNotNull null
            val extraTitle = element.selectFirst("td.publititle, td.publititle_ext")?.text()

            val chapterName = linkEl.text().replaceFirst(CHAPTER_PREFIX_REGEX, "Chapitre ")
            val href = linkEl.absUrl("href")
            // Licensed chapters link to a third-party reader instead of this site.
            val isLicensed = href.toHttpUrlOrNull()?.topPrivateDomain() != domain

            SChapter.create().apply {
                name = buildString {
                    if (isLicensed) append("🔒 ")
                    append(chapterName)
                    if (!extraTitle.isNullOrEmpty()) append(" - $extraTitle")
                }
                scanlator = scanlatorTeam
                if (isLicensed) {
                    // Keep the foreign absolute URL: setUrlWithoutDomain() would strip its host.
                    url = href
                } else {
                    setUrlWithoutDomain(href)
                }
            }
        }

        return SMangaUpdate(
            manga = updatedManga,
            chapters = newChapters,
        )
    }

    // Licensed chapters store their absolute (foreign) URL in chapter.url - see fetchMangaUpdate.
    private fun SChapter.isLicensed() = url.startsWith("http")

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.isLicensed()) chapter.url else super.getChapterUrl(chapter)

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.isLicensed()) {
            throw Exception("Ce chapitre est licencié.")
        }

        val chapterUrl = "$baseUrl${chapter.url}"

        // Cold session: CF won't issue cf_clearance to a session that never touched the host,
        // so warm it up in a hidden WebView and re-probe before bothering the user.
        val body = fetchChapterBody(chapterUrl)
            ?: run {
                warmupWebViewSession()
                fetchChapterBody(chapterUrl)
            }
            ?: solveChallengeInWebView(chapterUrl)

        return parsePageList(Jsoup.parse(body, chapterUrl))
    }

    private suspend fun fetchChapterBody(chapterUrl: String): String? = try {
        readerClient.get(chapterUrl, headers, ensureSuccess = false).use { resp ->
            resp.body.string().takeIf { CHAPTER_INFO_REGEX.containsMatchIn(it) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun solveChallengeInWebView(chapterUrl: String): String {
        val context = applicationContext
        val isReader = Exception().stackTrace.any { it.className.contains("reader") }

        try {
            val intent = Intent().apply {
                component = ComponentName(context, WEBVIEW_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("url_key", chapterUrl)
                putExtra("source_key", id)
                putExtra("title_key", CF_CHALLENGE_MESSAGE)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            throw Exception(CF_CHALLENGE_MESSAGE)
        }

        repeat(CF_MAX_POLLS) {
            delay(CF_POLL_INTERVAL_MS)
            val body = fetchChapterBody(chapterUrl) ?: return@repeat

            // Bring the app back to the foreground, closing the WebView.
            val closeIntent = Intent().apply {
                component = ComponentName(context, if (isReader) READER_ACTIVITY else MAIN_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(closeIntent)
            return body
        }

        // The warmup wasn't enough either: let the next attempt warm up again from scratch.
        sessionWarmedUp.set(false)
        throw Exception(CF_CHALLENGE_MESSAGE)
    }

    private val sessionWarmedUp = AtomicBoolean(false)

    private suspend fun warmupWebViewSession() {
        if (!sessionWarmedUp.compareAndSet(false, true)) return

        try {
            runWebView<Unit> {
                javaScriptEnabled = true
                domStorageEnabled = true
                onPageFinished { resolve(Unit) }
                loadUrl("$baseUrl/")
            }
            // Settle window so CF's Turnstile beacon can commit cf_clearance.
            delay(WARMUP_SETTLE_MS)
        } catch (e: CancellationException) {
            sessionWarmedUp.set(false)
            throw e
        } catch (_: Exception) {
            sessionWarmedUp.set(false)
        }
    }

    // Deliberately not a single-request page list: the "lel" data API needs sml/sme/chapterId
    // parsed out of the chapter page, so the second call depends on the first one's output.
    private suspend fun parsePageList(document: Document): List<Page> {
        val packedScript = document.selectFirst(PACKED_SCRIPT_SELECTOR)?.data()
            ?: error("Failed to find packed reader script.")
        val unpackedScript = decodeHunter(packedScript)

        val (sml) = SML_PARAM_REGEX.find(unpackedScript)?.destructured ?: error("Failed to extract sml parameter.")
        val (sme) = SME_PARAM_REGEX.find(unpackedScript)?.destructured ?: error("Failed to extract sme parameter.")
        val (chapterId) = CHAPTER_INFO_REGEX.find(packedScript)?.destructured ?: error("Failed to extract chapter ID.")

        val requestBody = LelRequestDto(a = sme, b = sml, c = getFingerprint())
            .toJsonString()
            .toRequestBody(LEL_MEDIA_TYPE)
        val requestHeaders = buildLelHeaders(document.baseUri().toHttpUrl())

        val payload = noCookieClient.post("https://bqj.$domain/lel/$chapterId.json", requestHeaders, requestBody)
            .use { dataAPI(it.body.string(), chapterId.toInt()) }

        return payload.generateImageUrls().map { Page(it.first, imageUrl = it.second) }
    }

    // The site's lel.js issues this as a same-site fetch(), not a navigation: it needs Client Hints
    // and cors-style sec-fetch-* values, and none of the navigation-only headers from
    // headersBuilder() (those caused 404s). Built from scratch so none of that leaks in.
    private fun buildLelHeaders(documentUrl: HttpUrl): Headers {
        val userAgent = headers["User-Agent"].orEmpty()
        val chromeMajor = CHROME_MAJOR_VERSION_REGEX.find(userAgent)?.groupValues?.get(1) ?: CHROME_MAJOR_FALLBACK
        val siteOrigin = "${documentUrl.scheme}://${documentUrl.host}"

        return Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Accept", "*/*")
            .add("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
            .add("sec-ch-ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"$chromeMajor\", \"Google Chrome\";v=\"$chromeMajor\"")
            .add("sec-ch-ua-mobile", "?1")
            .add("sec-ch-ua-platform", "\"Android\"")
            .add("sec-fetch-site", "same-site")
            .add("sec-fetch-mode", "cors")
            .add("sec-fetch-dest", "empty")
            .add("Origin", siteOrigin)
            .add("Referer", "$siteOrigin/")
            .add("Token", LEL_TOKEN)
            .add("source", documentUrl.toString())
            .add("Content-Type", LEL_MEDIA_TYPE.toString())
            .add("priority", "u=1, i")
            .build()
    }

    private fun decodeHunter(obfuscatedJs: String): String {
        val (encoded, mask, intervalStr, optionStr) = HUNTER_OBFUSCATION_REGEX.find(obfuscatedJs)?.destructured
            ?: error("Failed to match obfuscation pattern")

        val interval = intervalStr.toInt()
        val option = optionStr.toInt()
        val delimiter = mask[option]
        val tokens = encoded.split(delimiter).filter { it.isNotEmpty() }
        val reversedMap = mask.withIndex().associate { it.value to it.index }

        return buildString {
            for (token in tokens) {
                // Reverse hashIt(): masked characters back to digits
                val digitString = token.map { c ->
                    reversedMap[c]?.toString() ?: error("Invalid masked character: $c")
                }.joinToString("")

                // Digits are in base `option`; undo the shift applied by encodeIt()
                val number = digitString.toIntOrNull(option) ?: error("Failed to parse token: $digitString as base $option")

                append((number - interval).toChar())
            }
        }
    }

    private fun dataAPI(rawData: String, idc: Int): UrlPayload {
        val data = rawData.trim()

        // A valid payload is plain base64; anything else is an error message from the API.
        if (data.startsWith("error", ignoreCase = true) || !BASE64_REGEX.matches(data)) {
            error("Received error response from data API: ${WHITESPACE_REGEX.replace(data, " ").trim()}")
        }

        // base64 -> zlib inflate -> drop trailing hex(idc) -> reverse -> base64 -> JSON
        val inflated = Base64.decode(data, Base64.NO_WRAP or Base64.NO_PADDING).inflate().decodeToString()
        val reversed = inflated.removeSuffix(idc.toString(16)).reversed()

        return Base64.decode(reversed, Base64.DEFAULT).decodeToString().parseAs<UrlPayload>()
    }

    private suspend fun getFingerprint(): String {
        val gpu = preferences.getString(GPU_PREF_KEY, null)?.takeIf { it.isNotBlank() }
            ?: detectGpuRenderer()

        return Base64.encodeToString(
            FingerprintDto(gpu, "cellular").toJsonString().toByteArray(),
            Base64.NO_WRAP,
        )
    }

    // Only a value actually read from the WebView is cached; a timeout or failure falls back
    // to "IC" for this call only, so it never gets stored permanently.
    private suspend fun detectGpuRenderer(): String {
        val encoded = try {
            runWebView<String> {
                javaScriptEnabled = true
                onPageFinished { evaluateJs(GPU_SCRIPT) { result -> resolve(result) } }
                loadUrl("about:blank")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return GPU_FALLBACK
        }

        val gpu = encoded.removeSurrounding("\"")
            .takeIf { it.isNotEmpty() && it != "null" }
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT).decodeToString() }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return GPU_FALLBACK

        preferences.edit().putString(GPU_PREF_KEY, gpu).apply()
        return gpu
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = GPU_PREF_KEY
            title = "Unmasked GPU renderer"
            summary = "Override the cached GPU renderer string used to bypass fingerprint-based blocking."
            dialogTitle = "GPU Renderer"
            dialogMessage =
                "Enter your GPU renderer exactly as shown by a site like https://www.browserleaks.com/webgl " +
                "(open it in Google Chrome on Android), without any extra spaces or characters."
        }.also { screen.addPreference(it) }
    }

    companion object {
        private const val GPU_PREF_KEY = "gpu_renderer"
        private const val GPU_FALLBACK = "IC"
        private val GPU_SCRIPT = """
            (function() {
                try {
                    const canvas = document.createElement("canvas");
                    const gl = canvas.getContext("webgl");
                    const debugInfo = gl ? gl.getExtension("WEBGL_debug_renderer_info") : null;
                    const gpu = debugInfo ? gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL) : "IC";
                    return btoa(gpu);
                } catch (e) {
                    return btoa("IC");
                }
            })();
        """.trimIndent()

        private val CHAPTER_PREFIX_REGEX = Regex("""^Ch\.\s*""")
        private const val PACKED_SCRIPT_SELECTOR = "script:containsData(const idc)"
        private val HUNTER_OBFUSCATION_REGEX = Regex(
            """eval\s*\(\s*(?:/\*[^*]*\*/\s*)?function\s*\(\s*l\s*,\s*y\s*,\s*d\s*,\s*m\s*,\s*e\s*,\s*r\s*\)\s*\{[\s\S]*?\}\s*\(\s*"([^"]+)"\s*,\s*\d+\s*,\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*\d+\s*\)\s*\)""",
        )
        private val SML_PARAM_REGEX = Regex("""(?:const|let|var)?\s*sml\s*=\s*["']([^"']+)["']""")
        private val SME_PARAM_REGEX = Regex("""(?:const|let|var)?\s*sme\s*=\s*["']([^"']+)["']""")
        private val CHAPTER_INFO_REGEX = Regex("""(?:const|let|var)\s+idc\s*=\s*(\d+)""")
        private val CHROME_MAJOR_VERSION_REGEX = Regex("""Chrome/(\d+)""")
        private val BASE64_REGEX = Regex("""[A-Za-z0-9+/_=-]+""")
        private val WHITESPACE_REGEX = Regex("""\s+""")

        private const val CHROME_MAJOR_FALLBACK = "141"
        private val LEL_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        private const val LEL_TOKEN = "yf"

        private const val WEBVIEW_ACTIVITY = "eu.kanade.tachiyomi.ui.webview.WebViewActivity"
        private const val READER_ACTIVITY = "eu.kanade.tachiyomi.ui.reader.ReaderActivity"
        private const val MAIN_ACTIVITY = "eu.kanade.tachiyomi.ui.main.MainActivity"
        private const val CF_CHALLENGE_MESSAGE =
            "Résolvez le challenge Cloudflare, fermez la WebView et réouvrez le chapitre."
        private const val CF_POLL_INTERVAL_MS = 3000L
        private const val CF_MAX_POLLS = 20
        private const val WARMUP_SETTLE_MS = 200L
    }
}
