package com.voxli.catalog.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, HistoryEntity::class, SettingsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VoxliDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun historyDao(): HistoryDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        private const val DB_NAME = "voxli.db"

        fun create(context: Context): VoxliDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                VoxliDatabase::class.java,
                DB_NAME,
            )

            // Use seed DB if available, otherwise start fresh
            try {
                context.assets.open("databases/voxli_seed.db").use { it.close() }
                builder.createFromAsset("databases/voxli_seed.db")
            } catch (_: Exception) {
                // seed file not in assets — build from scratch
            }

            return builder
                .addCallback(FtsCallback())
                .build()
        }

        /**
         * Creates FTS5 virtual table and triggers after the database is created
         * (whether from seed or from scratch).
         */
        private class FtsCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(
                        title, author,
                        content=books,
                        content_rowid=id,
                        tokenize='unicode61 remove_diacritics 1'
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS books_ai AFTER INSERT ON books BEGIN
                        INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author);
                    END
                """.trimIndent())

                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS books_ad AFTER DELETE ON books BEGIN
                        INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author);
                    END
                """.trimIndent())

                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS books_au AFTER UPDATE ON books BEGIN
                        INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author);
                        INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author);
                    END
                """.trimIndent())
            }
        }
    }
}
