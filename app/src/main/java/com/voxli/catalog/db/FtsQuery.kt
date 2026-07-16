package com.voxli.catalog.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Sanitizes a user query string for FTS5 MATCH.
 * Produces valid FTS5 prefix syntax: `term*` for clean tokens,
 * `"term"` (exact) for tokens with special chars or FTS5 operators.
 *
 * FTS5 prefix `*` is only valid on unquoted terms, so we avoid `"term"*`.
 *
 * Example: "война и мир 1869" → "война* и* мир* 1869*"
 */
fun sanitizeFtsQuery(query: String): String {
    val ftsOperators = setOf("NOT", "AND", "OR", "NEAR")
    return query.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            val escaped = token.replace("\"", "\"\"")
            val isOperator = escaped.uppercase() in ftsOperators
            val hasSpecialChars = escaped.any { it in "*\"()+-:^" }
            if (isOperator || hasSpecialChars) {
                // Quoted exact match (prefix * does not work after quoted term)
                "\"$escaped\""
            } else {
                // Unquoted prefix match (valid FTS5 syntax)
                "${escaped}*"
            }
        }
        .ifEmpty { "" }
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
