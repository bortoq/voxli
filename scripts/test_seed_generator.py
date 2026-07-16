#!/usr/bin/env python3
"""
Regression tests for create_seed_from_catalog.py.

Tests the MySQL INSERT parser and seed DB integrity checks.
Run with:  python3 -m pytest scripts/test_seed_generator.py
"""

import os
import sys
import tempfile
import sqlite3
import pytest

# Add script dir to path so we can import the module functions
SCRIPT_DIR = os.path.join(os.path.dirname(__file__))
sys.path.insert(0, SCRIPT_DIR)

from create_seed_from_catalog import (
    parse_mysql_values,
    _split_row_fields,
    verify_db,
    ROOM_HASH,
    OUTPUT,
)


# ── Unit tests: MySQL INSERT parser ───────────────────────────────

class TestParseMysqlValues:
    def test_simple_integers(self):
        sql = "INSERT INTO `t` VALUES (1,2,3),(4,5,6);"
        rows = parse_mysql_values(sql)
        assert rows == [["1", "2", "3"], ["4", "5", "6"]]

    def test_single_row(self):
        sql = "INSERT INTO t VALUES (42, 'hello', 'world');"
        rows = parse_mysql_values(sql)
        assert len(rows) == 1
        assert rows[0][0] == "42"
        assert rows[0][1] == "hello"
        assert rows[0][2] == "world"

    def test_quoted_strings_with_commas(self):
        sql = "INSERT INTO t VALUES (1, 'hello, world', 'test');"
        rows = parse_mysql_values(sql)
        assert rows[0][1] == "hello, world"

    def test_escaped_quotes_mysql(self):
        sql = "INSERT INTO t VALUES (1, 'it''s fine', 'normal');"
        rows = parse_mysql_values(sql)
        assert rows[0][1] == "it's fine"

    def test_empty_numeric(self):
        sql = "INSERT INTO t VALUES (1,,3);"
        rows = parse_mysql_values(sql)
        assert rows[0][1] == ""

    def test_null_value(self):
        sql = "INSERT INTO t VALUES (1, NULL, 'text');"
        rows = parse_mysql_values(sql)
        assert rows[0][1] == ""

    def test_real_genrelist_row(self):
        # Actual format from lib.libgenrelist.sql.gz
        sql = ("INSERT INTO `libgenrelist` VALUES "
               "(1,'sf_history','Альтернативная история','Фантастика'),"
               "(2,'sf_action','Боевая фантастика и фэнтези','Фантастика');")
        rows = parse_mysql_values(sql)
        assert len(rows) == 2
        assert rows[0][0] == "1"
        assert rows[0][1] == "sf_history"
        assert rows[0][2] == "Альтернативная история"
        assert rows[1][2] == "Боевая фантастика и фэнтези"

    def test_real_bookgenre_row(self):
        # Actual format from lib.libgenre.sql.gz: (Id, BookId, GenreId)
        sql = "INSERT INTO `libgenre` VALUES (1,1001,11),(2,1002,25),(3,1003,11);"
        rows = parse_mysql_values(sql)
        assert len(rows) == 3
        assert rows[0] == ["1", "1001", "11"]
        assert rows[1] == ["2", "1002", "25"]

    def test_no_values_keyword(self):
        sql = "CREATE TABLE foo (id INT);"
        rows = parse_mysql_values(sql)
        assert rows == []

    def test_empty_string(self):
        assert parse_mysql_values("") == []
        assert parse_mysql_values("  ") == []

    def test_multiline_insert(self):
        sql = """INSERT INTO `libgenre` VALUES
            (1,1001,11),
            (2,1002,25);"""
        rows = parse_mysql_values(sql)
        assert len(rows) == 2


class TestSplitRowFields:
    def test_simple(self):
        assert _split_row_fields("1,'hello','world'") == ["1", "hello", "world"]

    def test_with_spaces(self):
        assert _split_row_fields("  1 , 'a' , 'b'  ") == ["1", "a", "b"]

    def test_empty_field(self):
        assert _split_row_fields("1,,3") == ["1", "", "3"]


# ── Integration tests: verify_db ──────────────────────────────────

