#!/usr/bin/env python3
"""
Seed DB generator for Voxli.

Generates voxli_seed.db (top-5000 books from flibusta) following Room's schema.

Usage:
    python3 generate_seed_db.py [--output path/to/voxli_seed.db]

The schema JSON (from Room KSP generation) must be at:
    app/build/generated/ksp/debug/resources/schemas/com.voxli.catalog.db.VoxliDatabase/1.json

If schema file is not found, a minimal fallback schema is used.

Reference: roadmap §14.3
"""

import argparse
import json
import os
import sqlite3
import sys
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Optional

# Default paths
SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_OUTPUT = SCRIPT_DIR / "app" / "src" / "main" / "assets" / "databases" / "voxli_seed.db"
SCHEMA_PATH = Path("app/build/generated/ksp/debug/resources/schemas/com.voxli.catalog.db.VoxliDatabase/1.json")

FLIBUSTA_MIRRORS = [
    "http://flibusta.is",
    "http://flibusta.site",
    "http://flibusta.net",
]

GENRES = [
    "prose", "detective", "fantasy", "sci-fi", "horror", "adventure",
    "romance", "thriller", "poetry", "drama", "humor", "child",
    "history", "religion", "philosophy", "psychology", "technical",
    "reference", "nonfiction", "biography", "military", "classic",
]

# ---- Schema ----

def load_schema(schema_path: Path) -> dict:
    """Load Room schema JSON."""
    if schema_path.exists():
        with open(schema_path) as f:
            return json.load(f)
    print(f"[WARN] Schema not found at {schema_path}, using fallback", file=sys.stderr)
    return {}

def get_create_table_ddl(schema: dict, table_name: str) -> Optional[str]:
    """Extract CREATE TABLE DDL for a given table from Room schema."""
    for entity in schema.get("entities", []):
        if entity.get("tableName") == table_name or (
            entity.get("createSql", "").upper().startswith("CREATE TABLE")
            and table_name in entity.get("createSql", "")
        ):
            return entity.get("createSql")
    return None

def get_create_index_ddl(schema: dict, table_name: str) -> list[str]:
    """Extract CREATE INDEX DDL for a given table."""
    indexes = []
    for entity in schema.get("entities", []):
        if entity.get("tableName") != table_name:
            continue
        for idx in entity.get("indices", []):
            idx_sql = idx.get("createSql")
            if idx_sql:
                indexes.append(idx_sql)
    return indexes

# ---- SQL ----

CREATE_BOOKS_SQL = """
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
)
"""

CREATE_HISTORY_SQL = """
CREATE TABLE IF NOT EXISTS history (
    book_id INTEGER NOT NULL PRIMARY KEY,
    status TEXT NOT NULL DEFAULT 'reading',
    char_offset INTEGER NOT NULL DEFAULT 0,
    progress REAL NOT NULL DEFAULT 0.0,
    playback_pos INTEGER NOT NULL DEFAULT 0,
    started_at TEXT NOT NULL DEFAULT '',
    finished_at TEXT DEFAULT NULL,
    updated_at TEXT NOT NULL DEFAULT ''
)
"""

CREATE_SETTINGS_SQL = """
CREATE TABLE IF NOT EXISTS settings (
    key TEXT NOT NULL PRIMARY KEY,
    value TEXT NOT NULL DEFAULT ''
)
"""

CREATE_FTS5_SQL = """
CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(
    title, author,
    content=books,
    content_rowid=id,
    tokenize='unicode61 remove_diacritics 1'
)
"""

CREATE_FTS_TRIGGERS = [
    """
    CREATE TRIGGER IF NOT EXISTS books_ai AFTER INSERT ON books BEGIN
        INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author);
    END
    """,
    """
    CREATE TRIGGER IF NOT EXISTS books_ad AFTER DELETE ON books BEGIN
        INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author);
    END
    """,
    """
    CREATE TRIGGER IF NOT EXISTS books_au AFTER UPDATE ON books BEGIN
        INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author);
        INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author);
    END
    """,
]

CREATE_INDEXES = [
    "CREATE INDEX IF NOT EXISTS index_books_title ON books(title COLLATE NOCASE)",
    "CREATE INDEX IF NOT EXISTS index_books_author ON books(author COLLATE NOCASE)",
    "CREATE INDEX IF NOT EXISTS index_books_genre ON books(genre)",
    "CREATE INDEX IF NOT EXISTS index_books_rating ON books(rating)",
    "CREATE INDEX IF NOT EXISTS index_books_has_audio ON books(has_audio)",
]

def init_db(conn: sqlite3.Connection, schema: dict):
    """Initialize the database schema."""
    conn.executescript(CREATE_BOOKS_SQL)
    conn.executescript(CREATE_HISTORY_SQL)
    conn.executescript(CREATE_SETTINGS_SQL)
    conn.executescript(CREATE_FTS5_SQL)
    for trigger in CREATE_FTS_TRIGGERS:
        conn.executescript(trigger)
    for idx in CREATE_INDEXES:
        conn.executescript(idx)
    conn.commit()

# ---- Flibusta OPDS fetch ----

def fetch_opds(url: str) -> Optional[str]:
    """Fetch an OPDS URL."""
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
            "Accept": "application/atom+xml,application/xml,text/html,*/*",
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.read().decode("utf-8")
    except Exception as e:
        print(f"[WARN] Failed to fetch {url}: {e}", file=sys.stderr)
        return None

NS = {
    "atom": "http://www.w3.org/2005/Atom",
    "opds": "http://opds-spec.org/2010/catalog",
}

