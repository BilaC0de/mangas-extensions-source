package eu.kanade.tachiyomi.multisrc.pam

import android.content.ComponentName
import android.content.Intent
import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.lib.i18n.Intl
import keiyoushi.lib.secretstream.SecretStream
import keiyoushi.lib.secretstream.State
import keiyoushi.lib.secretstream.X25519
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParseDateTime
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.net.URLDecoder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

abstract class Pam :
    KeiSource(),
    ConfigurableSource {

    protected val baseHttpUrl = baseUrl.toHttpUrl()

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    protected val intl = Intl(
        language = lang,
        baseLanguage = "en",
        availableLanguages = setOf("en", "fr"),
        classLoader = this::class.java.classLoader!!,
    )

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        addInterceptor(::imageInterceptor)
        rateLimit(1, 2.seconds) { it.fragment != THUMBNAIL_FRAGMENT }
        return this
    }

    private var version: String? = null
    private var csrfToken: String? = null

    @Synchronized
    private fun apiRequest(
        url: HttpUrl,
        body: RequestBody? = null,
        includeXSRFToken: Boolean,
        includeCSRFToken: Boolean,
        includeVersion: Boolean,
    ): Request {
        var xsrfToken = client.cookieJar.loadForRequest(baseHttpUrl)
            .firstOrNull { it.name == "XSRF-TOKEN" }?.let { URLDecoder.decode(it.value, "UTF-8") }

        if (
            (includeXSRFToken && xsrfToken == null) ||
            (includeCSRFToken && csrfToken == null) ||
            (includeVersion && version == null)
        ) {
            val document = client.newCall(GET(baseHttpUrl, headers)).execute()
                .also {
                    if (!it.isSuccessful) {
                        it.close()
                        throw Exception("HTTP Error ${it.code}")
                    }
                }
                .asJsoup()

            version = document.selectFirst("#app")!!
                .attr("data-page")
                .parseAs<Version>().version

            csrfToken = document.selectFirst("meta[name=csrf-token]")!!
                .attr("content")

            xsrfToken = client.cookieJar.loadForRequest(baseHttpUrl)
                .first { it.name == "XSRF-TOKEN" }.let { URLDecoder.decode(it.value, "UTF-8") }
        }

        val headers = headersBuilder().apply {
            set("Accept", "application/json")
            set("X-Requested-With", "XMLHttpRequest")
            if (includeVersion) {
                set("X-Inertia", "true")
                set("X-Inertia-Version", version!!)
            }
            if (includeXSRFToken) {
                set("X-XSRF-TOKEN", xsrfToken!!)
            }
            if (includeCSRFToken) {
                set("X-CSRF-TOKEN", csrfToken!!)
            }
        }.build()

        return if (body != null) {
            POST(url.toString(), headers, body)
        } else {
            GET(url, headers)
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchManga(page, "", latestFilters)
    override suspend fun getPopularManga(page: Int): MangasPage = getSearchManga(page, "", popularFilters)

    protected abstract val popularFilters: FilterList
    protected abstract val latestFilters: FilterList

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request: Request
        if (query.isNotEmpty()) {
            val url = baseHttpUrl.newBuilder().apply {
                addPathSegments("api/v1/search/series")
                addQueryParameter("q", query)
            }.build()

            request = apiRequest(
                url,
                includeXSRFToken = true,
                includeCSRFToken = false,
                includeVersion = false,
            )
        } else {
            val url = baseHttpUrl.newBuilder().apply {
                addPathSegment("library")
                if (page > 1) {
                    addQueryParameter("page", page.toString())
                }

                filters.filterIsInstance<TriStateGroupFilter>().forEach { group ->
                    when (group.name) {
                        "Genres", "Genres/Thèmes" -> {
                            group.included.takeIf { it.isNotEmpty() }?.also { addQueryParameter("include_genres", it.joinToString(",")) }
                            group.excluded.takeIf { it.isNotEmpty() }?.also { addQueryParameter("exclude_genres", it.joinToString(",")) }
                        }

                        "Types" -> {
                            group.included.takeIf { it.isNotEmpty() }?.also { addQueryParameter("include_types", it.joinToString(",")) }
                            group.excluded.takeIf { it.isNotEmpty() }?.also { addQueryParameter("exclude_types", it.joinToString(",")) }
                        }
                    }
                }

                filters.firstInstanceOrNull<CheckBoxGroup>()?.also { status ->
                    if (status.checked.isNotEmpty()) {
                        addQueryParameter("status", status.checked.joinToString(","))
                    }
                }
                filters.firstInstanceOrNull<SortFilter>()?.also { sort ->
                    addQueryParameter("orderby", sort.sort)
                    if (sort.ascending) {
                        addQueryParameter("order", "asc")
                    }
                }
            }.build()

            request = apiRequest(
                url,
                includeXSRFToken = true,
                includeCSRFToken = false,
                includeVersion = false,
            )
        }

        val response = client.newCall(request).execute()

        if (response.request.url.queryParameter("q") != null) {
            val data = response.parseAs<SearchResponse>().data

            return MangasPage(
                mangas = data.map { it.toSManga(::createThumbnailUrl) },
                hasNextPage = false,
            )
        } else {
            val data = response.parseAs<LibraryResponse>().series

            return MangasPage(
                mangas = data.data.map { it.toSManga(::createThumbnailUrl) },
                hasNextPage = data.meta?.let { it.current < it.last } ?: false,
            )
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/serie/${manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.takeIf { it.size >= 2 && it[0] == "serie" }
            ?.get(1)
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return toSManga(fetchSerie(slug))
    }

    private fun fetchSerie(slug: String): MangaResponse.Props.Manga {
        val request = apiRequest(
            "$baseUrl/serie/$slug".toHttpUrl(),
            includeXSRFToken = true,
            includeCSRFToken = false,
            includeVersion = true,
        )

        return client.newCall(request).execute().parseAs<MangaResponse>().props.serie
    }

    private fun toSManga(data: MangaResponse.Props.Manga): SManga = SManga.create().apply {
        url = data.slug
        title = data.title
        thumbnail_url = createThumbnailUrl(data.image)
        author = data.author
        artist = data.artist
        description = buildString {
            data.description?.also {
                append(it.trim(), "\n\n")
            }
            data.releaseYear?.also {
                append(intl["release_year"], ": ", it, "\n\n")
            }
            data.alternativeName?.also {
                append(intl["alternative_names"], ": ", it)
            }
        }.trim()
        genre = buildList {
            data.type?.name?.also(::add)
            data.genres.mapTo(this) { it.name }
        }.joinToString()
        status = when (data.status?.lowercase()) {
            "ongoing", "upcoming" -> SManga.ONGOING
            "finished" -> SManga.COMPLETED
            "dropped" -> SManga.CANCELLED
            "onhold" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val data = fetchSerie(manga.url)

        val hidePremium = preferences.getBoolean(HIDE_PREMIUM_PREF, false)
        val chapterList = data.chapters.filter { !(it.isPremium && hidePremium) }.map {
            SChapter.create().apply {
                url = "/serie/${data.slug}/chapter/${it.slug}"
                name = buildString {
                    if (it.isPremium) {
                        append("\uD83D\uDD12 ")
                    }
                    append(it.title)
                }
                date_upload = it.createdAt.substringBefore(".").let { dateStr ->
                    dateFormat.tryParseDateTime(dateStr, ZoneOffset.UTC)
                }
            }
        }.asReversed()

        return SMangaUpdate(toSManga(data), chapterList)
    }

    protected open fun createThumbnailUrl(imagePath: String?): String? {
        if (imagePath == null) return null
        return "$baseUrl$imagePath#$THUMBNAIL_FRAGMENT"
    }

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = HIDE_PREMIUM_PREF
            title = intl["pref_hide_premium_title"]
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Evaluated first: the call stack no longer shows the Downloader after a thread switch.
        val isDownload = isDownloadContext()

        val url = "$baseUrl${chapter.url}".toHttpUrl()

        val request = apiRequest(
            url,
            includeXSRFToken = true,
            includeCSRFToken = false,
            includeVersion = true,
        )

        val response = client.newCall(request).execute()

        var body = response.parseAs<PageListResponse>()
        if (body.props.captchaPending()) {
            body = awaitCaptcha(chapter, isDownload)
        }
        val props = body.props

        val id = sessionKey(props.data.serie.slug, props.data.slug)
        val state = openChapter(body)
        sessions[id] = state.session

        val manifest = state.manifest ?: return (1..props.pageCount).map { idx ->
            Page(
                index = idx - 1,
                url = "$id#$idx",
                imageUrl = "$baseUrl/serie/${props.data.serie.slug}/chapter/${props.data.slug}/page/$idx#$id",
            )
        }

        val variant = manifest.variants.minByOrNull { abs(it - MAX_VARIANT_WIDTH) }
            ?.let { "-$it" }
            .orEmpty()

        return (1..manifest.count).map { idx ->
            Page(
                index = idx - 1,
                url = "$id#$idx",
                imageUrl = "$baseUrl${manifest.base}$idx$variant.ece#$id",
            )
        }
    }

    // The server withholds the chapter token until the Turnstile captcha is solved for this chapter.
    private fun PageListResponse.Props.captchaPending() = data.captcha == 1 && captchaPassed != true

    /**
     * Opens the chapter in the WebView and polls until the captcha is solved, so the reader
     * keeps loading on its own once the WebView is closed. Downloads fail right away.
     */
    private suspend fun awaitCaptcha(chapter: SChapter, isDownload: Boolean): PageListResponse {
        if (isDownload) {
            throw IOException(intl["captcha_download_unavailable"])
        }
        if (!tryOpenWebView(getChapterUrl(chapter))) {
            throw IOException(intl["captcha_webview_failed"])
        }

        val url = "$baseUrl${chapter.url}".toHttpUrl()
        val deadline = System.currentTimeMillis() + CAPTCHA_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(CAPTCHA_POLL_MS)
            val body = try {
                val request = apiRequest(
                    url,
                    includeXSRFToken = true,
                    includeCSRFToken = false,
                    includeVersion = true,
                )
                client.newCall(request).execute().parseAs<PageListResponse>()
            } catch (_: IOException) {
                continue // transient network error, keep waiting
            }
            if (!body.props.captchaPending()) return body
        }

        throw IOException(intl["captcha_timeout"])
    }

    private fun isDownloadContext(): Boolean = Exception().stackTrace.any {
        it.className.contains("eu.kanade.tachiyomi.data.download", ignoreCase = true) ||
            it.className.contains("Downloader", ignoreCase = true)
    }

    private fun tryOpenWebView(url: String): Boolean = try {
        val context = applicationContext
        context.startActivity(
            Intent().apply {
                component = ComponentName(context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("url_key", url)
                putExtra("source_key", id)
            },
        )
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Id of the site's own reader signer: its key in the pam-reader release and the name of the
     * fallback bundled as `assets/pam/<id>.wasm`. Each site ships a differently obfuscated module,
     * so it is executed rather than reimplemented - see IMPLEMENT.md.
     */
    protected abstract val readerId: String

    private val readerWasm by lazy {
        ReaderWasmManager(readerId, client, preferences) {
            val path = "assets/pam/$readerId.wasm"
            this::class.java.classLoader!!.getResourceAsStream(path)?.use { it.readBytes() }
                ?: throw IOException("Missing reader signer: $path")
        }
    }

    /** `signAttestation`: HMACs the device report and client pubkey against the challenge. */
    protected abstract fun Signer.signAttestation(challenge: String, payload: String): String

    /** `signManifest`: signs the manifest request fields with the chapter token. */
    protected abstract fun Signer.signManifest(token: String, version: Int, uid: String, ts: Long, nonce: String): String

    /**
     * `ecdhInit` followed by `kdfRot`: seeds the signer with this session's ECDH secret, then
     * unmasks the manifest hint into the chapter's page key.
     */
    protected abstract fun Signer.deriveContentKey(
        privateKey: ByteArray,
        serverPubkey: ByteArray,
        uid: String,
        keyVersion: Int,
        hint: ByteArray,
    ): ByteArray

    /**
     * The signer holds 16 MB of WASM memory, so it is built per chapter and dropped again
     * instead of being kept for the source's lifetime.
     */
    private fun <T> withSigner(block: Signer.() -> T): T = try {
        Signer(readerWasm.get()).block()
    } catch (e: Exception) {
        // Most likely the site rotated its module since the cached one was fetched.
        if (!readerWasm.refresh()) throw e
        Signer(readerWasm.get()).block()
    }

    private val secureRandom = SecureRandom()

    private class ChapterSession(
        val chapterToken: String,
        val sharedSecret: ByteArray,
        val clientPubkeyB64: String,
        /** Reader v2 only: input keying material for this chapter's encrypted pages. */
        val contentKey: ByteArray? = null,
    )

    private class ChapterState(
        val session: ChapterSession,
        val manifest: ManifestResponse?,
    )

    private val sessions = ConcurrentHashMap<String, ChapterSession>()
    private val sessionLocks = ConcurrentHashMap<String, Any>()

    private fun sessionKey(serieSlug: String, chapterSlug: String) = "${name.take(3).lowercase()}-$serieSlug--$chapterSlug"

    private fun openChapter(body: PageListResponse): ChapterState {
        val props = body.props
        val serverPub = Base64.decode(props.serverPubkey, Base64.DEFAULT)
        require(serverPub.size == 32) { "server pubkey must be 32 bytes" }

        val priv = ByteArray(32).also(secureRandom::nextBytes)
        val clientPub = X25519.publicKey(priv)
        val shared = X25519.scalarMult(priv, serverPub)
        val clientPubkeyB64 = Base64.encodeToString(clientPub, Base64.NO_WRAP)

        try {
            if (!props.readerV2) {
                val token = props.chapterToken ?: throw IOException("Chapter token missing")
                return ChapterState(ChapterSession(token, shared, clientPubkeyB64), null)
            }

            return withSigner {
                val token = attest(body, clientPubkeyB64)
                val uid = props.data.uid ?: throw IOException("Chapter uid missing")
                val manifest = requestManifest(uid, token, clientPubkeyB64)

                ChapterState(
                    ChapterSession(token, shared, clientPubkeyB64, contentKey(manifest, priv, serverPub)),
                    manifest,
                )
            }
        } finally {
            priv.fill(0)
        }
    }

    /**
     * Reader v2 mints the chapter token from an attestation exchange instead of shipping it
     * in the page props. The first exchange is always answered with `refresh`, which retires
     * the challenge embedded in the page: only the challenge handed back by the partial
     * reload gets a token.
     */
    private fun Signer.attest(body: PageListResponse, clientPubkeyB64: String): String {
        val attestation = body.props.attestation ?: throw IOException("Missing attestation challenge")
        val device = deviceReport(attestation.webglSeed)
        var challenge = attestation.challenge

        repeat(ATTESTATION_ATTEMPTS) {
            val request = AttestationRequest(
                c = challenge,
                v = hmacSha256Hex(device, challenge.toByteArray()),
                sp = signAttestation(challenge, "$device\u0000$clientPubkeyB64"),
                d = device,
                pk = clientPubkeyB64,
            )
            val minted = client.newCall(
                apiRequest(
                    "$baseUrl/api/v1/t".toHttpUrl(),
                    request.toJsonRequestBody(),
                    includeXSRFToken = true,
                    includeCSRFToken = false,
                    includeVersion = false,
                ),
            ).execute().parseAs<AttestationResponse>().ct

            if (minted != null) return minted

            val reloaded = client.newCall(attestationReloadRequest(body)).execute()
                .parseAs<AttestationReload>().props
            reloaded.chapterToken?.also { return it }
            challenge = reloaded.attestation?.challenge ?: throw IOException("Attestation refused")
        }

        throw IOException("Attestation refused")
    }

    private fun attestationReloadRequest(body: PageListResponse): Request {
        val url = "$baseUrl/serie/${body.props.data.serie.slug}/chapter/${body.props.data.slug}".toHttpUrl()
        val headers = headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("X-Inertia", "true")
            .set("X-Inertia-Version", body.version)
            .set("X-Inertia-Partial-Component", body.component)
            .set("X-Inertia-Partial-Data", "chapter_token,attestation")
            .build()

        return GET(url, headers)
    }

    /**
     * Stands in for the browser fingerprint the site collects through canvas and WebGL.
     *
     * The values themselves cannot be checked - the server hands out a seed and has no way to
     * know what an unknown GPU would rasterise - but how they react to a new seed can be, and
     * that is what the `refresh` round re-tests. The reader renders `webgl_proof` from the
     * seed, so it has to change with it, while `canvas_hash` draws a fixed string and has to
     * stay the same.
     */
    private fun deviceReport(webglSeed: String): String = """{"webdriver":false,"webgl_vendor":"Qualcomm","webgl_renderer":"Adreno (TM) 730",""" +
        """"webgl_proof":"${sha256Hex("proof:$webglSeed")}","gl_sig":"8192|1|1|23",""" +
        """"device_memory":null,"hardware_concurrency":8,"effective_type":null,"save_data":false,""" +
        """"screen_width":1080,"screen_height":2340,"viewport_width":1080,"viewport_height":2130,""" +
        """"device_pixel_ratio":2.75,"max_touch_points":5,"has_touch":true,""" +
        """"locale":"en-US","timezone":"America/New_York","platform":"Linux armv8l",""" +
        """"canvas_hash":"${sha256Hex("attest:canvas")}"}"""

    private fun Signer.requestManifest(uid: String, chapterToken: String, clientPubkeyB64: String): ManifestResponse {
        val ts = System.currentTimeMillis() / 1000
        val nonce = hexNonce()
        val request = ManifestRequest(
            v = MANIFEST_VERSION,
            c = uid,
            t = chapterToken,
            ts = ts,
            n = nonce,
            s = signManifest(chapterToken, MANIFEST_VERSION, uid, ts, nonce),
        )

        val call = apiRequest(
            "$baseUrl/api/v1/m".toHttpUrl(),
            request.toJsonRequestBody(),
            includeXSRFToken = true,
            includeCSRFToken = false,
            includeVersion = false,
        ).newBuilder().header("X-Client-Pubkey", clientPubkeyB64).build()

        return client.newCall(call).execute().parseAs<ManifestResponse>()
    }

    /**
     * The manifest hint is the page key masked with a digest chain over the ECDH secret, so
     * it is worthless to any other session.
     */
    private fun Signer.contentKey(manifest: ManifestResponse, privateKey: ByteArray, serverPubkey: ByteArray): ByteArray {
        val segments = manifest.base.split('/').filter(String::isNotEmpty)
        require(segments.size >= 4 && segments[0] == "p") { "unexpected manifest base: ${manifest.base}" }

        val hint = Base64.decode(manifest.hint, Base64.DEFAULT)
        return deriveContentKey(privateKey, serverPubkey, segments[1], segments[2].toInt(), hint)
    }

    private fun ensureSession(serieSlug: String, chapterSlug: String): ChapterSession {
        val id = sessionKey(serieSlug, chapterSlug)
        sessions[id]?.let { return it }

        val lock = sessionLocks[id] ?: Any().let { fresh ->
            sessionLocks.putIfAbsent(id, fresh) ?: fresh
        }
        synchronized(lock) {
            sessions[id]?.let { return it }

            val url = "$baseUrl/serie/$serieSlug/chapter/$chapterSlug".toHttpUrl()
            val req = apiRequest(
                url,
                includeXSRFToken = true,
                includeCSRFToken = false,
                includeVersion = true,
            )
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Could not rebuild chapter session: HTTP ${resp.code}")
                }
                resp.parseAs<PageListResponse>()
            }

            if (body.props.captchaPending()) {
                throw IOException(intl["captcha_reopen"])
            }

            val sess = openChapter(body).session
            sessions[id] = sess
            return sess
        }
    }

    private fun hexNonce(byteCount: Int = 16): String {
        val b = ByteArray(byteCount).also(secureRandom::nextBytes)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256Hex(key: String, msg: String): String = hmacSha256Hex(key, msg.toByteArray(Charsets.US_ASCII))

    private fun hmacSha256Hex(key: String, msg: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        }
        return mac.doFinal(msg).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    override fun imageRequest(page: Page): Request {
        val parsed = page.imageUrl!!.toHttpUrl()
        if (parsed.encodedPath.endsWith(".ece")) {
            return GET(parsed, headers)
        }

        val seg = parsed.pathSegments
        require(seg.size >= 6 && seg[0] == "serie" && seg[2] == "chapter" && seg[4] == "page") {
            "unexpected page URL shape: ${parsed.encodedPath}"
        }
        val serieSlug = seg[1]
        val chapterSlug = seg[3]
        val pageIndex = seg[5].toInt()

        val session = ensureSession(serieSlug, chapterSlug)
        val sessionId = sessionKey(serieSlug, chapterSlug)

        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = hexNonce()
        val sig = hmacSha256Hex(session.chapterToken, "$pageIndex$ts$nonce")

        val url = baseHttpUrl.newBuilder()
            .addPathSegment("serie").addPathSegment(serieSlug)
            .addPathSegment("chapter").addPathSegment(chapterSlug)
            .addPathSegment("page").addPathSegment(pageIndex.toString())
            .addQueryParameter("token", session.chapterToken)
            .addQueryParameter("ts", ts)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("sig", sig)
            .fragment(sessionId)
            .build()

        val h = headersBuilder()
            .set("X-Client-Pubkey", session.clientPubkeyB64)
            .build()

        return GET(url, h)
    }

    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val sessionId = request.url.fragment ?: return response
        val session = sessions[sessionId] ?: return response

        if (session.contentKey != null) {
            if (!response.isSuccessful) return response

            return try {
                response.newBuilder().body(
                    decryptEce(response.body.source(), session.contentKey).buffer()
                        .asResponseBody("image/webp".toMediaType()),
                ).build()
            } catch (e: Exception) {
                response.close()
                throw e
            }
        }

        val pageNameRaw = response.header("X-Page-Name") ?: return response
        val keyHintB64 = response.header("X-Key-Hint") ?: return response
        val keyHint = Base64.decode(keyHintB64, Base64.DEFAULT)
        require(keyHint.size >= 32) { "X-Key-Hint must decode to >= 32 bytes" }

        val streamKey = run {
            val sha = MessageDigest.getInstance("SHA-256").run {
                update(session.sharedSecret)
                update(pageNameRaw.toByteArray(Charsets.UTF_8))
                digest()
            }
            ByteArray(32) { i -> (sha[i].toInt() xor keyHint[i].toInt()).toByte() }
        }

        val networkSource = response.body.source()
        networkSource.skip(PREFIX_LENGTH.toLong())
        val ssHeader = networkSource.readByteArray(STREAM_HEADER_LENGTH.toLong())

        val decryptedSource = object : okio.Source {
            private val secretStream = SecretStream()
            private val state = State().apply {
                secretStream.initPull(this, ssHeader, streamKey)
            }
            private val decryptedBuffer = Buffer()
            private var isFinished = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (decryptedBuffer.size == 0L) {
                    if (isFinished) return -1

                    networkSource.request(CHUNK_SIZE.toLong())

                    val chunkSize = minOf(CHUNK_SIZE.toLong(), networkSource.buffer.size)

                    if (chunkSize == 0L) {
                        isFinished = true
                        return -1
                    }

                    val encryptedData = Buffer().apply {
                        networkSource.read(this, chunkSize)
                    }.readByteArray()

                    val result = secretStream.pull(state, encryptedData, encryptedData.size)
                        ?: throw IOException("Decryption failed")

                    decryptedBuffer.write(result.message)

                    if (result.tag.toInt() == SecretStream.TAG_FINAL) {
                        isFinished = true
                    }
                }

                return decryptedBuffer.read(sink, byteCount)
            }

            override fun timeout(): Timeout = networkSource.timeout()

            override fun close() = networkSource.close()
        }.buffer()

        return response.newBuilder()
            .body(decryptedSource.asResponseBody("image/jpg".toMediaType()))
            .build()
    }

    /** RFC 8188 `aes128gcm`, the container reader v2 serves its pages in, decrypted record by record. */
    private fun decryptEce(upstream: BufferedSource, ikm: ByteArray): okio.Source {
        val salt = upstream.readByteArray(16)
        val recordSize = upstream.readInt()
        upstream.skip((upstream.readByte().toInt() and 0xFF).toLong())
        require(recordSize >= 18 && !upstream.exhausted()) { "ece: malformed header" }

        val key = SecretKeySpec(hkdf(ikm, salt, ECE_KEY_INFO, 16), "AES")
        val nonce = hkdf(ikm, salt, ECE_NONCE_INFO, 12)

        return object : okio.Source {
            private val output = Buffer()
            private var sequence = 0
            private var isFinished = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                while (output.size == 0L) {
                    if (isFinished) return -1
                    decryptRecord()
                }
                return output.read(sink, byteCount)
            }

            private fun decryptRecord() {
                upstream.request(recordSize.toLong())
                val record = upstream.readByteArray(minOf(recordSize.toLong(), upstream.buffer.size))
                isFinished = upstream.exhausted()
                require(record.size >= 18) { "ece: record $sequence too short" }

                val iv = nonce.copyOf()
                var counter = sequence
                for (i in 11 downTo 0) {
                    if (counter == 0) break
                    iv[i] = (iv[i].toInt() xor (counter and 0xFF)).toByte()
                    counter = counter ushr 8
                }

                val plain = try {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                        doFinal(record)
                    }
                } catch (e: GeneralSecurityException) {
                    throw IOException("ece: record $sequence failed to decrypt", e)
                }

                // Records are zero-padded up to a delimiter byte: 2 on the last one, 1 elsewhere.
                var last = plain.size - 1
                while (last >= 0 && plain[last].toInt() == 0) last--
                require(last >= 0 && plain[last].toInt() == if (isFinished) 2 else 1) {
                    "ece: record $sequence has the wrong delimiter"
                }

                output.write(plain, 0, last)
                sequence++
            }

            override fun timeout(): Timeout = upstream.timeout()

            override fun close() = upstream.close()
        }
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }.doFinal(ikm)

        return Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update(info)
            update(1)
        }.doFinal().copyOf(length)
    }
}

private const val THUMBNAIL_FRAGMENT = "thumbnail"
private const val ATTESTATION_ATTEMPTS = 3
private const val CAPTCHA_POLL_MS = 5_000L
private const val CAPTCHA_TIMEOUT_MS = 3 * 60 * 1000L
private const val MANIFEST_VERSION = 2
private const val MAX_VARIANT_WIDTH = 2160
private val ECE_KEY_INFO = "Content-Encoding: aes128gcm\u0000".toByteArray()
private val ECE_NONCE_INFO = "Content-Encoding: nonce\u0000".toByteArray()
private const val HIDE_PREMIUM_PREF = "pref_hide_premium_chapters"
private const val CHUNK_SIZE = 65536 + 17 // libsodium secretstream chunk + ABYTES
private const val PREFIX_LENGTH = 192
private const val STREAM_HEADER_LENGTH = 24
