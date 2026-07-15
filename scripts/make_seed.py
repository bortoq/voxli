#!/usr/bin/env python3
"""Fast seed DB generator — fetches new books from flibusta OPDS."""

import sqlite3, urllib.request, re, xml.etree.ElementTree as ET, sys, os

NS = {"atom": "http://www.w3.org/2005/Atom", "dc": "http://purl.org/dc/terms/"}

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "databases", "voxli_seed.db")

def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    conn = sqlite3.connect(OUT)
    conn.execute("PRAGMA journal_mode=DELETE")  # simpler, no WAL
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
        CREATE INDEX IF NOT EXISTS index_books_title ON books(title COLLATE NOCASE);
        CREATE INDEX IF NOT EXISTS index_books_author ON books(author COLLATE NOCASE);
        CREATE INDEX IF NOT EXISTS index_books_genre ON books(genre);
        CREATE INDEX IF NOT EXISTS index_books_rating ON books(rating);
        CREATE INDEX IF NOT EXISTS index_books_has_audio ON books(has_audio);
        PRAGMA user_version = 1;
    """)
    conn.commit()

    # Create room_master_table with the expected identity hash so Room trusts this DB
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS room_master_table (
            id INTEGER PRIMARY KEY,
            identity_hash TEXT
        );
        INSERT OR REPLACE INTO room_master_table (id, identity_hash)
        VALUES (42, '92184ce86527b2f270052e1d2873ad57');
    """)
    conn.commit()
    print("Schema created", file=sys.stderr)

    # Fetch new books
    seen = set()
    books = []
    base = "http://flibusta.is"
    next_url = "/opds/new/0/new/"

    for page in range(100):
        if not next_url:
            break
        url = base + next_url
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        try:
            resp = urllib.request.urlopen(req, timeout=10)
            xml = resp.read().decode("utf-8")
        except Exception as e:
            print(f"Page {page}: {e}", file=sys.stderr)
            break

        root = ET.fromstring(xml)
        nl = root.find(".//atom:link[@rel='next']", NS)
        next_url = nl.get("href") if nl is not None else None

        for entry in root.findall(".//atom:entry", NS):
            acq = [l for l in entry.findall("atom:link", NS)
                   if l.get("rel", "").startswith("http://opds-spec.org/acquisition")]
            if not acq:
                continue
            href = acq[0].get("href", "")
            m = re.match(r"/b/(\d+)/", href)
            if not m:
                continue
            bid = int(m.group(1))
            if bid in seen:
                continue
            seen.add(bid)

            title = entry.findtext("atom:title", "", NS)
            ae = entry.find("atom:author", NS)
            author = ""
            if ae is not None:
                an = ae.find("atom:name", NS)
                if an is not None and an.text:
                    author = an.text.strip()
            cat = entry.find("atom:category", NS)
            genre = cat.get("term", "") if cat is not None else ""
            le = entry.find("dc:language", NS)
            lang = le.text.strip() if le is not None and le.text else ""
            has_fb2 = 0
            has_epub = 0
            for link in entry.findall("atom:link", NS):
                t = link.get("type", "")
                if "fb2" in t:
                    has_fb2 = 1
                if "epub" in t:
                    has_epub = 1

            books.append((bid, title.strip(), author, genre, lang, has_fb2, has_epub))

        print(f"  Page {page}: {len(books)} books", file=sys.stderr)
        if len(books) >= 500:
            break

    cur = conn.cursor()
    cur.executemany(
        "INSERT OR REPLACE INTO books(id,title,author,genre,lang,has_fb2,has_epub) VALUES(?,?,?,?,?,?,?)",
        books,
    )
    conn.commit()
    conn.execute("INSERT INTO books_fts(books_fts) VALUES ('rebuild')")
    conn.commit()
    conn.execute("VACUUM")
    conn.close()
    print(f"Done: {len(books)} books", file=sys.stderr)

if __name__ == "__main__":
    main()
