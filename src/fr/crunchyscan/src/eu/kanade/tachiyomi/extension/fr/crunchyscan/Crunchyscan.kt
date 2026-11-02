package eu.kanade.tachiyomi.extension.fr.crunchyscan

import android.content.ComponentName
import android.content.Intent
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Source
abstract class Crunchyscan : KeiSource() {

    private var csrfToken: String = ""

    private suspend fun fetchCsrfToken(): String {
        if (csrfToken.isNotEmpty()) return csrfToken
        val document = client.get(baseUrl, Fetch.DOCUMENT).asJsoup()
        csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        return csrfToken
    }

    private suspend fun headersWithCsrf(): Headers = headers.newBuilder().add("X-CSRF-TOKEN", fetchCsrfToken()).add("X-Requested-With", "XMLHttpRequest").build()

    private fun parseMangasPage(response: Response): MangasPage {
        val apiResponse = response.parseAs<ApiResponse>()
        val mangaList = apiResponse.data.map { it.toSManga() }
        val hasNextPage = apiResponse.meta.currentPage < apiResponse.meta.lastPage
        return MangasPage(mangaList, hasNextPage)
    }

    // ============================================
    // POPULAR MANGA
    // ============================================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val formBody =
            FormBody.Builder().add("affichage", "grid").add("team", "").add("artist", "").add("author", "").add("page", page.toString())
                .add("chapters[]", "0").add("chapters[]", "200").add("searchTerm", "").add("orderWith", "Vues").add("orderBy", "desc")
                .build()
        val response = client.post("$baseUrl/api/manga/search/advance", headersWithCsrf(), Fetch.XHR, formBody)
        return parseMangasPage(response)
    }

    // ============================================
    // LATEST UPDATES
    // ============================================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val formBody =
            FormBody.Builder().add("affichage", "grid").add("team", "").add("artist", "").add("author", "").add("page", page.toString())
                .add("chapters[]", "0").add("chapters[]", "200").add("searchTerm", "").add("orderWith", "Récent").add("orderBy", "desc")
                .build()
        val response = client.post("$baseUrl/api/manga/search/advance", headersWithCsrf(), Fetch.XHR, formBody)
        return parseMangasPage(response)
    }

    // ============================================
    // SEARCH
    // ============================================

    override fun getFilterList(data: JsonElement?) = FilterList(
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        TypeFilter(),
        ChapterMinFilter(),
        ChapterMaxFilter(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val formBody =
            FormBody.Builder().add("affichage", "grid").add("team", "").add("artist", "").add("author", "").add("page", page.toString())
                .add("searchTerm", query).add("orderWith", "Vues").add("orderBy", "desc")

        var chaptersMin = "0"
        var chaptersMax = "200"

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> filter.state.forEach { genre ->
                    when (genre.state) {
                        Filter.TriState.STATE_INCLUDE -> formBody.add("genres[]", genre.name)
                        Filter.TriState.STATE_EXCLUDE -> formBody.add("exclude_genres[]", genre.name)
                        else -> {}
                    }
                }

                is YearFilter -> filter.state.filter { it.state }.forEach { formBody.add("year[]", it.name) }
                is StatusFilter -> filter.state.filter { it.state }.forEach { formBody.add("status[]", it.name) }
                is TypeFilter -> filter.state.filter { it.state }.forEach { formBody.add("types[]", it.name) }
                is ChapterMinFilter -> chaptersMin = filter.state
                is ChapterMaxFilter -> chaptersMax = filter.state
                else -> {}
            }
        }

        formBody.add("chapters[]", chaptersMin).add("chapters[]", chaptersMax)

        val response = client.post("$baseUrl/api/manga/search/advance", headersWithCsrf(), Fetch.XHR, formBody.build())
        return parseMangasPage(response)
    }

    // ============================================
    // MANGA DETAILS + CHAPTER LIST
    // ============================================

    // Détails et chapitres viennent de la même page : une seule requête, peu importe les flags.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url, Fetch.DOCUMENT).asJsoup()

        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = parseChapterList(document),
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = manga.apply {
        title = document.selectFirst("h2.text-3xl, h1.text-2xl")!!.text()
        description = document.selectFirst("div.mt-12 > p")?.text() ?: document.selectFirst("p.whitespace-pre-line")?.text()
        thumbnail_url = document.selectFirst("img.manga_cover")?.attr("abs:src")
        author = document.select("a[href*='/catalog/author/']").joinToString { it.text() }
        genre = document.select("a[href*='/catalog/genre/']").joinToString { it.text() }
        val statusText = document.select("h3:contains(Status)").first()?.nextElementSibling()?.text()?.lowercase()
        status = when {
            statusText?.contains("en cours") == true -> SManga.ONGOING
            statusText?.contains("terminé") == true -> SManga.COMPLETED
            statusText?.contains("pause") == true -> SManga.ON_HIATUS
            statusText?.contains("abandonné") == true -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        return document.select("div#ChapterWrap > div.chapterBox").mapNotNull { element ->
            val link = element.selectFirst("a.chapter-link[href*='/read/']") ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text().ifEmpty { "Chapitre" }
                val dateElement = element.select("i.fa-timer").first()?.parent()?.nextElementSibling()
                date_upload = parseChapterDate(dateElement?.text())
            }
        }
    }

    private fun parseChapterDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        val normalized = dateStr.lowercase().trim()
        val value = normalized.filter { it.isDigit() }.toLongOrNull() ?: return 0L
        val now = System.currentTimeMillis()
        return when {
            normalized.contains("min") -> now - (value * 60 * 1000)
            normalized.contains("heure") || normalized.contains("h") -> now - (value * 60 * 60 * 1000)
            normalized.contains("jour") -> now - (value * DAY_IN_MILLIS)
            normalized.contains("mois") -> now - (value * MONTH_IN_MILLIS)
            normalized.contains("année") || normalized.contains("an") -> now - (value * YEAR_IN_MILLIS)
            else -> 0L
        }
    }

    // ============================================
    // PAGE LIST
    // ============================================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Capturé avant tout appel suspend de cette fonction (donc avant tout vrai saut de
        // thread), pour que la pile d'appel reflète encore l'appel synchrone venant du
        // Downloader de Mihon. Si cette vérification est faite après un `client.get(...)`,
        // elle ne fonctionne plus de manière fiable pendant un téléchargement.
        val isDownload = isDownloadContext()

        val response = client.get(baseUrl + chapter.url, Fetch.DOCUMENT)
        val chapterUrl = response.request.url.toString().substringBefore("?")
        val document = response.asJsoup()

        val hasTurnstile = document.selectFirst(".cf-turnstile") != null
        val dataMeta = document.selectFirst("#a-ads-id")?.attr("data-meta") ?: ""

        if (hasTurnstile || dataMeta.isEmpty()) {
            if (isDownload) {
                throw Exception("Protégé par Cloudflare, non téléchargeable")
            }

            val opened = tryOpenWebView(chapterUrl)
            throw Exception(
                if (opened) {
                    "Chapitre protégé par Cloudflare : ouverture du WebView, lisez-y le chapitre puis fermez-la."
                } else {
                    "Chapitre protégé par Cloudflare. Ouvrez-le manuellement via \"Ouvrir dans le WebView\" (menu ⋮)."
                },
            )
        }

        val imageUrls = decryptImageUrls(dataMeta)
        return imageUrls.mapIndexed { index, _ ->
            Page(index, url = "$chapterUrl?imgIndex=$index")
        }
    }

    private fun isDownloadContext(): Boolean = Exception().stackTrace.any {
        it.className.contains("eu.kanade.tachiyomi.data.download", ignoreCase = true) || it.className.contains(
            "Downloader",
            ignoreCase = true,
        )
    }

    private fun tryOpenWebView(url: String): Boolean = try {
        val context = applicationContext
        val intent = Intent().apply {
            component = ComponentName(context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("url_key", url)
            putExtra("source_key", id)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    // getImageUrl fusionne l'ancien imageUrlRequest (avec son CacheControl.FORCE_NETWORK) + imageUrlParse
    override suspend fun getImageUrl(page: Page): String {
        val response = client.get(page.url, Fetch.DOCUMENT, cacheControl = CacheControl.FORCE_NETWORK)
        val targetIndex = response.request.url.queryParameter("imgIndex")?.toIntOrNull() ?: 0
        val document = response.asJsoup()
        val dataMeta = document.selectFirst("#a-ads-id")?.attr("data-meta") ?: return ""
        val imageUrls = decryptImageUrls(dataMeta)
        val url = imageUrls.getOrNull(targetIndex) ?: return ""

        return if (url.contains("/get-image")) "$url&cid=$FINGERPRINT_DEFAULT" else url
    }

    override fun getImageRequest(page: Page): Request {
        val imageUrl = page.imageUrl.orEmpty().replace("&amp;", "&")

        val referer = if (imageUrl.contains("/get-image") && page.url.isNotEmpty()) {
            page.url.substringBefore("?imgIndex")
        } else {
            baseUrl
        }

        val requestHeaders = headers.newBuilder().set("secs-ch-aa", "v=\"118\"").set("Referer", referer).removeAll("Accept").build()

        return GET(imageUrl, requestHeaders)
    }

    // ============================================
    // DÉCRYPTAGE
    // ============================================

    private fun decryptImageUrls(dataMeta: String): List<String> {
        val encryptedText = hexToString(dataMeta)
        var temp = decryptVigenere(encryptedText, KEY_MARIO_1)
        temp = decryptXor(temp, KEY_TETRIS_1)
        temp = decryptXor(temp, KEY_TETRIS_2)
        val wasmOutput = decryptVigenere(temp, KEY_MARIO_2)
        val combinedBytes = ByteArray(wasmOutput.length) { (wasmOutput[it].code and 0xFF).toByte() }
        val decryptedBytes = decryptAesCbc(combinedBytes)
        val finalString = String(decryptedBytes, StandardCharsets.UTF_8)

        return finalString.split(";").map { it.trim() }.filter { it.isNotBlank() }.filterNot { it.contains("get-lmage") }.map { url ->
            when {
                url.startsWith("http") -> url
                url.startsWith("/") -> "$baseUrl$url"
                else -> url
            }
        }
    }

    private fun decryptVigenere(input: String, key: String): String {
        val sb = StringBuilder(input.length)
        for (i in input.indices) {
            sb.append(((input[i].code - key[i % key.length].code + 0x100) % 0x100).toChar())
        }
        return sb.toString()
    }

    private fun decryptXor(input: String, key: String): String {
        val sb = StringBuilder(input.length)
        for (i in input.indices) {
            sb.append((input[i].code xor key[i % key.length].code).toChar())
        }
        return sb.toString()
    }

    private fun hexToString(hex: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < hex.length - 1) {
            sb.append(hex.substring(i, i + 2).toInt(16).toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun decryptAesCbc(encryptedBytes: ByteArray): ByteArray {
        val iv = encryptedBytes.copyOfRange(0, 16)
        val cipherText = encryptedBytes.copyOfRange(16, encryptedBytes.size)
        val keyBytes = Base64.decode(AES_KEY_BASE64, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherText)
    }

    companion object {
        private const val KEY_MARIO_1 = "aYdjAA9bFlWzoO2ZDjvw51DUhIy9"
        private const val KEY_TETRIS_1 = "K0Q6YqGsxCtCLPLG"
        private const val KEY_TETRIS_2 = "3jBYzWHkXj1Gke3VcS6pLDLz"
        private const val KEY_MARIO_2 = "L3EtGmOqE746udz0k8P74tUq"
        private const val AES_KEY_BASE64 = "Tr3eGFZNXPTo8mTEBhu1R+mLy/MCcgG8+7ikXbMVaEQ="
        private const val FINGERPRINT_DEFAULT = "0000000000000000000000000000000000000000000000000000000000000000"

        private const val DAY_IN_MILLIS = 86400000L
        private const val MONTH_IN_MILLIS = 30 * DAY_IN_MILLIS
        private const val YEAR_IN_MILLIS = 365 * DAY_IN_MILLIS
    }
}