def parse_opds_entry(entry: ET.Element) -> Optional[dict]:
    """Parse an OPDS entry into a book dict."""
    id_text = entry.findtext("atom:id", "", NS)
    if not id_text or "flibusta:book:" not in id_text:
        return None
    try:
        book_id = int(id_text.split("flibusta:book:")[1])
    except (ValueError, IndexError):
        return None

    title = entry.findtext("atom:title", "", NS)
    author_el = entry.find("atom:author", NS)
    author = author_el.findtext("atom:name", "") if author_el is not None else ""

    category = entry.find("atom:category", NS)
    genre = category.get("term", "") if category is not None else "unknown"

    content = entry.findtext("atom:content", "", NS)
    published = entry.findtext("atom:published", "", NS)

    has_fb2 = False
    has_epub = False
    for link in entry.findall("atom:link", NS):
        link_type = link.get("type", "")
        if "fb2" in link_type:
            has_fb2 = True
        if "epub" in link_type:
            has_epub = True

    return {
        "id": book_id,
        "title": title.strip(),
        "author": author.strip(),
        "genre": genre,
        "annotation": content.strip(),
        "has_fb2": 1 if has_fb2 else 0,
        "has_epub": 1 if has_epub else 0,
        "created_at": published,
        "series": "",
        "series_num": 0,
        "lang": "",
        "rating": 0.0,
        "votes_count": 0,
        "has_audio": 0,
    }

def fetch_genre_books(genre: str, base_url: str, limit: int = 500) -> list[dict]:
    """Fetch top books for a genre from OPDS."""
    books = []
    offset = 0
    page_size = 100

    while len(books) < limit:
        url = f"{base_url}/opds/genre/{genre}/?offset={offset}&limit={page_size}"
        xml = fetch_opds(url)
        if not xml:
            break

        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            break

        entries = root.findall(".//atom:entry", NS)
        if not entries:
            break

        for entry in entries:
            result = parse_opds_entry(entry)
            if result:
                result["genre"] = genre
                books.append(result)

        offset += page_size
        if len(entries) < page_size:
            break

        print(f"  {genre}: {len(books)} books...", file=sys.stderr)

    return books[:limit]

def fetch_new_books(base_url: str, max_books: int = 5000) -> list[dict]:
    """Fetch newest books across all categories."""
    books = []
    seen_ids = set()
    offset = 0
    page_size = 100

    while len(books) < max_books:
        url = f"{base_url}/opds/new/0/new?offset={offset}"
        xml = fetch_opds(url)
        if not xml:
            break

        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            break

        entries = root.findall(".//atom:entry", NS)
        if not entries:
            break

        for entry in entries:
            result = parse_opds_entry(entry)
            if result and result["id"] not in seen_ids:
                seen_ids.add(result["id"])
                books.append(result)

        offset += page_size
        if len(entries) < page_size:
            break

        print(f"  New: {len(books)} books...", file=sys.stderr)

    return books[:max_books]

# ---- Main ----

def build_seed_db(output_path: Path, max_books: int = 5000):
    """Main seed DB generation."""
    print(f"Generating seed DB: {output_path}", file=sys.stderr)
    print(f"Max books: {max_books}", file=sys.stderr)

    # Find working mirror
    base_url = None
    for mirror in FLIBUSTA_MIRRORS:
        if fetch_opds(mirror + "/opds/"):
            base_url = mirror
            print(f"Using mirror: {base_url}", file=sys.stderr)
            break

    if not base_url:
        print("[ERROR] No flibusta mirror available", file=sys.stderr)
        return 1

    # Remove old DB
    if output_path.exists():
        output_path.unlink()

    # Connect
    conn = sqlite3.connect(str(output_path))
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=OFF")

    # Create schema
    schema = load_schema(SCHEMA_PATH)
    init_db(conn, schema)
    print("Schema created", file=sys.stderr)

    # Fetch books from each genre
    all_books = []
    seen_ids = set()

    for genre in GENRES:
        genre_books = fetch_genre_books(genre, base_url, limit=max_books // len(GENRES) // 2)
        for book in genre_books:
            if book["id"] not in seen_ids:
                seen_ids.add(book["id"])
                all_books.append(book)
        print(f"  {genre}: {len(genre_books)} unique books", file=sys.stderr)

    # Also fetch new books for coverage
    new_books = fetch_new_books(base_url, max_books // 2)
    for book in new_books:
        if book["id"] not in seen_ids:
            seen_ids.add(book["id"])
            all_books.append(book)

    print(f"Total unique books: {len(all_books)}", file=sys.stderr)

    # Sort by id, keep top max_books
    all_books.sort(key=lambda b: b["rating"], reverse=True)
    all_books = all_books[:max_books]

    # Insert into DB
    cursor = conn.cursor()
    insert_sql = """
        INSERT OR REPLACE INTO books
            (id, title, author, annotation, genre, series, series_num, lang,
             rating, votes_count, has_fb2, has_epub, has_audio, created_at)
        VALUES
            (:id, :title, :author, :annotation, :genre, :series, :series_num, :lang,
             :rating, :votes_count, :has_fb2, :has_epub, :has_audio, :created_at)
    """

    for book in all_books:
        cursor.execute(insert_sql, book)
    conn.commit()

    print(f"Inserted {len(all_books)} books", file=sys.stderr)

    # FTS5 rebuild
    conn.execute("INSERT INTO books_fts(books_fts) VALUES ('rebuild')")

    # Vacuum
    conn.execute("VACUUM")
    conn.close()

    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"Done: {output_path} ({size_mb:.1f} MB)", file=sys.stderr)
    return 0

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate Voxli seed database")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT,
                       help="Output path for voxli_seed.db")
    parser.add_argument("--max-books", type=int, default=5000,
                       help="Maximum number of books (default: 5000)")
    args = parser.parse_args()

    # Create output directory
    args.output.parent.mkdir(parents=True, exist_ok=True)

    sys.exit(build_seed_db(args.output, args.max_books))
