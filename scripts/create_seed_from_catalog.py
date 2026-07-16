#!/usr/bin/env python3
"""
Generate voxli_seed.db from Flibusta's official catalog + SQL genre dumps.

Downloads:
  - http://flibusta.is/catalog/catalog.zip   (~60 MB) — book list
  - http://flibusta.is/sql/lib.libgenrelist.sql.gz (~7 KB) — genre names
  - http://flibusta.is/sql/lib.libgenre.sql.gz   (~8 MB) — book↔genre mapping

Creates a Room-compatible SQLite database with FTS5 index.
Genre data is merged from SQL dumps so the app can filter by genre.

Usage:
  python3 scripts/create_seed_from_catalog.py [--catalog catalog.zip]

Regression assertions (fail if violated):
  - books > 0
  - genres > 50
  - FTS rows == books
  - Room identity_hash matches schema
"""

import sqlite3
import sys
import os
import time
import csv
import io
import re
import zipfile
import urllib.request
import gzip

# ── Paths ──────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, ".."))
OUTPUT = os.path.join(
    PROJECT_ROOT, "app", "src", "main", "assets", "databases", "voxli_seed.db"
)
CATALOG_URL = "http://flibusta.is/catalog/catalog.zip"
CATALOG_PATH = os.path.join(SCRIPT_DIR, "catalog.zip")

GENRELIST_URL = "http://flibusta.is/sql/lib.libgenrelist.sql.gz"
GENRELIST_PATH = os.path.join(SCRIPT_DIR, "libgenrelist.sql.gz")
GENRE_URL = "http://flibusta.is/sql/lib.libgenre.sql.gz"
GENRE_PATH = os.path.join(SCRIPT_DIR, "libgenre.sql.gz")

# Room identity hash from app/schemas/VoxliDatabase/1.json
ROOM_HASH = "92184ce86527b2f270052e1d2873ad57"

# FTS5 triggers matching VoxliDatabase.FtsCallback
FTS_TRIGGERS = [
    ("books_ai", "AFTER INSERT",
     "INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author);"),
    ("books_ad", "AFTER DELETE",
     "INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author);"),
    ("books_au", "AFTER UPDATE",
     "INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author); "
     "INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author);"),
]

# ── Download helpers ───────────────────────────────────────────────

