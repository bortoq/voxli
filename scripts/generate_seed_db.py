#!/usr/bin/env python3
"""
⚠️  DEPRECATED — use create_seed_from_catalog.py instead.

This old script does multi-phase collection from Flibusta OPDS (5,000+ HTTP requests).
The new script downloads catalog.zip in ONE request (~60 MB) and parses the complete
catalog (~1.2M books) directly. See create_seed_from_catalog.py.

Generate voxli_seed.db — a Room-compatible SQLite seed database for Voxli.

Multi-phase collection from Flibusta OPDS:
  Phase 1 — Author index: crawl ALL author pages (5,000+ pages, ~90,000 authors)
  Phase 2 — New books feed: follow pagination (752+ books)
  Phase 3 — Search queries: cover diverse genres (~2,000+ books)
  Phase 4 — Author catalogs: fetch ALL books from as many authors as possible

Author IDs are cached in a JSON checkpoint so re-runs can skip Phase 1.
"""

import sqlite3
import json
import os
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from urllib.parse import quote
from html.parser import HTMLParser

# ── Config ────────────────────────────────────────────────────────
MIRRORS = ["http://flibusta.is", "http://flibusta.site", "http://flibusta.net"]
OUTPUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app/src/main/assets/databases/voxli_seed.db",
)
CHECKPOINT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "scripts/.seed_checkpoint.json",
)

NEW_BOOKS_BASE = "/opds/new/{page}/new/"
AUTHOR_INDEX_BASE = "/opds/authors"
AUTHOR_BOOKS = "/opds/authorsequenceless/{author_id}"
SEARCH_BOOKS = "/opds/search?searchType=books&searchTerm={query}"

MAX_AUTHOR_FEEDS = 5000   # how many author catalogs to fetch
MAX_NEW_BOOK_PAGES = 200  # safety limit
SEARCH_DELAY = 0.3
FAST_DELAY = 0.15

SEARCH_QUERIES = [
    "классика", "детектив", "триллер", "фантастика", "фэнтези",
    "мистика", "ужасы", "боевик", "приключения", "вестерн",
    "антиутопия", "киберпанк", "роман", "повесть", "рассказы",
    "поэзия", "драма", "публицистика", "критика", "сказки",
    "юмор", "комиксы", "стихи", "пьесы", "эссе",
    "письма", "дневники", "очерки", "басни", "былины", "летописи",
    "история", "биография", "мемуары", "психология", "философия",
    "наука", "техника", "медицина", "право", "экономика",
    "педагогика", "социология", "политология", "культурология",
    "искусство", "музыка", "кино", "театр", "архитектура",
    "живопись", "лингвистика", "фольклор", "религия", "эзотерика",
    "кулинария", "спорт", "путешествия", "сад", "здоровье",
    "домоводство", "маркетинг", "менеджмент", "бизнес", "финансы",
    "карьера", "саморазвитие", "мотивация", "лидерство",
    "программирование", "математика", "физика", "химия", "биология",
    "инженерия", "искусственный интеллект", "антропология",
    "археология", "мифология", "этнография", "география",
    "экология", "дизайн", "фотография", "кинематограф",
    "Толстой", "Достоевский", "Чехов", "Пушкин", "Гоголь", "Тургенев",
    "Шекспир", "Дюма", "Верн", "Лондон", "Хемингуэй", "Оруэлл",
    "Брэдбери", "Азимов", "Кинг", "Стругацкие", "Лем", "Пелевин",
    "Уэллс", "Дойл", "Кристи", "Роулинг", "Толкиен",
    "война и мир", "преступление и наказание", "мастер и маргарита",
    "гарри поттер", "властелин колец", "дюна", "ведьмак",
    "игра престолов", "1984", "скотный двор",
]


# ── HTML / helpers ────────────────────────────────────────────────
class _MLStripper(HTMLParser):
    def __init__(self):
        super().__init__()
        self.reset()
        self.convert_charrefs = True
        self.text = []
    def handle_data(self, d):
        self.text.append(d)
    def get_data(self):
        return "".join(self.text)

_STRIPPER = _MLStripper()

