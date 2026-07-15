package com.voxli.knigavuhe.matcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * Matches flibusta books to knigavuhe.org audio versions.
 * Provides on-demand narrator lookup and track URLs.
 *
 * Reference: roadmap §3.2 (fuzzy matching) and §14.2 (API spec).
 */
class KnigavuheMatcher(
    private val client: OkHttpClient,
) {
    private val baseUrl = "https://knigavuhe.org"

    // In-memory cache per bookId, thread-safe
    private val narratorCache = ConcurrentHashMap<Long, List<NarratorInfo>>()

    /**
     * Search for a book on knigavuhe by (title, author).
     * Returns the slug if found, null otherwise.
     */
    suspend fun searchBook(title: String, author: String): String? {
        val query = "$title $author".trim()
        val url = "$baseUrl/search/?q=${okhttp3.HttpUrl.Companion.encode(query)}"
        val html = fetchHtml(url) ?: return null
        return parseSearchResults(html, title, author)
    }

    /**
     * Fetch narrators for a given knigavuhe book slug.
     * Cached in memory per session.
     */
    suspend fun fetchNarrators(bookId: Long, slug: String): List<NarratorInfo> {
        narratorCache[bookId]?.let { return it }

        val url = "$baseUrl/book/$slug/"
        val html = fetchHtml(url) ?: return emptyList()
        val narrators = parseNarrators(html)
        if (narrators.isNotEmpty()) {
            narratorCache[bookId] = narrators
        }
        return narrators
    }

    /** Cache access for direct lookup (used by UI after initial fetch). */
    fun getCachedNarrators(bookId: Long): List<NarratorInfo>? = narratorCache[bookId]

    fun clearCache() = narratorCache.clear()

    // ---- internal helpers ----

    private suspend fun fetchHtml(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Referer", "https://knigavuhe.org/")
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.string() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse search results HTML to find matching book slug.
     * Implements fuzzy matching per roadmap §3.2.
     */
    internal fun parseSearchResults(html: String, targetTitle: String, targetAuthor: String): String? {
        // Step 1: Normalize
        val normTargetTitle = normalize(targetTitle)
        val normTargetAuthor = normalize(targetAuthor)

        // Extract search result entries using simple regex (Ksoup in Phase 2)
        val bookItemRegex = Regex(
            """<div class="book-item">.*?<a href="/book/([^/]+)/">.*?</a>.*?</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val titleRegex = Regex("""<span class="book-title">([^<]+)</span>""", RegexOption.IGNORE_CASE)
        val authorRegex = Regex("""<span class="book-author">([^<]+)</span>""", RegexOption.IGNORE_CASE)

        val matches = bookItemRegex.findAll(html)
        for (match in matches) {
            val slug = match.groupValues[1]
            val itemHtml = match.value
            val itemTitle = titleRegex.find(itemHtml)?.groupValues?.get(1) ?: continue
            val itemAuthor = authorRegex.find(itemHtml)?.groupValues?.get(1) ?: continue

            // Step 2: Exact match
            if (normalize(itemTitle) == normTargetTitle && normalize(itemAuthor) == normTargetAuthor) {
                return slug
            }
            // Step 3: Last name + first word of title
            val targetLastName = normTargetAuthor.split(" ").firstOrNull() ?: ""
            val itemLastName = normalize(itemAuthor).split(" ").firstOrNull() ?: ""
            val targetFirstWord = normTargetTitle.split(" ").firstOrNull() ?: ""
            val itemFirstWord = normalize(itemTitle).split(" ").firstOrNull() ?: ""
            if (targetLastName == itemLastName && targetFirstWord == itemFirstWord) {
                return slug
            }
        }
        return null
    }

    /**
     * Parse narrator list and track URLs from book page HTML.
     * Extracts data from JS variable `audioFiles`.
     */
    internal fun parseNarrators(html: String): List<NarratorInfo> {
        // Extract reader items
        val readerRegex = Regex(
            """<div class="reader-item" data-reader-id="(\d+)">\s*<span class="reader-name">([^<]+)</span>\s*<span class="reader-duration">([^<]+)</span>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val readerMatches = readerRegex.findAll(html).toList()

        // Extract audioFiles JS object (simplified regex — Ksoup in Phase 2)
        val audioFilesJson = extractAudioFilesJson(html)

        return readerMatches.mapNotNull { readerMatch ->
            val readerId = readerMatch.groupValues[1]
            val name = readerMatch.groupValues[2].trim()
            val durationRaw = readerMatch.groupValues[3].trim()
            val durationSec = parseDuration(durationRaw)

            val tracks = extractTracksForReader(audioFilesJson, readerId)

            NarratorInfo(
                name = name,
                durationSeconds = durationSec,
                tracks = tracks,
            )
        }
    }

    private fun extractAudioFilesJson(html: String): String {
        val regex = Regex(
            """var audioFiles\s*=\s*(\{.*?\});""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return regex.find(html)?.groupValues?.get(1) ?: "{}"
    }

    private fun extractTracksForReader(audioFilesJson: String, readerId: String): List<TrackInfo> {
        // Simplified: extract array for readerId from JSON-like structure
        val readerRegex = Regex(
            """"$readerId"\s*:\s*\[(.*?)\]""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val readerArray = readerRegex.find(audioFilesJson)?.groupValues?.get(1) ?: return emptyList()

        val trackRegex = Regex(
            """\{[^}]*?"url"\s*:\s*"([^"]+)"[^}]*?"title"\s*:\s*"([^"]+)"[^}]*?"duration"\s*:\s*"([^"]+)"[^}]*?\}""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        return trackRegex.findAll(readerArray).mapNotNull { trackMatch ->
            val path = trackMatch.groupValues[1]
            TrackInfo(
                title = trackMatch.groupValues[2],
                url = if (path.startsWith("http")) path else "$baseUrl$path",
                durationSeconds = parseDuration(trackMatch.groupValues[3]),
            )
        }.toList()
    }

    /** Normalize string for matching: lowercase, remove punctuation. */
    internal fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("[\\p{Punct}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Parse duration string "HH:MM:SS" or "MM:SS" to seconds. */
    private fun parseDuration(duration: String): Long {
        val parts = duration.trim().split(":").map { it.toLongOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> parts.firstOrNull() ?: 0
        }
    }
}

data class NarratorInfo(
    val name: String,
    val durationSeconds: Long,
    val tracks: List<TrackInfo> = emptyList(),
)

data class TrackInfo(
    val title: String,
    val url: String,
    val durationSeconds: Long,
)
