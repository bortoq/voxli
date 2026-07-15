package com.voxli.catalog.db

import androidx.room.*

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE book_id = :bookId")
    suspend fun getHistory(bookId: Long): HistoryEntity?

    @Query("SELECT h.*, b.title, b.author, b.genre FROM history h JOIN books b ON h.book_id = b.id ORDER BY h.updated_at DESC")
    suspend fun getAllHistory(): List<HistoryWithBook>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(history: HistoryEntity)

    @Delete
    suspend fun deleteHistory(history: HistoryEntity)

    @Query("DELETE FROM history WHERE book_id = :bookId")
    suspend fun deleteHistoryByBook(bookId: Long)
}

data class HistoryWithBook(
    val bookId: Long,
    val status: String,
    val charOffset: Int,
    val progress: Double,
    val playbackPos: Long,
    val startedAt: String,
    val finishedAt: String?,
    val updatedAt: String,
    val title: String,
    val author: String,
    val genre: String,
)