def strip_html(html: str) -> str:
    _STRIPPER.text = []
    _STRIPPER.feed(html)
    return _STRIPPER.get_data().strip()


# ── HTTP ──────────────────────────────────────────────────────────
def fetch(url: str, retries: int = 3, timeout: int = 15) -> str | None:
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": "Voxli/0.1 (seed-generator)",
                "Accept": "application/atom+xml, text/html, */*",
            })
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.read().decode("utf-8", errors="replace")
        except Exception:
            if attempt < retries - 1:
                time.sleep(1)
            continue
    return None


def pick_mirror() -> str:
    for mirror in MIRRORS:
        try:
            req = urllib.request.Request(f"{mirror}/", method="HEAD")
            with urllib.request.urlopen(req, timeout=5):
                return mirror
        except Exception:
            continue
    return MIRRORS[0]


# ── OPDS parsing ──────────────────────────────────────────────────
ATOM_NS = "http://www.w3.org/2005/Atom"
DC_NS = "http://purl.org/dc/terms/"
NS = {"atom": ATOM_NS, "dc": DC_NS}


def parse_opds_entry(entry: ET.Element) -> dict | None:
    """Parse a single OPDS <entry> into a book dict."""
    book_id = None
    formats: set[str] = set()

    for link in entry.findall("atom:link", NS):
        href = link.get("href", "")
        link_type = link.get("type", "")
        m = re.match(r"/b/(\d+)(?:/|$)", href)
        if m:
            bid = int(m.group(1))
            if book_id is None:
                book_id = bid
            if "fb2" in link_type:
                formats.add("fb2")
            if "epub" in link_type:
                formats.add("epub")

    if book_id is None:
        return None

    dc_format = entry.find("dc:format", NS)
    if dc_format is not None and dc_format.text:
        fmt = dc_format.text.strip().lower()
        if "fb2" in fmt:
            formats.add("fb2")
        if "epub" in fmt:
            formats.add("epub")

    title_el = entry.find("atom:title", NS)
    title = title_el.text.strip() if title_el is not None and title_el.text else ""
    if not title:
        return None

    author_el = entry.find("atom:author/atom:name", NS)
    author = author_el.text.strip() if author_el is not None and author_el.text else ""

    cat = entry.find("atom:category", NS)
    genre = ""
    if cat is not None:
        genre = cat.get("label", "") or cat.get("term", "")

    content_el = entry.find("atom:content", NS)
    annotation = ""
    if content_el is not None and content_el.text:
        raw = content_el.text.strip()
        ct = content_el.get("type", "text")
        if ct == "text/html":
            raw = strip_html(raw)
        annotation = re.sub(
            r"(?:Год издания|Формат|Язык|Размер|Скачиваний|Перевод).*?(?:<br/?>|\n|$)",
            "", raw, flags=re.IGNORECASE
        ).strip()

    published_el = entry.find("atom:published", NS)
    published = published_el.text.strip() if published_el is not None and published_el.text else ""
    updated_el = entry.find("atom:updated", NS)
    updated = updated_el.text.strip() if updated_el is not None and updated_el.text else ""

    lang_el = entry.find("dc:language", NS)
    lang = lang_el.text.strip() if lang_el is not None and lang_el.text else "ru"

    return {
        "id": book_id,
        "title": title,
        "author": author,
        "annotation": annotation,
        "genre": genre,
        "series": "",
        "series_num": 0,
        "lang": lang if lang else "ru",
        "rating": 0.0,
        "votes_count": 0,
        "has_fb2": 1 if "fb2" in formats else 0,
        "has_epub": 1 if "epub" in formats else 0,
        "has_audio": 0,
        "created_at": published or updated,
    }


def parse_opds_feed(xml: str) -> list[dict]:
    books = []
    try:
        root = ET.fromstring(xml)
        for entry in root.findall("atom:entry", NS):
            book = parse_opds_entry(entry)
            if book:
                books.append(book)
    except ET.ParseError:
        pass
    return books


