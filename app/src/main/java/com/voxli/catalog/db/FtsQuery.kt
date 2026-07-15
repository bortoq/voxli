package com.voxli.catalog.db

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
