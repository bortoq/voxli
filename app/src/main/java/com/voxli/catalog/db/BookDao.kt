package com.voxli.catalog.db

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
abstract class BookDao {
    @Query("SELECT * FROM books ORDER BY rating DESC LIMIT :limit")
    abstract suspend fun getTopBooks(limit: Int = 50): List<BookEntity>

    @Query("SELECT * FROM books WHERE genre IN (:genres) ORDER BY rating DESC")
    abstract suspend fun getBooksByGenresRaw(genres: Collection<String>): List<BookEntity>

    /** Safe wrapper: returns empty list for empty genre filter (avoids SQLite WHERE IN () syntax error). */
    suspend fun getBooksByGenres(genres: Collection<String>): List<BookEntity> {
        if (genres.isEmpty()) return emptyList()
        return getBooksByGenresRaw(genres)
    }

    @Query("SELECT * FROM books ORDER BY rating DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun getAllBooks(limit: Int = 10000, offset: Int = 0): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :bookId")
    abstract suspend fun getBookById(bookId: Long): BookEntity?

    @Query("SELECT DISTINCT author FROM books WHERE genre IN (:genres) AND author != '' ORDER BY author COLLATE NOCASE")
    abstract suspend fun getAuthorsByGenresRaw(genres: Collection<String>): List<String>

    suspend fun getAuthorsByGenres(genres: Collection<String>): List<String> {
        if (genres.isEmpty()) return emptyList()
        return getAuthorsByGenresRaw(genres)
    }

    @Query("SELECT * FROM books WHERE author = :author ORDER BY series_num, title")
    abstract suspend fun getBooksByAuthor(author: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE genre = :genre ORDER BY rating DESC")
    abstract suspend fun getBooksByGenre(genre: String): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertBooks(books: List<BookEntity>)

    @Delete
    abstract suspend fun deleteBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :bookId")
    abstract suspend fun deleteBookById(bookId: Long)

    // FTS5 search via raw query (see VoxliDatabase for FTS trigger setup).
    // @RawQuery is used because Room does not natively support FTS5 tables.
    @RawQuery(observedEntities = [BookEntity::class])
    abstract suspend fun searchBooksFts(query: SupportSQLiteQuery): List<BookEntity>

    @RawQuery(observedEntities = [BookEntity::class])
    abstract suspend fun searchAuthorsFts(query: SupportSQLiteQuery): List<String>

    @Query("SELECT DISTINCT genre FROM books WHERE genre != '' ORDER BY genre COLLATE NOCASE")
    abstract suspend fun getAllGenres(): List<String>

    @Query("SELECT * FROM books WHERE has_audio = 0 ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun getBooksNeedingAudioCheck(limit: Int = 100): List<BookEntity>
}
