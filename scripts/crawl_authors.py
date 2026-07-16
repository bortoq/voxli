#!/usr/bin/env python3
"""
⚠️  DEPRECATED — use create_seed_from_catalog.py instead.

This old script crawls ALL author pages (5,000+ HTTP requests).
The new script downloads catalog.zip in ONE request (~60 MB).

Phase 1: Crawl ALL author IDs from Flibusta OPDS index.

Saves checkpoint to .seed_checkpoint.json every 100 pages.
Checkpoint includes author_ids + last page — can be resumed if interrupted.
"""

import json, os, re, sys, time, urllib.request
import xml.etree.ElementTree as ET

MIRRORS = ["http://flibusta.is", "http://flibusta.site", "http://flibusta.net"]
CHECKPOINT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "scripts/.seed_checkpoint.json",
)
AUTHOR_INDEX = "/opds/authors"
DELAY = 0.2

NS = {"atom": "http://www.w3.org/2005/Atom"}

def fetch(url):
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Voxli/0.1"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                return resp.read().decode("utf-8", errors="replace")
        except Exception:
            if attempt < 2:
                time.sleep(2)
    return None

def pick_mirror():
    for m in MIRRORS:
        try:
            urllib.request.urlopen(f"{m}/", timeout=5)
            return m
        except:
            continue
    return MIRRORS[0]

def collect():
    mirror = pick_mirror()
    print(f"Mirror: {mirror}")

    # Resume from checkpoint
    author_ids = []
    start_page = 0
    start_url = f"{mirror}{AUTHOR_INDEX}"

    if os.path.exists(CHECKPOINT):
        with open(CHECKPOINT) as f:
            data = json.load(f)
        author_ids = data.get("author_ids", [])
        start_page = data.get("last_page", 0)
        start_url = data.get("last_url", start_url)
        print(f"Resuming: page {start_page}, {len(author_ids)} authors")

    page = start_page
    url = start_url
    consecutive_empty = 0
    t0 = time.time()

    while url and page < 20000 and consecutive_empty < 10:
        xml = fetch(url)
        if not xml:
            print(f"  P{page}: fetch fail")
            consecutive_empty += 1
            time.sleep(2)
            continue

        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            print(f"  P{page}: XML error, end of index?")
            break

        entries = root.findall("atom:entry", NS)

        new = 0
        for e in entries:
            id_el = e.find("atom:id", NS)
            if id_el is not None and id_el.text:
                m = re.search(r"tag:author:(\d+)", id_el.text)
                if m:
                    aid = int(m.group(1))
                    if aid not in author_ids:
                        author_ids.append(aid)
                        new += 1

        # Find next page
        next_url = None
        for link in root.findall("atom:link", NS):
            if link.get("rel") == "next":
                href = link.get("href", "")
                if href.startswith("/"):
                    href = f"{mirror}{href}"
                next_url = href
                break

        if page % 100 == 0:
            elapsed = time.time() - t0
            rate = page / (elapsed / 60) if elapsed > 0 else 0
            print(f"  P{page}: +{new} new, total={len(author_ids)}, "
                  f"{elapsed:.0f}s ({rate:.0f} p/min)")
            # Save checkpoint
            os.makedirs(os.path.dirname(CHECKPOINT), exist_ok=True)
            with open(CHECKPOINT, "w") as f:
                json.dump({
                    "author_ids": author_ids,
                    "last_page": page,
                    "last_url": url,
                }, f)

        if not entries:
            consecutive_empty += 1
        else:
            consecutive_empty = 0

        url = next_url
        page += 1
        if not next_url:
            print(f"  No next link at P{page-1} — end of index")
            break
        time.sleep(DELAY)

    # Final save
    os.makedirs(os.path.dirname(CHECKPOINT), exist_ok=True)
    with open(CHECKPOINT, "w") as f:
        json.dump({"author_ids": author_ids, "last_page": page, "last_url": url or ""}, f)

    elapsed = time.time() - t0
    print(f"\n✅ Author index done: {len(author_ids)} authors, {page} pages, {elapsed:.0f}s")
    return author_ids

if __name__ == "__main__":
    collect()
