package com.voxli.catalog.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Sanitizes a user query string for FTS5 MATCH.
 * Each word is wrapped in double quotes and suffixed with * for prefix search.
 * Special characters are handled by quoting.
 *
 * Example: "война и мир 1869" → ""война"* "и"* "мир"* "1869"*"
 */
fun sanitizeFtsQuery(query: String): String {
    return query.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
}

/**
 * Builds a [SupportSQLiteQuery] for FTS5 book search (joins books_fts → books).
 */
fun buildBookFtsQuery(sanitizedQuery: String): SupportSQLiteQuery = SimpleSQLiteQuery(
    """SELECT b.* FROM books b
       JOIN books_fts ON b.id = books_fts.rowid
       WHERE books_fts MATCH ?
       ORDER BY rank
       LIMIT 50""".trimMargin(),
    arrayOf(sanitizedQuery),
)

/**
 * Builds a [SupportSQLiteQuery] for FTS5 author search.
 */
fun buildAuthorFtsQuery(sanitizedQuery: String): SupportSQLiteQuery = SimpleSQLiteQuery(
    """SELECT DISTINCT b.author FROM books b
       JOIN books_fts ON b.id = books_fts.rowid
       WHERE books_fts MATCH ?
       ORDER BY b.author COLLATE NOCASE
       LIMIT 50""".trimMargin(),
    arrayOf(sanitizedQuery),
)
