package eu.kanade.tachiyomi.extension.fr.scanmanga

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
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
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Inflater

@Source
abstract class ScanManga :
    KeiSource(),
    ConfigurableSource {

    // Registrable domain (baseUrl may be a subdomain like "m."), used to build the
    // static.<domain> image host and bqj.<domain> search/API host without duplicating
    // the literal domain string everywhere. Falls back to the raw host if resolution fails.
    private val domain = baseUrl.toHttpUrl().topPrivateDomain() ?: baseUrl.toHttpUrl().host
    private val baseImageUrl = "https://static.$domain/img/manga"

    private val preferences by getPreferencesLazy()

    private val stripEmptyXRequestedWith = Interceptor { chain ->
        val request = chain.request()
        val header = request.header("X-Requested-With")
        if (header != null && header.isEmpty()) {
            chain.proceed(request.newBuilder().removeHeader("X-Requested-With").build())
        } else {
            chain.proceed(request)
        }
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addNetworkInterceptor(stripEmptyXRequestedWith)

    // Reader-page fetches reuse the app client (cache, gzip, DoH, cookie jar, etc.) but strip
    // the host's CloudflareInterceptor — that interceptor wastes ~30 s per call trying its own
    // headless solve before throwing, blowing up our polling.
    private val readerClient: OkHttpClient by lazy {
        client.newBuilder().apply { interceptors().removeAll { it.javaClass.simpleName == "CloudflareInterceptor" } }.build()
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
     * The site lazy-loads thumbnails via `data-original`, but the first few entries on a page
     * are rendered without the lazy-load wrapper and have the real URL directly in `src`.
     * Fall back to `src`, excluding the known fixed lazy placeholder image.
     */
    private fun Element.extractThumbnail(): String? {
        val img = selectFirst("img") ?: return null
        val lazyUrl = img.attr("data-original")
        if (lazyUrl.isNotEmpty()) return lazyUrl

        val src = img.attr("abs:src")
        return src.takeIf { it.isNotEmpty() && it != LAZY_PLACEHOLDER }
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

        val searchHeaders = headers.newBuilder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .set("Content-Type", "application/json; charset=UTF-8")
            .build()

        val json = client.get(url, searchHeaders).use { it.body.string().trim() }

        if (json.isEmpty() || json == "[]") {
            return MangasPage(emptyList(), false)
        }

        val dto = json.parseAs<MangaSearchDto>()

        return MangasPage(
            dto.title?.map {
                SManga.create().apply {
                    title = it.nom_match
                    setUrlWithoutDomain(it.url)
                    thumbnail_url = "$baseImageUrl/${it.image}"
                }
            } ?: emptyList(),
            false,
        )
    }

    // URL search (e.g. pasting a manga link): resolve it into details on our own domain.
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.topPrivateDomain() != domain) return null

        val manga = SManga.create().apply {
            setUrlWithoutDomain(url.toString())
        }

        // fetchMangaUpdate() only fills in title/author/description/genre/status/thumbnail_url
        // on the SManga it returns - it deliberately leaves `url` untouched, because its usual
        // caller already knows the url of the entity it's updating and merges the returned
        // fields onto that existing entity. Here we're returning a brand-new standalone SManga
        // (not merging onto anything), so `url` - a lateinit var - would stay uninitialized and
        // throw UninitializedPropertyAccessException the moment anything reads manga.url.
        // Re-set it explicitly before returning.
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga.apply {
            setUrlWithoutDomain(url.toString())
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
            // itemprop can hold multiple space-separated values (e.g. "name headline"), so an
            // exact-match [itemprop=name] silently matches nothing on those pages. The other
            // h1.main_title on this page (the "Lecture en ligne - ..." one) never carries an
            // itemprop attribute at all, so presence alone is enough to disambiguate.
            title = document.selectFirst("h1.main_title[itemprop]")?.text()
                ?: error("Failed to find manga title.")

            // The itemprop=author div's own text is just the "Auteur/Artiste" label - the
            // actual names sit as a text node on its *parent*, alongside it.
            author = document.selectFirst("div[itemprop=author]")?.parent()?.ownText()?.trim()

            description = document.selectFirst("div.titres_desc[itemprop=description]")?.text()

            // Same label-vs-value split as author: the span only holds the demographic
            // ("Shonen"), the actual genre tag list is the parent's own text alongside it.
            val genreContainer = document.selectFirst("div.titres_souspart:has(span[itemprop=genre])")
            genre = genreContainer?.let { el ->
                val demographic = el.selectFirst("span[itemprop=genre]")?.text()?.trim()
                val tags = el.ownText().trim()
                listOfNotNull(demographic?.takeIf { it.isNotEmpty() }, tags.takeIf { it.isNotEmpty() })
                    .joinToString(", ")
            }?.takeIf { it.isNotEmpty() }

            // There are several div.titres_souspart blocks (author, genre, year, status,
            // team...) - selectFirst("div.titres_souspart") grabbed whichever came first
            // (the author one), never the status one, so this always resolved to UNKNOWN.
            // Target the block whose own label div actually says "Statut".
            val statutText = document.select("div.titres_souspart")
                .firstOrNull { it.selectFirst("> div")?.text()?.trim()?.startsWith("Statut", ignoreCase = true) == true }
                ?.ownText()?.lowercase()
            status = when {
                statutText?.contains("en cours") == true -> SManga.ONGOING
                statutText?.contains("terminé") == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            thumbnail_url = document.select("div.full_img_serie img[itemprop=image]").attr("abs:src")
        }

        // "Team" block (itemprop=translator) holds the scanlation team as a link next to it -
        // surfaced as `scanlator` on every chapter below, matching Tachiyomi's convention.
        val scanlatorTeam = document.selectFirst("div[itemprop=translator]")?.parent()?.selectFirst("a")?.text()

        val newChapters = document.select("div.chapt_m").mapNotNull { element ->
            val linkEl = element.selectFirst("td.publimg span.i a") ?: return@mapNotNull null
            val titleEl = element.selectFirst("td.publititle, td.publititle_ext")

            val chapterName = linkEl.text().replaceFirst(CHAPTER_PREFIX_REGEX, "Chapitre ")
            val extraTitle = titleEl?.text()
            val href = linkEl.absUrl("href")
            // Licensed chapters link to a third-party reader (e.g. lezhinfr.com) instead of
            // this site - detected by comparing the link's domain to our own.
            val isLicensed = href.toHttpUrlOrNull()?.topPrivateDomain() != domain

            SChapter.create().apply {
                name = buildString {
                    if (isLicensed) append("🔒 ")
                    append(chapterName)
                    if (!extraTitle.isNullOrEmpty()) append(" - $extraTitle")
                }
                scanlator = scanlatorTeam
                if (isLicensed) {
                    // Keep the absolute URL as-is: setUrlWithoutDomain() would strip the
                    // (foreign) host and silently turn it into a path on our own domain.
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

    // Licensed chapters have their absolute (foreign) URL stored directly in chapter.url,
    // instead of a relative path - see fetchMangaUpdate.
    private fun SChapter.isLicensed() = url.startsWith("http")

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.isLicensed()) chapter.url else super.getChapterUrl(chapter)

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.isLicensed()) {
            throw Exception("Ce chapitre est licencié.")
        }

        val context = applicationContext
        val chapterUrl = "$baseUrl${chapter.url}"
        val isReader = Exception().stackTrace.any { it.className.contains("reader") }

        suspend fun fetch(): String? = try {
            readerClient.get(chapterUrl, headers, ensureSuccess = false).use { resp ->
                resp.body.string().takeIf { CHAPTER_INFO_REGEX.containsMatchIn(it) }
            }
        } catch (_: Exception) {
            null
        }

        var body = fetch()
        if (body == null) {
            // Cold-session path: CF refuses to issue cf_clearance to a session that's
            // never touched the host. Warm up by loading the homepage in a hidden WV,
            // then re-probe — usually clears it without ever needing WebViewActivity.
            warmupWebViewSession()
            body = fetch()
        }
        if (body == null) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("url_key", chapterUrl)
                    putExtra("source_key", id)
                    putExtra("title_key", "Résolvez le challenge Cloudflare, fermez la WebView et réouvrez le chapitre.")
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                throw Exception("Résolvez le challenge Cloudflare depuis la WebView puis réouvrez le chapitre.")
            }

            for (attempt in 1..CF_MAX_POLLS) {
                delay(CF_POLL_INTERVAL_MS)
                body = fetch()
                if (body != null) {
                    val closeIntent = Intent().apply {
                        val target = if (isReader) {
                            "eu.kanade.tachiyomi.ui.reader.ReaderActivity"
                        } else {
                            "eu.kanade.tachiyomi.ui.main.MainActivity"
                        }
                        component = ComponentName(context, target)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(closeIntent)
                    break
                }
            }
            if (body == null) {
                // WV flow exhausted itself; the warmup we did wasn't enough either.
                // Clear the gate so the next attempt warms again from scratch.
                sessionWarmedUp.set(false)
                throw Exception("Résolvez le challenge Cloudflare, fermez la WebView et réouvrez le chapitre.")
            }
        }

        return parsePageList(Jsoup.parse(body, chapterUrl))
    }

    private val sessionWarmedUp = AtomicBoolean(false)

    @SuppressLint("SetJavaScriptEnabled")
    private fun warmupWebViewSession() {
        if (!sessionWarmedUp.compareAndSet(false, true)) return

        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            val wv = WebView(applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true

            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // Schedule teardown on the main looper directly — view.postDelayed
                    // is silently dropped because the WebView isn't attached to a window.
                    // The settle window lets CF's Turnstile beacon commit cf_clearance.
                    mainHandler.postDelayed(
                        {
                            runCatching {
                                view?.stopLoading()
                                view?.destroy()
                            }
                            latch.countDown()
                        },
                        WARMUP_SETTLE_MS,
                    )
                }
            }
            wv.loadUrl("$baseUrl/")
        }

        try {
            if (!latch.await(WARMUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                sessionWarmedUp.set(false)
            }
        } catch (_: InterruptedException) {
            sessionWarmedUp.set(false)
        }
    }

    // NOTE: This deliberately deviates from the standard single-request page-list flow.
    // The follow-up request to the "lel" data API needs parameters (sml/sme/chapterId/fingerprint)
    // extracted from the initial chapter page response, so it can't be expressed as a single
    // request - the second call is inherently dependent on the first one's parsed output.
    private suspend fun parsePageList(document: Document): List<Page> {
        val packedScript = document.selectFirst(PACKED_SCRIPT_SELECTOR)?.data()
            ?: error("Failed to find packed reader script.")
        val unpackedScript = decodeHunter(packedScript)

        val (sml) = SML_PARAM_REGEX.find(unpackedScript)?.destructured ?: error("Failed to extract sml parameter.")

        val (sme) = SME_PARAM_REGEX.find(unpackedScript)?.destructured ?: error("Failed to extract sme parameter.")

        val (chapterId) = CHAPTER_INFO_REGEX.find(packedScript)?.destructured ?: error("Failed to extract chapter ID.")

        val availableVariables = mapOf(
            "sme" to sme,
            "sml" to sml,
            "fingerprint" to getFingerprint(),
            "chapterId" to chapterId,
            "topDomain" to (baseUrl.toHttpUrl().topPrivateDomain() ?: ""),
        )

        val mediaType = "application/json; charset=UTF-8".toMediaType()
        val documentUrl = document.baseUri().toHttpUrl()

        val requestBody = injectVariables(REQUEST_BODY, availableVariables)
        val pageListUrl = injectVariables(PAGE_LIST_URL, availableVariables)
        val requestHeaders = buildLelHeaders(documentUrl)

        val noCookieClient = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

        val lelResponse = noCookieClient.post(pageListUrl, requestHeaders, requestBody.toRequestBody(mediaType)).use { response ->
            dataAPI(response.body.string(), chapterId.toInt())
        }

        return lelResponse.generateImageUrls().map { Page(it.first, imageUrl = it.second) }
    }

    // The real site's lel.js issues this as a same-site fetch() call, not a page navigation -
    // it needs Client Hints (sec-ch-ua*) and cors-style sec-fetch-* values that OkHttp never
    // sends on its own, and none of the navigation-only headers from headersBuilder() (those
    // caused the 404s: a real fetch() never sends upgrade-insecure-requests, and Accept there
    // is */*, not the text/html navigation list). Built from scratch rather than
    // headers.newBuilder() so none of that leaks in.
    private fun buildLelHeaders(documentUrl: HttpUrl): Headers {
        val userAgent = headers["User-Agent"].orEmpty()
        val chromeMajor = CHROME_MAJOR_VERSION_REGEX.find(userAgent)?.groupValues?.get(1) ?: "150"
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
            .add("Content-Type", "application/json; charset=UTF-8")
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
                // Reverse the hashIt() operation: convert masked characters back to digits
                val digitString = token.map { c ->
                    reversedMap[c]?.toString() ?: error("Invalid masked character: $c")
                }.joinToString("")

                // Convert from base `option` to decimal
                val number = digitString.toIntOrNull(option) ?: error("Failed to parse token: $digitString as base $option")

                // Reverse the shift done during encodeIt()
                val originalCharCode = number - interval

                append(originalCharCode.toChar())
            }
        }
    }

    private val multipleSpaces = Regex("""\s+""")

    private fun dataAPI(data: String, idc: Int): UrlPayload {
        if (data.contains("error")) {
            error("Received error response from data API: ${multipleSpaces.replace(data, " ").trim()}")
        }

        // Step 1: Base64 decode the input
        val compressedBytes = Base64.decode(data, Base64.NO_WRAP or Base64.NO_PADDING)

        // Step 2: Inflate (zlib decompress)
        val inflater = Inflater()
        inflater.setInput(compressedBytes)
        val outputBuffer = ByteArray(512 * 1024)
        val decompressedLength = inflater.inflate(outputBuffer)
        inflater.end()

        val inflated = String(outputBuffer, 0, decompressedLength)

        // Step 3: Remove trailing hex string and reverse
        val hexIdc = idc.toString(16)
        val cleaned = inflated.removeSuffix(hexIdc)
        val reversed = cleaned.reversed()

        // Step 4: Base64 decode and parse JSON
        val finalJsonStr = String(Base64.decode(reversed, Base64.DEFAULT))

        return finalJsonStr.parseAs<UrlPayload>()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun getFingerprint(): String {
        var currentValue = preferences.getString("gpu_renderer", null)

        if (currentValue.isNullOrEmpty()) {
            val latch = CountDownLatch(1)
            var returnValue = "SUMK"

            Handler(Looper.getMainLooper()).post {
                val webView = WebView(applicationContext)
                webView.settings.javaScriptEnabled = true

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val script = """
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

                        view?.evaluateJavascript(script) {
                            returnValue = it?.removeSurrounding("\"") ?: "SUMK"
                            view.stopLoading()
                            view.destroy()
                            latch.countDown()
                        }
                    }
                }
                webView.loadUrl("about:blank")
            }

            try {
                latch.await(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }

            val decodedValue = String(Base64.decode(returnValue, Base64.DEFAULT))

            preferences.edit().putString("gpu_renderer", decodedValue).apply()
            currentValue = decodedValue
        }

        return Base64.encodeToString(
            """{"gpu":"$currentValue","connection":"cellular"}""".toByteArray(),
            Base64.NO_WRAP,
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "gpu_renderer"
            title = "Unmasked GPU renderer"
            summary =
                "Set and cache your GPU renderer string here to bypass fingerprint-based blocking. You can find your GPU renderer by visiting a site like https://www.browserleaks.com/webgl. Make sure to enter the exact string as shown on the site, without any extra spaces or characters and use Google Chrome on Android."
            setDefaultValue(null)
            dialogTitle = "GPU Renderer"
            dialogMessage =
                "Enter your GPU renderer string here. This is used to bypass blocking based on WebGL fingerprinting. You can find your GPU renderer by visiting a site like https://www.browserleaks.com/webgl using Google Chrome on Android. Make sure to enter the exact string as shown on the site, without any extra spaces or characters."

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also { screen.addPreference(it) }
    }

    private fun injectVariables(template: String, variables: Map<String, String>): String {
        var result = template
        for ((key, value) in variables) {
            result = result.replace("{$key}", value)
        }
        return result
    }

    companion object {
        private const val LAZY_PLACEHOLDER = "https://static.scan-manga.com/img/lazy_130x45.jpg"
        private val CHAPTER_PREFIX_REGEX = Regex("""^Ch\.\s*""")
        private const val PACKED_SCRIPT_SELECTOR = "script:containsData(const idc)"
        private val HUNTER_OBFUSCATION_REGEX = Regex(
            """eval\s*\(\s*(?:/\*[^*]*\*/\s*)?function\s*\(\s*l\s*,\s*y\s*,\s*d\s*,\s*m\s*,\s*e\s*,\s*r\s*\)\s*\{[\s\S]*?\}\s*\(\s*"([^"]+)"\s*,\s*\d+\s*,\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*\d+\s*\)\s*\)""",
        )
        private val SML_PARAM_REGEX = Regex(
            """(?:const|let|var)?\s*sml\s*=\s*["']([^"']+)["']""",
        )
        private val SME_PARAM_REGEX = Regex(
            """(?:const|let|var)?\s*sme\s*=\s*["']([^"']+)["']""",
        )
        private val CHAPTER_INFO_REGEX = Regex(
            """(?:const|let|var)\s+idc\s*=\s*(\d+)""",
        )
        private val CHROME_MAJOR_VERSION_REGEX = Regex("""Chrome/(\d+)""")
        private const val PAGE_LIST_URL = "https://bqj.{topDomain}/lel/{chapterId}.json"
        private const val REQUEST_BODY = """{"a":"{sme}","b":"{sml}","c":"{fingerprint}"}"""
        private const val LEL_TOKEN = "yf"
        private const val CF_POLL_INTERVAL_MS = 5000L
        private const val CF_MAX_POLLS = 15
        private const val WARMUP_SETTLE_MS = 200L
        private const val WARMUP_TIMEOUT_SECONDS = 8L
    }
}