# ── Author ID collection ──────────────────────────────────────────
def collect_all_author_ids(mirror: str) -> list[int]:
    """Crawl ALL author index pages and return all author IDs."""
    checkpoint_path = CHECKPOINT
    author_ids: list[int] = []

    # Resume from checkpoint if exists
    if os.path.exists(checkpoint_path):
        with open(checkpoint_path) as f:
            data = json.load(f)
        author_ids = data.get("author_ids", [])
        last_page = data.get("last_page", 0)
        last_url = data.get("last_url", "")
        print(f"  Resuming from page {last_page} ({len(author_ids)} authors collected)")
    else:
        last_page = 0
        last_url = f"{mirror}{AUTHOR_INDEX_BASE}"
        author_ids = []

    page = last_page
    url = last_url
    consecutive_empty = 0

    while url and page < 20000 and consecutive_empty < 5:
        xml = fetch(url, timeout=10)
        if not xml:
            print(f"  Page {page}: fetch failed, retrying")
            consecutive_empty += 1
            time.sleep(1)
            continue

        try:
            root = ET.fromstring(xml)
        except ET.ParseError as e:
            print(f"  Page {page}: XML error {e}, stopping")
            break

        entries = root.findall("atom:entry", NS)

        # Extract author IDs from tag:author:NNNNN id fields
        new_ids = 0
        for e in entries:
            id_el = e.find("atom:id", NS)
            if id_el is not None and id_el.text:
                m = re.search(r"tag:author:(\d+)", id_el.text)
                if m:
                    aid = int(m.group(1))
                    if aid not in author_ids:
                        author_ids.append(aid)
                        new_ids += 1

        # Find next link
        next_url = None
        for link in root.findall("atom:link", NS):
            if link.get("rel") == "next":
                next_url = link.get("href")
                if next_url and not next_url.startswith("http"):
                    next_url = f"{mirror}{next_url}"
                break

        if page % 100 == 0:
            print(f"  Page {page}: {len(entries)} entries, +{new_ids} new, "
                  f"total={len(author_ids)}, next={next_url and next_url[-20:]}")
            # Save checkpoint every 100 pages
            os.makedirs(os.path.dirname(checkpoint_path), exist_ok=True)
            with open(checkpoint_path, "w") as f:
                json.dump({"author_ids": author_ids, "last_page": page, "last_url": url}, f)

        if not entries:
            consecutive_empty += 1
        else:
            consecutive_empty = 0

        url = next_url
        page += 1
        if not next_url:
            print(f"  No next link on page {page-1}, end of index")
            break
        time.sleep(FAST_DELAY)

    # Save final checkpoint
    os.makedirs(os.path.dirname(checkpoint_path), exist_ok=True)
    with open(checkpoint_path, "w") as f:
        json.dump({"author_ids": author_ids, "last_page": page, "last_url": url or ""}, f)

    print(f"\n  Author index done: {len(author_ids)} unique authors, {page} pages crawled")
    return author_ids


# ── Book collection ───────────────────────────────────────────────
def merge_books(books: list[dict], seen: set[int], sink: list[dict]):
    for b in books:
        if b["id"] not in seen:
            seen.add(b["id"])
            if b.get("has_fb2") or b.get("has_epub"):
                sink.append(b)
            else:
                pass  # skip format-less


def fetch_feed(url: str, label: str, seen: set[int], sink: list[dict],
               delay: float = SEARCH_DELAY) -> int:
    xml = fetch(url)
    if not xml:
        return 0
    books = parse_opds_feed(xml)
    before = len(sink)
    merge_books(books, seen, sink)
    new_count = len(sink) - before
    if new_count > 0:
        print(f"  {label}: +{new_count} books", flush=True)
    return new_count