def download(url: str, path: str, label: str, min_size: int = 1000) -> str:
    """Download a file if not cached. Returns path."""
    if os.path.exists(path) and os.path.getsize(path) > min_size:
        print(f"  Using cached {label}: {path}")
        return path
    print(f"  Downloading {label}: {url} ...", flush=True)
    t0 = time.time()
    req = urllib.request.Request(url, headers={"User-Agent": "Voxli/0.1 (seed-generator)"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = resp.read()
    with open(path, "wb") as f:
        f.write(data)
    elapsed = time.time() - t0
    print(f"    {len(data):,} bytes in {elapsed:.0f}s ({len(data)/1024/1024:.1f} MB)")
    return path


# ── MySQL INSERT parser ───────────────────────────────────────────

def parse_mysql_values(sql_line: str) -> list[list[str]]:
    """
    Parse MySQL INSERT INTO ... VALUES (row),(row),...
    Returns list of rows, each row is a list of string fields.
    Handles quoted strings ('' escape), commas inside quotes, numeric fields.
    """
    m = re.search(r"VALUES\s*(.*)", sql_line, re.DOTALL)
    if not m:
        return []
    values = m.group(1).strip().rstrip(";").rstrip()

    rows = []
    i = 0
    n = len(values)

    while i < n:
        if values[i] == '(':
            depth = 1
            j = i + 1
            in_string = False
            while j < n and depth > 0:
                ch = values[j]
                if in_string:
                    if ch == "'" and (j + 1 >= n or values[j + 1] != "'"):
                        in_string = False
                    elif ch == "'" and j + 1 < n and values[j + 1] == "'":
                        j += 1  # skip escaped quote ''
                else:
                    if ch == "'":
                        in_string = True
                    elif ch == '(':
                        depth += 1
                    elif ch == ')':
                        depth -= 1
                j += 1

            if depth == 0:
                row_str = values[i + 1 : j - 1]
                fields = _split_row_fields(row_str)
                rows.append(fields)
            i = j
        else:
            i += 1
    return rows


def _split_row_fields(row_str: str) -> list[str]:
    """
    Split a single (val, val, ...) row string into separate fields.
    Handles empty fields (consecutive commas), quoted strings, NULL.
    """
    fields = []
    i = 0
    n = len(row_str)

    while i < n:
        # Skip leading whitespace (except inside quoted strings)
        while i < n and row_str[i] in (' ', '\t', '\n', '\r'):
            i += 1
        if i >= n:
            break

        if row_str[i] == "'":
            # Quoted string
            i += 1
            start = i
            while i < n:
                if row_str[i] == "'" and (i + 1 >= n or row_str[i + 1] != "'"):
                    break
                elif row_str[i] == "'" and i + 1 < n and row_str[i + 1] == "'":
                    i += 1  # skip escaped quote ''
                i += 1
            fields.append(row_str[start:i].replace("''", "'"))
            i += 1  # skip closing quote
            # Skip whitespace before comma
            while i < n and row_str[i] in (' ', '\t', '\n', '\r'):
                i += 1
            if i < n and row_str[i] == ',':
                i += 1
        elif row_str[i] == ',':
            # Empty field between commas
            fields.append('')
            i += 1
        else:
            # Unquoted value (number, NULL, etc.)
            start = i
            while i < n and row_str[i] not in (',', ' ', '\t', '\n', '\r'):
                i += 1
            val = row_str[start:i].strip()
            if val == 'NULL':
                fields.append('')
            else:
                fields.append(val)
            if i < n and row_str[i] == ',':
                i += 1
            elif i < n and row_str[i] in (' ', '\t', '\n', '\r'):
                # Trailing whitespace before comma or end
                j = i
                while j < n and row_str[j] in (' ', '\t', '\n', '\r'):
                    j += 1
                if j < n and row_str[j] == ',':
                    i = j + 1
                else:
                    i = j

    return fields


# ── Genre data loading ────────────────────────────────────────────

def load_genre_list(path: str) -> dict[int, str]:
    """
    Parse lib.libgenrelist.sql.gz → dict[genre_id, genre_desc].
    Format: (GenreId, GenreCode, GenreDesc, GenreMeta)
    We store GenreDesc as the genre name for the app.
    """
    genre_map: dict[int, str] = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line.startswith("INSERT INTO"):
                continue
            rows = parse_mysql_values(line)
            for row in rows:
                if len(row) >= 3:
                    try:
                        gid = int(row[0])
                        desc = row[2]
                        if desc:
                            genre_map[gid] = desc
                    except (ValueError, IndexError):
                        continue
    print(f"  Loaded {len(genre_map):,} genre definitions")
    return genre_map


def load_book_genres(path: str) -> dict[int, list[int]]:
    """
    Parse lib.libgenre.sql.gz → dict[book_id, list[genre_id]].
    Format: (Id, BookId, GenreId)
    """
    book_genres: dict[int, list[int]] = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line.startswith("INSERT INTO"):
                continue
            rows = parse_mysql_values(line)
            for row in rows:
                if len(row) >= 3:
                    try:
                        book_id = int(row[1])
                        genre_id = int(row[2])
                        book_genres.setdefault(book_id, []).append(genre_id)
                    except (ValueError, IndexError):
                        continue
    print(f"  Loaded {len(book_genres):,} book-genre mappings")
    return book_genres


# ── Catalog parsing ───────────────────────────────────────────────

def parse_catalog(catalog_path: str) -> list[dict]:
    """Parse catalog.txt from the zip archive. Returns list of book dicts."""
    books = []
    seen_ids: set[int] = set()

    with zipfile.ZipFile(catalog_path) as zf:
        txt_name = [n for n in zf.namelist() if n.endswith(".txt")][0]
        print(f"  Reading {txt_name} from archive ...", flush=True)

        with zf.open(txt_name, "r") as f:
            text_io = io.TextIOWrapper(f, encoding="utf-8", errors="replace")
            reader = csv.reader(text_io, delimiter=";", quotechar='"')

            header = next(reader, None)
            if header is None:
                print("  ERROR: Empty catalog file!")
                sys.exit(1)

            # Columns: Last Name;First Name;Middle Name;Title;Subtitle;Language;Year;Series;ID
            row_count = 0
            t0 = time.time()

            for row in reader:
                row_count += 1
                if row_count % 200_000 == 0:
                    elapsed = time.time() - t0
                    rate = row_count / (elapsed or 1)
                    print(f"    Parsed {row_count:,} rows ({rate:.0f} rows/s)", flush=True)

                if len(row) < 9:
                    continue

                book_id_str = row[8].strip()
                if not book_id_str.isdigit():
                    continue
                book_id = int(book_id_str)
                if book_id in seen_ids:
                    continue
                seen_ids.add(book_id)

                parts = [row[0].strip(), row[1].strip(), row[2].strip()]
                author = " ".join(p for p in parts if p)

                title = row[3].strip()
                if not title:
                    continue

                series = row[7].strip()
                lang = row[5].strip()

                # Extract series number from trailing [N]
                series_num = 0
                if series:
                    m = re.search(r"\[(\d+)\]$", series)
                    if m:
                        series_num = int(m.group(1))
                        series = re.sub(r"\s*\[\d+\]$", "", series).strip()

                books.append({
                    "id": book_id,
                    "title": title,
                    "author": author,
                    "annotation": "",
                    "genre": "",
                    "series": series,
                    "series_num": series_num,
                    "lang": lang if lang else "ru",
                    "rating": 0.0,
                    "votes_count": 0,
                    "has_fb2": 1,
                    "has_epub": 0,
                    "has_audio": 0,
                    "created_at": "",
                })

    elapsed = time.time() - t0
    print(f"  Parsed {row_count:,} total rows, {len(books):,} unique books in {elapsed:.0f}s")
    return books


def apply_genres(books: list[dict], genre_map: dict[int, str],
                 book_genres: dict[int, list[int]]) -> int:
    """Assign genre names to books. Returns count of books that got a genre."""
    updated = 0
    for book in books:
        gids = book_genres.get(book["id"])
        if gids:
            # Use the first genre as the primary genre
            gid = gids[0]
            genre_name = genre_map.get(gid, "")
            if genre_name:
                book["genre"] = genre_name
                updated += 1
    print(f"  Applied genres to {updated:,} / {len(books):,} books")
    return updated


# ── DB creation ───────────────────────────────────────────────────

def create_db(books: list[dict]) -> int:
    """Create the Room-compatible SQLite database. Returns insert count."""
    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    if os.path.exists(OUTPUT):
        os.remove(OUTPUT)

    conn = sqlite3.connect(OUTPUT)
    conn.isolation_level = None
    conn.execute("PRAGMA journal_mode=MEMORY")
    conn.execute("PRAGMA synchronous=OFF")
    conn.execute("PRAGMA cache_size=-80000")

    # ── Schema ──
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS books (
            id INTEGER NOT NULL PRIMARY KEY,
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
        );
        CREATE TABLE IF NOT EXISTS history (
            book_id INTEGER NOT NULL PRIMARY KEY,
            status TEXT NOT NULL DEFAULT 'reading',
            char_offset INTEGER NOT NULL DEFAULT 0,
            progress REAL NOT NULL DEFAULT 0.0,
            playback_pos INTEGER NOT NULL DEFAULT 0,
            started_at TEXT NOT NULL DEFAULT '',
            finished_at TEXT DEFAULT NULL,
            updated_at TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT NOT NULL PRIMARY KEY,
            value TEXT NOT NULL DEFAULT ''
        );
        CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(
            title, author,
            content=books,
            content_rowid=id,
            tokenize='unicode61 remove_diacritics 1'
        );
        CREATE INDEX IF NOT EXISTS idx_books_author ON books(author COLLATE NOCASE);
        CREATE INDEX IF NOT EXISTS idx_books_genre ON books(genre);
        CREATE INDEX IF NOT EXISTS idx_books_rating ON books(rating);
        CREATE INDEX IF NOT EXISTS idx_books_title ON books(title COLLATE NOCASE);
    """)

    for name, timing, stmt in FTS_TRIGGERS:
        conn.execute(f"CREATE TRIGGER IF NOT EXISTS {name} {timing} ON books BEGIN {stmt} END")

    # Room master table
    conn.execute(
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
    conn.execute(
        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)",
        (ROOM_HASH,))
    conn.commit()

    # ── Bulk insert ──
    t0 = time.time()
    batch_size = 50_000
    inserted = 0

    for start in range(0, len(books), batch_size):
        batch = books[start:start + batch_size]
        conn.execute("BEGIN")
        conn.executemany(
            """INSERT INTO books
            (id, title, author, annotation, genre, series, series_num,
             lang, rating, votes_count, has_fb2, has_epub, has_audio, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            [(b["id"], b["title"], b["author"], b["annotation"], b["genre"],
              b["series"], b["series_num"], b["lang"], b["rating"], b["votes_count"],
              b["has_fb2"], b["has_epub"], b["has_audio"], b["created_at"])
             for b in batch],
        )
        conn.commit()
        inserted += len(batch)
        elapsed = time.time() - t0
        rate = inserted / (elapsed or 1)
        print(f"    Inserted {inserted:,} books ({rate:.0f} rows/s)", flush=True)

    print(f"  Inserted {inserted:,} books in {time.time() - t0:.0f}s")

    # ── Rebuild FTS ──
    print("  Rebuilding FTS index ...", flush=True)
    t0 = time.time()
    conn.execute("INSERT INTO books_fts(books_fts) VALUES ('rebuild')")
    conn.commit()
    print(f"  FTS index rebuilt in {time.time() - t0:.0f}s")

    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.execute("PRAGMA user_version = 1")
    conn.commit()
    conn.close()
    return inserted


# ── Verification + regression checks ──────────────────────────────

class RegressionError(Exception):
    """Raised when a regression assertion fails."""


def verify_db() -> dict:
    """Verify DB integrity. Raises RegressionError on failure."""
    conn = sqlite3.connect(OUTPUT)
    count = conn.execute("SELECT COUNT(*) FROM books").fetchone()[0]
    genre_count = conn.execute(
        "SELECT COUNT(DISTINCT genre) FROM books WHERE genre != ''"
    ).fetchone()[0]
    authors = conn.execute(
        "SELECT COUNT(DISTINCT author) FROM books WHERE author != ''"
    ).fetchone()[0]
    fts = conn.execute("SELECT COUNT(*) FROM books_fts").fetchone()[0]
    room = conn.execute(
        "SELECT identity_hash FROM room_master_table WHERE id = 42"
    ).fetchone()
    sample_genres = conn.execute(
        "SELECT DISTINCT genre FROM books WHERE genre != '' LIMIT 10"
    ).fetchall()
    conn.close()

    # ── Regression assertions ──
    errors = []

    if count == 0:
        errors.append(f"REGRESSION: books table is EMPTY")

    if genre_count < 50:
        errors.append(
            f"REGRESSION: only {genre_count} genres (expected ≥50). "
            "App genre filtering will break."
        )

    if fts != count:
        errors.append(
            f"REGRESSION: FTS index has {fts} rows but books has {count} rows. "
            "Search will not work correctly."
        )

    if room is None or room[0] != ROOM_HASH:
        errors.append(
            f"REGRESSION: Room identity_hash mismatch. "
            f"Expected {ROOM_HASH}, got {room[0] if room else 'NONE'}. "
            "Room will reject the database."
        )

    if errors:
        print("\n  ❌  REGRESSION CHECKS FAILED:")
        for err in errors:
            print(f"       - {err}")
        raise RegressionError(
            f"{len(errors)} regression check(s) failed. See above."
        )

    print(f"  ✅  All regression checks passed")

    size = os.path.getsize(OUTPUT)
    return {
        "count": count,
        "genres": genre_count,
        "authors": authors,
        "fts": fts,
        "room_hash": room[0] if room else "N/A",
        "size": size,
        "sample_genres": [g[0] for g in sample_genres],
    }


# ── Main ──────────────────────────────────────────────────────────

def main():
    t0 = time.time()
    print("=" * 60)
    print("  Voxli Seed DB Generator v5 — CATALOG + GENRES")
    print("=" * 60)

    # ── Step 1: Download files ──
    print("\n─── Downloading data ───")
    catalog_path = CATALOG_PATH
    if len(sys.argv) > 1:
        arg = sys.argv[1]
        if arg.startswith("--catalog="):
            catalog_path = arg.split("=", 1)[1]
        elif not arg.startswith("--"):
            catalog_path = arg
        if not os.path.exists(catalog_path):
            print(f"  ERROR: catalog file not found: {catalog_path}")
            sys.exit(1)
        print(f"  Using local catalog: {catalog_path}")
    else:
        catalog_path = download(CATALOG_URL, CATALOG_PATH, "catalog.zip")

    download(GENRELIST_URL, GENRELIST_PATH, "genre list")
    download(GENRE_URL, GENRE_PATH, "book-genre mapping")

    # ── Step 2: Load genres ──
    print("\n─── Loading genre data ───")
    t1 = time.time()
    genre_map = load_genre_list(GENRELIST_PATH)
    book_genres = load_book_genres(GENRE_PATH)
    print(f"  Genre data loaded in {time.time() - t1:.0f}s")

    # ── Step 3: Parse catalog ──
    print("\n─── Parsing catalog ───")
    t2 = time.time()
    books = parse_catalog(catalog_path)
    print(f"  Parse time: {time.time() - t2:.0f}s for {len(books):,} books")

    if len(books) == 0:
        print("  ERROR: No books parsed! Aborting.")
        sys.exit(1)

    # ── Step 4: Apply genres ──
    print("\n─── Applying genres ───")
    t3 = time.time()
    applied = apply_genres(books, genre_map, book_genres)
    print(f"  Genres applied in {time.time() - t3:.0f}s")

    # ── Step 5: Create DB ──
    print("\n─── Creating SQLite database ───")
    t4 = time.time()
    inserted = create_db(books)
    t_db = time.time() - t4
    print(f"  DB creation: {t_db:.0f}s")

    # ── Step 6: Verify + Regression check ──
    print("\n─── Verification & regression checks ───")
    stats = verify_db()

    total = time.time() - t0
    print(f"\n  {'=' * 56}")
    print(f"  ✅  DATABASE CREATED SUCCESSFULLY")
    print(f"  {'=' * 56}")
    print(f"     Books:      {stats['count']:,}")
    print(f"     Authors:    {stats['authors']:,}")
    print(f"     Genres:     {stats['genres']:,}")
    print(f"     FTS rows:   {stats['fts']:,}")
    print(f"     Room hash:  {stats['room_hash']}")
    print(f"     Size:       {stats['size']:,} bytes ({stats['size']/1024/1024:.1f} MB)")
    print(f"  {'─' * 56}")
    print(f"     Sample genres: {stats['sample_genres'][:6]}")
    print(f"  {'─' * 56}")
    print(f"     Total time: {total:.0f}s")
    print(f"  {'=' * 56}")


if __name__ == "__main__":
    try:
        main()
    except RegressionError:
        print(f"\n  ❌  ABORTED due to regression. DB not usable.")
        # Remove the invalid DB so Room doesn't pick it up
        if os.path.exists(OUTPUT):
            os.remove(OUTPUT)
            print(f"     Removed invalid {OUTPUT}")
        sys.exit(1)
    except Exception as e:
        print(f"\n  ❌  ERROR: {e}")
        sys.exit(1)