class TestVerifyDb:
    @pytest.fixture
    def valid_db(self):
        """Create a temporary DB that satisfies all regression checks (≥50 genres)."""
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        db_path = tmp.name
        conn = sqlite3.connect(db_path)
        conn.execute("CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT NOT NULL DEFAULT '', "
                     "author TEXT NOT NULL DEFAULT '', annotation TEXT NOT NULL DEFAULT '', "
                     "genre TEXT NOT NULL DEFAULT '', series TEXT NOT NULL DEFAULT '', "
                     "series_num INTEGER NOT NULL DEFAULT 0, lang TEXT NOT NULL DEFAULT '', "
                     "rating REAL NOT NULL DEFAULT 0.0, votes_count INTEGER NOT NULL DEFAULT 0, "
                     "has_fb2 INTEGER NOT NULL DEFAULT 0, has_epub INTEGER NOT NULL DEFAULT 0, "
                     "has_audio INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT '')")

        # Insert 200 books with 60 different genres (≥50 threshold)
        genres = [f"genre_{i}" for i in range(60)]
        for i in range(200):
            conn.execute("INSERT INTO books (id, title, author, genre) VALUES (?, ?, ?, ?)",
                         (i + 1, f"Book {i}", f"Author {i}", genres[i % 60]))

        conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(title, author, content=books, content_rowid=id)")
        conn.execute("INSERT INTO books_fts(books_fts) VALUES ('rebuild')")
        conn.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        conn.execute("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", (ROOM_HASH,))
        conn.commit()
        conn.close()
        yield db_path
        os.unlink(db_path)

    @pytest.fixture
    def empty_db(self):
        """DB with no books — should FAIL regression."""
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        db_path = tmp.name
        conn = sqlite3.connect(db_path)
        conn.execute("CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT NOT NULL DEFAULT '', "
                     "author TEXT NOT NULL DEFAULT '', annotation TEXT NOT NULL DEFAULT '', "
                     "genre TEXT NOT NULL DEFAULT '', series TEXT NOT NULL DEFAULT '', "
                     "series_num INTEGER NOT NULL DEFAULT 0, lang TEXT NOT NULL DEFAULT '', "
                     "rating REAL NOT NULL DEFAULT 0.0, votes_count INTEGER NOT NULL DEFAULT 0, "
                     "has_fb2 INTEGER NOT NULL DEFAULT 0, has_epub INTEGER NOT NULL DEFAULT 0, "
                     "has_audio INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT '')")
        conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(title, author, content=books, content_rowid=id)")
        conn.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        conn.execute("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", (ROOM_HASH,))
        conn.commit()
        conn.close()
        yield db_path
        os.unlink(db_path)

    @pytest.fixture
    def no_genre_db(self):
        """DB with books but no genres — should FAIL."""
        tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
        db_path = tmp.name
        conn = sqlite3.connect(db_path)
        conn.execute("CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT NOT NULL DEFAULT '', "
                     "author TEXT NOT NULL DEFAULT '', annotation TEXT NOT NULL DEFAULT '', "
                     "genre TEXT NOT NULL DEFAULT '', series TEXT NOT NULL DEFAULT '', "
                     "series_num INTEGER NOT NULL DEFAULT 0, lang TEXT NOT NULL DEFAULT '', "
                     "rating REAL NOT NULL DEFAULT 0.0, votes_count INTEGER NOT NULL DEFAULT 0, "
                     "has_fb2 INTEGER NOT NULL DEFAULT 0, has_epub INTEGER NOT NULL DEFAULT 0, "
                     "has_audio INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT '')")
        for i in range(100):
            conn.execute("INSERT INTO books (id, title, author, genre) VALUES (?, ?, ?, '')",
                         (i + 1, f"Book {i}", f"Author {i}"))
        conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(title, author, content=books, content_rowid=id)")
        conn.execute("INSERT INTO books_fts(books_fts) VALUES ('rebuild')")
        conn.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        conn.execute("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", (ROOM_HASH,))
        conn.commit()
        conn.close()
        yield db_path
        os.unlink(db_path)

    def test_valid_db_passes_regression(self, valid_db, monkeypatch):
        monkeypatch.setattr("create_seed_from_catalog.OUTPUT", valid_db)
        stats = verify_db()
        assert stats["count"] == 200
        assert stats["genres"] == 60
        assert stats["fts"] == 200
        assert stats["room_hash"] == ROOM_HASH

    def test_empty_db_fails_regression(self, empty_db, monkeypatch):
        monkeypatch.setattr("create_seed_from_catalog.OUTPUT", empty_db)
        with pytest.raises(Exception) as excinfo:
            verify_db()
        assert "regression" in str(excinfo.value).lower()

    def test_no_genre_db_fails_regression(self, no_genre_db, monkeypatch):
        monkeypatch.setattr("create_seed_from_catalog.OUTPUT", no_genre_db)
        with pytest.raises(Exception) as excinfo:
            verify_db()
        assert "regression" in str(excinfo.value).lower()


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
