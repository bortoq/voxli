package com.voxli.flibusta.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Flibusta OPDS provider.
 * Handles catalog traversal, search, book page parsing, and download links.
 */
class FlibustaProvider(
    private val client: OkHttpClient,
    private val mirrorList: List<String> = DEFAULT_MIRRORS,
) {
    private var activeMirror: String = mirrorList.first()
    private var mirrorIndex: Int = 0

    companion object {
        val DEFAULT_MIRRORS = listOf(
            "http://flibusta.is",
            "http://flibusta.site",
            "http://flibusta.net",
        )

        private const val OPDS_ROOT = "/opds/"
        private const val OPDS_NEW = "/opds/new/0/new"
        private const val OPDS_SEARCH_BOOKS = "/opds/search?searchType=books&searchTerm="
        private const val OPDS_SEARCH_AUTHORS = "/opds/search?searchType=authors&searchTerm="
        private const val BOOK_PAGE = "/b/"
    }

    /** Returns the currently active mirror URL. */
    fun getActiveMirror(): String = activeMirror

    /** Try to switch to the next available mirror. Returns true if switched. */
    suspend fun trySwitchMirror(): Boolean = withContext(Dispatchers.IO) {
        for (i in mirrorList.indices) {
            val idx = (mirrorIndex + 1 + i) % mirrorList.size
            val mirror = mirrorList[idx]
            try {
                val request = okhttp3.Request.Builder()
                    .url("$mirror/")
                    .head()
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    activeMirror = mirror
                    mirrorIndex = idx
                    response.close()
                    return@withContext true
                }
                response.close()
            } catch (_: Exception) { }
        }
        return@withContext false
    }

    // ---- OPDS endpoints ----

    fun opdsRootUrl(): String = "$activeMirror$OPDS_ROOT"

    fun opdsNewUrl(offset: Int = 0): String = "$activeMirror$OPDS_NEW?offset=$offset"

    fun opdsSearchBooksUrl(query: String): String =
        "$activeMirror$OPDS_SEARCH_BOOKS${java.net.URLEncoder.encode(query, "UTF-8")}"

    fun opdsSearchAuthorsUrl(query: String): String =
        "$activeMirror$OPDS_SEARCH_AUTHORS${java.net.URLEncoder.encode(query, "UTF-8")}"

    fun bookPageUrl(bookId: Long): String = "$activeMirror$BOOK_PAGE$bookId/"

    fun downloadUrl(bookId: Long, format: String): String =
        "$activeMirror$BOOK_PAGE$bookId/$format"

    // ---- HTTP helpers ----

    suspend fun fetchUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                response.close()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetch and parse an OPDS feed, returning book entries.
     * Supports feeds at /opds/new, /opds/search?searchType=books, etc.
     */
    suspend fun fetchOpdsFeed(url: String): List<OpdsBookEntry> = withContext(Dispatchers.IO) {
        val xml = fetchUrl(url) ?: return@withContext emptyList()
        parseOpdsEntry(xml)
    }

    /** Parse OPDS XML and extract book metadata entries. */
    fun parseOpdsEntry(xml: String): List<OpdsBookEntry> {
        if (xml.isBlank()) return emptyList()

        val entries = mutableListOf<OpdsBookEntry>()

        // Extract each <entry>…</entry> block
        val entryRegex = Regex("<entry[^>]*>(.*?)</entry\\s*>", RegexOption.DOT_MATCHES_ALL)

        for (entryMatch in entryRegex.findAll(xml)) {
            val block = entryMatch.groupValues[1]

            // id – urn:flibusta:NNNNN or similar
            val id = extractTag(block, "id")
                ?.replace(Regex("[^0-9]"), "")
                ?.toLongOrNull() ?: continue

            val title = extractTag(block, "title")?.trim() ?: continue

            val author = extractTag(block, "author", "name")?.trim() ?: ""

            // category label or term attribute
            val genre = extractAttr(block, "category", "label")
                ?: extractAttr(block, "category", "term")
                ?: ""

            // content – CDATA or plain text
            val annotation = extractTag(block, "content")?.trim() ?: ""

            // Scan all <link> tags for acquisition rels
            val linkRegex = Regex(
                """<link\s+[^>]*href\s*=\s*"([^"]*)"[^>]*>""",
                RegexOption.DOT_MATCHES_ALL
            )
            var hasFb2 = false
            var hasEpub = false
            for (linkMatch in linkRegex.findAll(block)) {
                val href = linkMatch.groupValues[1]
                if (href.contains("/fb2", ignoreCase = true)) hasFb2 = true
                if (href.contains("/epub", ignoreCase = true)) hasEpub = true
            }

            val createdAt = extractTag(block, "published")?.trim() ?: ""
            val updatedAt = extractTag(block, "updated")?.trim() ?: ""

            entries.add(
                OpdsBookEntry(
                    id = id,
                    title = title,
                    author = author,
                    genre = genre,
                    annotation = annotation,
                    hasFb2 = hasFb2,
                    hasEpub = hasEpub,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
            )
        }

        return entries
    }

    // ---- private helpers ----

    /** Extract text content of a child tag within [block]. */
    private fun extractTag(block: String, tag: String): String? {
        val regex = Regex(
            "<$tag[^>]*>(.*?)</$tag\\s*>",
            RegexOption.DOT_MATCHES_ALL
        )
        return regex.find(block)?.groupValues?.get(1)?.trim()
    }

    /** Extract text content of a nested grandchild tag within [block].
     *  E.g. extractTag(block, "author", "name") extracts <author><name>…</name></author> */
    private fun extractTag(block: String, parent: String, child: String): String? {
        val parentRegex = Regex(
            "<$parent[^>]*>(.*?)</$parent\\s*>",
            RegexOption.DOT_MATCHES_ALL
        )
        val parentBlock = parentRegex.find(block)?.groupValues?.get(1) ?: return null
        val childRegex = Regex(
            "<$child[^>]*>(.*?)</$child\\s*>",
            RegexOption.DOT_MATCHES_ALL
        )
        return childRegex.find(parentBlock)?.groupValues?.get(1)?.trim()
    }

    /** Extract an attribute value from the first occurrence of [tag] in [block]. */
    private fun extractAttr(block: String, tag: String, attr: String): String? {
        // Match <tag ... attr="value" ...> or <tag ... attr='value' ...>
        val regex = Regex(
            """<$tag\s+[^>]*\b$attr\s*=\s*"([^"]*)"[^>]*>""",
            RegexOption.DOT_MATCHES_ALL
        )
        return regex.find(block)?.groupValues?.get(1)
    }
}

/** Lightweight OPDS entry representation. */
data class OpdsBookEntry(
    val id: Long,
    val title: String,
    val author: String,
    val genre: String,
    val annotation: String,
    val hasFb2: Boolean,
    val hasEpub: Boolean,
    val createdAt: String,
    val updatedAt: String,
)


