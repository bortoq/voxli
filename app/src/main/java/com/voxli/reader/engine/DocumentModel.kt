package com.voxli.reader.engine

/**
 * Block with formatting. charOffset is the global character index
 * through the entire book text (for progress persistence).
 *
 * Reference: roadmap §7.5
 */
data class ParagraphBlock(
    val text: String,                    // plain text content
    val globalCharStart: Int,            // global char offset start
    val globalCharEnd: Int,              // global char offset end (exclusive)
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isHeader: Boolean = false,
)

data class BookImage(
    val bytes: ByteArray? = null,
    val url: String? = null,
    val altText: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BookImage) return false
        return altText == other.altText && url == other.url && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes?.contentHashCode() ?: 0
        result = 31 * result + (url?.hashCode() ?: 0)
        result = 31 * result + altText.hashCode()
        return result
    }
}

/**
 * The complete parsed document, ready for pagination.
 *
 * Reference: roadmap §7.5 — DocumentModel contract.
 */
data class DocumentModel(
    val bookId: Long,
    val title: String,
    val author: String,
    val paragraphs: List<ParagraphBlock>,
    val totalChars: Int,
)

/**
 * Interface for all book format parsers.
 */
interface BookParser {
    /** Parse a book file into a DocumentModel. */
    suspend fun parse(file: java.io.File): DocumentModel
}
