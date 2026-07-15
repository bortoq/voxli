package com.voxli.catalog.db

import androidx.room.*

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY rating DESC LIMIT :limit")
    suspend fun getTopBooks(limit: Int = 50): List<BookEntity>

    @Query("SELECT * FROM books ORDER BY rating DESC")
    suspend fun getAllBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): BookEntity?

    @Query("SELECT DISTINCT author FROM books ORDER BY author COLLATE NOCASE")
    suspend fun getAllAuthors(): List<String>

    @Query("SELECT * FROM books WHERE author = :author ORDER BY series_num, title")
    suspend fun getBooksByAuthor(author: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE genre = :genre ORDER BY rating DESC")
    suspend fun getBooksByGenre(genre: String): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBooks(books: List<BookEntity>)

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: Long)

    // FTS5 search via raw query (see VoxliDatabase for FTS trigger setup)
    @Query("""
        SELECT b.* FROM books b
        JOIN books_fts ON b.id = books_fts.rowid
        WHERE books_fts MATCH :query
        ORDER BY rank
        LIMIT 50
    """)
    suspend fun searchBooksFts(query: String): List<BookEntity>

    @Query("""
        SELECT DISTINCT b.author FROM books b
        JOIN books_fts ON b.id = books_fts.rowid
        WHERE books_fts MATCH :query
        ORDER BY b.author COLLATE NOCASE
        LIMIT 50
    """)
    suspend fun searchAuthorsFts(query: String): List<String>

    @Query("SELECT * FROM books WHERE has_audio = 0 ORDER BY RANDOM() LIMIT :limit")
    suspend fun getBooksNeedingAudioCheck(limit: Int = 100): List<BookEntity>
}
