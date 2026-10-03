package eu.kanade.tachiyomi.multisrc.pam

import android.content.SharedPreferences
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient

@Serializable
private class ReaderReleaseVersion(
    val validVersion: List<Int>,
)

@Serializable
private class ReaderRelease(
    val validVersion: List<Int>,
    /** Reader id to the bare filename of its module in the same release. */
    val sites: Map<String, String>,
)

internal class ReaderWasmManager(
    private val readerId: String,
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
    private val bundled: () -> ByteArray,
) {
    @Volatile
    private var cached: ByteArray? = null

    fun get(): ByteArray {
        val expired = System.currentTimeMillis() - preferences.getLong(PREF_CHECKED_AT, 0L) >= CACHE_TTL_MS
        if (!expired) {
            cached?.let { return it }
            stored()?.let { return it.also { cached = it } }
            // Checked recently but nothing usable was downloaded (e.g. the release targets another
            // parser version): use the bundled module without hitting the network again.
            return bundled()
        }
        return fetch() ?: stored() ?: bundled()
    }

    /**
     * Bypasses the TTL after the current module got refused. Returns whether a different module
     * is now in use, i.e. whether retrying is worth it.
     */
    fun refresh(): Boolean {
        val before = preferences.getString(PREF_FILE, null)
        fetch()
        return preferences.getString(PREF_FILE, null) != before
    }

    private fun stored(): ByteArray? = preferences
        .takeIf { it.getInt(PREF_PARSER_VERSION, 0) == PARSER_VERSION }
        ?.getString(PREF_WASM, null)
        ?.let { Base64.decode(it, Base64.DEFAULT) }

    private fun fetch(): ByteArray? = runCatching {
        val body = client.newCall(GET(MANIFEST_URL)).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
        preferences.edit().putLong(PREF_CHECKED_AT, System.currentTimeMillis()).apply()

        // Incompatible release: keep whatever this build already has.
        if (PARSER_VERSION !in body.parseAs<ReaderReleaseVersion>().validVersion) return null

        val file = body.parseAs<ReaderRelease>().sites[readerId] ?: return null
        require(file.matches(FILENAME_REGEX) && ".." !in file) { "Rejected reader module filename: $file" }

        if (file == preferences.getString(PREF_FILE, null)) {
            stored()?.let { return it.also { cached = it } }
        }

        val wasm = client.newCall(GET(RELEASE_BASE + file)).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.bytes()
        }
        preferences.edit()
            .putString(PREF_FILE, file)
            .putString(PREF_WASM, Base64.encodeToString(wasm, Base64.NO_WRAP))
            .putInt(PREF_PARSER_VERSION, PARSER_VERSION)
            .apply()
        wasm.also { cached = it }
    }.getOrNull()

    private companion object {
        const val RELEASE_BASE = "https://github.com/Starmania/pam-reader/releases/latest/download/"
        const val MANIFEST_URL = RELEASE_BASE + "reader.json"

        val FILENAME_REGEX = """[A-Za-z0-9._-]+""".toRegex()

        const val PREF_WASM = "reader_wasm"
        const val PREF_FILE = "reader_wasm_file"
        const val PREF_CHECKED_AT = "reader_wasm_checked_at"
        const val PREF_PARSER_VERSION = "reader_wasm_parser_version"
        const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L

        /** Bump when the release's modules need Kotlin changes this build does not have. */
        const val PARSER_VERSION = 2
    }
}
