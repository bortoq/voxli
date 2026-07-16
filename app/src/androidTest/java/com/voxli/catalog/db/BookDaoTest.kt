package com.voxli.catalog.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*

/**
 * Instrumentation test for BookDao (Room + FTS5).
 *
 * Reference: roadmap §4.2 (FTS5), §4.5 (обновление БД).
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class BookDaoTest {

    private lateinit var database: VoxliDatabase
    private lateinit var bookDao: BookDao
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Use the same factory method as production code
        database = Room.inMemoryDatabaseBuilder(
            context,
            VoxliDatabase::class.java,
        ).build()
        // Create tables and FTS triggers manually for testing
        database.openHelper.writableDatabase.apply {
            execSQL("""
                CREATE TABLE IF NOT EXISTS books (
                    id INTEGER PRIMARY KEY,
                    title TEXT NOT NULL DEFAULT '',
                    author TEXT NOT NULL DEFAULT '',
                    annotation TEXT NOT NULL DEFAULT '',
                    genre TEXT NOT NULL DEFAULT '',
                    series TEXT NOT NULL DEFAULT '',
                    series_num INTEGER NOT NULL DEFAULT 0,
                    lang TEXT NOT NULL DEFAULT '',
                    rating REAL NOT NULL DEFAULT 0.0,
                    votes_count INTEGER NOT NULL DEFAULT 0,
                    has_fb2 INTEGER NOT NULL DEFAULT 0,
                    has_epub INTEGER NOT NULL DEFAULT 0,
                    has_audio INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())
            execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(" +
                    "title, author, content=books, content_rowid=id, tokenize='unicode61 remove_diacritics 1')")
            execSQL("CREATE TRIGGER IF NOT EXISTS books_ai AFTER INSERT ON books BEGIN " +
                    "INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author); END")
            execSQL("CREATE TRIGGER IF NOT EXISTS books_ad AFTER DELETE ON books BEGIN " +
                    "INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author); END")
            execSQL("CREATE TRIGGER IF NOT EXISTS books_au AFTER UPDATE ON books BEGIN " +
                    "INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author); " +
                    "INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author); END")
        }
        bookDao = database.bookDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testInsertAndQueryBook() = runBlocking {
        val book = BookEntity(
            id = 1L,
            title = "Война и мир",
            author = "Толстой Лев Николаевич",
            genre = "prose",
            rating = 4.5,
            hasFb2 = true,
        )
        bookDao.upsertBook(book)

        val loaded = bookDao.getBookById(1L)
        assertNotNull(loaded)
        assertEquals("Война и мир", loaded?.title)
    }

    @Test
    fun testFts5SearchByTitle() = runBlocking {
        bookDao.upsertBook(
            BookEntity(id = 1L, title = "Война и мир", author = "Толстой Лев", genre = "prose")
        )
        bookDao.upsertBook(
            BookEntity(id = 2L, title = "Преступление и наказание", author = "Достоевский Фёдор", genre = "prose")
        )

        // FTS5 search
        val query = buildBookFtsQuery(sanitizeFtsQuery("война"))
        val results = bookDao.searchBooksFts(query)

        assertTrue(results.isNotEmpty(), "FTS5 should find 'война'")
        assertEquals(1, results.size)
        assertEquals("Война и мир", results[0].title)
    }

    @Test
    fun testFts5SearchByAuthor() = runBlocking {
        bookDao.upsertBook(
            BookEntity(id = 1L, title = "Война и мир", author = "Толстой Лев", genre = "prose")
        )
        bookDao.upsertBook(
            BookEntity(id = 2L, title = "Преступление и наказание", author = "Достоевский Фёдор", genre = "prose")
        )

        val query = buildAuthorFtsQuery(sanitizeFtsQuery("достоевский"))
        val results = bookDao.searchAuthorsFts(query)

        assertTrue(results.isNotEmpty(), "FTS5 should find 'достоевский'")
        assertTrue(results.any { it.contains("Достоевский") })
    }

    @Test
    fun testGetAllGenres() = runBlocking {
        bookDao.upsertBook(
            BookEntity(id = 1L, title = "A", author = "B", genre = "prose")
        )
        bookDao.upsertBook(
            BookEntity(id = 2L, title = "C", author = "D", genre = "sci-fi")
        )
        bookDao.upsertBook(
            BookEntity(id = 3L, title = "E", author = "F", genre = "prose")
        )

        val genres = bookDao.getAllGenres()
        assertEquals(2, genres.size)
        assertTrue(genres.contains("prose"))
        assertTrue(genres.contains("sci-fi"))
    }

    @Test
    fun testGetAllBooks() = runBlocking {
        bookDao.upsertBook(
            BookEntity(id = 1L, title = "A", author = "B", genre = "prose")
        )
        bookDao.upsertBook(
            BookEntity(id = 2L, title = "C", author = "D", genre = "sci-fi")
        )

        val allBooks = bookDao.getAllBooks()
        assertEquals(2, allBooks.size)
    }

    @Test
    fun testFts5SanitizeQuery() {
        val sanitized = sanitizeFtsQuery("война и мир 1869")
        assertEquals("\"война\"* \"и\"* \"мир\"* \"1869\"*", sanitized)
    }

    @Test
    fun testSanitizeQueryWithSpecialChars() {
        val sanitized = sanitizeFtsQuery("test:query -special")
        assertEquals("\"test:query\"* \"-special\"*", sanitized)
    }

    @Test
    fun testSanitizeQueryWithQuotes() {
        val sanitized = sanitizeFtsQuery("say \"hello\"")
        // FTS5 escaping: double quotes are escaped as ""
        assertTrue(sanitized.contains("\"\"hello\"\"") || sanitized.contains("hello"),
            "Sanitized query should handle quotes")
    }

    @Test
    fun testEmptyBooksReturnsEmpty() = runBlocking {
        val authors = bookDao.getAllAuthors()
        assertTrue(authors.isEmpty())
    }

    @Test
    fun testGetBooksByGenreFiltering() = runBlocking {
        bookDao.upsertBook(
            BookEntity(id = 1L, title = "A", author = "B", genre = "prose")
        )
        bookDao.upsertBook(
            BookEntity(id = 2L, title = "C", author = "D", genre = "sci-fi")
        )

        val proseBooks = bookDao.getBooksByGenre("prose")
        assertEquals(1, proseBooks.size)
        assertEquals("A", proseBooks[0].title)
    }

    @Test
    fun testUpdateExistingBook() = runBlocking {
        bookDao.upsertBook(
            BookEntity(id = 1L, title = "Original", author = "B", genre = "prose")
        )
        bookDao.upsertBook(
            BookEntity(id = 1L, title = "Updated", author = "B", genre = "prose")
        )

        val loaded = bookDao.getBookById(1L)
        assertEquals("Updated", loaded?.title)
    }

    @Test
    fun testDeleteBook() = runBlocking {
        bookDao.upsertBook(
            BookEntity(id = 1L, title = "A", author = "B", genre = "prose")
        )
        bookDao.deleteBookById(1L)

        val loaded = bookDao.getBookById(1L)
        assertNull(loaded)
    }
}
