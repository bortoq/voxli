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
                    return@withContext true
                }
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
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Parse OPDS XML entry to extract book metadata (stub — full parser in Phase 2). */
    fun parseOpdsEntry(xml: String): List<OpdsBookEntry> {
        // TODO: implement Ksoup-based OPDS parser in Phase 2
        return emptyList()
    }

    /** Parse HTML book page for rating and download links. */
    fun parseBookPage(html: String): BookPageInfo? {
        // TODO: implement Ksoup-based parser in Phase 2
        return null
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

/** Parsed book page info. */
data class BookPageInfo(
    val rating: Double,
    val votesCount: Int,
    val fb2Url: String?,
    val epubUrl: String?,
)