def create_db(books: list[dict]) -> int:
    """Create the SQLite database. Returns book count."""
    if os.path.exists(OUTPUT):
        os.remove(OUTPUT)
    conn = sqlite3.connect(OUTPUT)
    conn.execute("PRAGMA journal_mode=OFF")
    conn.execute("PRAGMA synchronous=OFF")

    conn.execute("""CREATE TABLE IF NOT EXISTS books (
        id INTEGER NOT NULL PRIMARY KEY,
        title TEXT NOT NULL DEFAULT '', author TEXT NOT NULL DEFAULT '',
        annotation TEXT NOT NULL DEFAULT '', genre TEXT NOT NULL DEFAULT '',
        series TEXT NOT NULL DEFAULT '', series_num INTEGER NOT NULL DEFAULT 0,
        lang TEXT NOT NULL DEFAULT '', rating REAL NOT NULL DEFAULT 0.0,
        votes_count INTEGER NOT NULL DEFAULT 0,
        has_fb2 INTEGER NOT NULL DEFAULT 0, has_epub INTEGER NOT NULL DEFAULT 0,
        has_audio INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL DEFAULT ''
    )""")
    conn.execute("""CREATE TABLE IF NOT EXISTS history (
        book_id INTEGER NOT NULL PRIMARY KEY, status TEXT NOT NULL DEFAULT 'reading',
        char_offset INTEGER NOT NULL DEFAULT 0, progress REAL NOT NULL DEFAULT 0.0,
        playback_pos INTEGER NOT NULL DEFAULT 0, started_at TEXT NOT NULL DEFAULT '',
        finished_at TEXT DEFAULT NULL, updated_at TEXT NOT NULL DEFAULT ''
    )""")
    conn.execute("""CREATE TABLE IF NOT EXISTS settings (
        key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL DEFAULT ''
    )""")
    conn.execute("""CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(
        title, author, content=books, content_rowid=id,
        tokenize='unicode61 remove_diacritics 1'
    )""")

    for idx in [
        "CREATE INDEX IF NOT EXISTS idx_books_author ON books(author COLLATE NOCASE)",
        "CREATE INDEX IF NOT EXISTS idx_books_genre ON books(genre)",
        "CREATE INDEX IF NOT EXISTS idx_books_rating ON books(rating)",
        "CREATE INDEX IF NOT EXISTS idx_books_title ON books(title COLLATE NOCASE)",
    ]:
        conn.execute(idx)

    for trig in [
        "CREATE TRIGGER IF NOT EXISTS books_ai AFTER INSERT ON books BEGIN "
        "INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author); END",
        "CREATE TRIGGER IF NOT EXISTS books_ad AFTER DELETE ON books BEGIN "
        "INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author); END",
        "CREATE TRIGGER IF NOT EXISTS books_au AFTER UPDATE ON books BEGIN "
        "INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author); "
        "INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author); END",
    ]:
        conn.execute(trig)

    inserted = 0
    for book in books:
        try:
            conn.execute(
                """INSERT INTO books (id, title, author, annotation, genre, series, series_num,
                   lang, rating, votes_count, has_fb2, has_epub, has_audio, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (book["id"], book["title"], book["author"], book["annotation"],
                 book["genre"], book["series"], book["series_num"],
                 book["lang"], book["rating"], book["votes_count"],
                 book["has_fb2"], book["has_epub"], book["has_audio"],
                 book["created_at"]),
            )
            inserted += 1
        except sqlite3.IntegrityError:
            pass
    conn.commit()
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.execute("PRAGMA user_version = 1")
    conn.commit()
    conn.close()
    return inserted


# ── Main ──────────────────────────────────────────────────────────
def main():
    t0 = time.time()
    print("=" * 60)
    print("  Voxli Seed DB Generator v3 — ALL BOOKS")
    print("=" * 60)

    mirror = pick_mirror()
    print(f"  Mirror: {mirror}")
    seen: set[int] = set()
    all_books: list[dict] = []

    # ── Phase 1: Collect all author IDs ──
    print("\n─── Phase 1: Author index crawl ───")
    t1 = time.time()
    author_ids = collect_all_author_ids(mirror)
    t_phase1 = time.time() - t1
    print(f"  Time: {t_phase1:.0f}s")

    # ── Phase 2: New books feed ──
    print("\n─── Phase 2: New books feed ───")
    t2 = time.time()
    for page in range(1, MAX_NEW_BOOK_PAGES + 1):
        url = f"{mirror}{NEW_BOOKS_BASE.format(page=page)}"
        xml = fetch(url, timeout=10)
        if not xml:
            break
        books = parse_opds_feed(xml)
        if not books:
            break
        merge_books(books, seen, all_books)
        if page % 10 == 0:
            print(f"  page {page}: {len(all_books)} total books", flush=True)
        time.sleep(SEARCH_DELAY)
    t_phase2 = time.time() - t2
    print(f"  New books done: {len(all_books)} books ({t_phase2:.0f}s)")

    # ── Phase 3: Search queries ──
    print(f"\n─── Phase 3: Search ({len(SEARCH_QUERIES)} queries) ───")
    t3 = time.time()
    for i, query in enumerate(SEARCH_QUERIES):
        url = f"{mirror}{SEARCH_BOOKS.format(query=quote(query))}"
        label = f"search[{i+1}/{len(SEARCH_QUERIES)}]"
        fetch_feed(url, label, seen, all_books, delay=0)
        time.sleep(SEARCH_DELAY)
    t_phase3 = time.time() - t3
    print(f"  Search done: {len(all_books)} books ({t_phase3:.0f}s)")

    # ── Phase 4: Author catalogs ──
    n_authors = min(len(author_ids), MAX_AUTHOR_FEEDS)
    print(f"\n─── Phase 4: Author catalogs ({n_authors} of {len(author_ids)} authors) ───")
    t4 = time.time()
    crawled = 0
    for aid in author_ids:
        if crawled >= MAX_AUTHOR_FEEDS:
            print(f"  Reached {MAX_AUTHOR_FEEDS} author limit")
            break
        url = f"{mirror}{AUTHOR_BOOKS.format(author_id=aid)}"
        label = f"author {aid}"
        n = fetch_feed(url, label, seen, all_books, delay=0)
        crawled += 1
        if crawled % 100 == 0:
            elapsed = time.time() - t4
            rate = crawled / (elapsed / 60)
            print(f"  [{crawled}/{n_authors}] {len(all_books)} books, "
                  f"{elapsed:.0f}s ({rate:.0f} authors/min)", flush=True)
        time.sleep(FAST_DELAY)
    t_phase4 = time.time() - t4
    print(f"  Author catalogs done: {len(all_books)} books ({t_phase4:.0f}s)")

    # ── Create DB ──
    print(f"\n─── Creating database ({len(all_books)} books) ───")
    t5 = time.time()
    inserted = create_db(all_books)
    t_db = time.time() - t5
    total_time = time.time() - t0

    # Verify
    conn = sqlite3.connect(OUTPUT)
    count = conn.execute("SELECT COUNT(*) FROM books").fetchone()[0]
    genres = conn.execute("SELECT COUNT(DISTINCT genre) FROM books WHERE genre != ''").fetchone()[0]
    fb2 = conn.execute("SELECT COUNT(*) FROM books WHERE has_fb2 = 1").fetchone()[0]
    epub = conn.execute("SELECT COUNT(*) FROM books WHERE has_epub = 1").fetchone()[0]
    fts = conn.execute("SELECT COUNT(*) FROM books_fts").fetchone()[0]
    conn.close()

    size = os.path.getsize(OUTPUT)
    print(f"\n  {'=' * 56}")
    print(f"  ✅  DATABASE CREATED")
    print(f"  {'=' * 56}")
    print(f"     Books:        {count}")
    print(f"     Authors:      {len(author_ids)} in index, {n_authors} crawled")
    print(f"     Genres:       {genres}")
    print(f"     Has FB2:      {fb2}")
    print(f"     Has EPUB:     {epub}")
    print(f"     FTS entries:  {fts}")
    print(f"     Size:         {size:,} bytes ({size/1024/1024:.1f} MB)")
    print(f"  {'─' * 56}")
    print(f"     Phase 1 (index): {t_phase1:.0f}s")
    print(f"     Phase 2 (new):   {t_phase2:.0f}s")
    print(f"     Phase 3 (search):{t_phase3:.0f}s")
    print(f"     Phase 4 (authors):{t_phase4:.0f}s")
    print(f"     DB creation:     {t_db:.0f}s")
    print(f"     Total:           {total_time:.0f}s")
    print(f"  {'=' * 56}")


if __name__ == "__main__":
    main()
