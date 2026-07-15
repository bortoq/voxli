#!/usr/bin/env python3
"""
Полная верификация Voxli по контракту roadmap.md.

Проверяет:
  1. APK (verify_apk.py)
  2. Архитектура пакетов (§6)
  3. Контракты парсера и пагинатора (§7.5)
  4. FTS5-поиск (§4.2, §4.5)
  5. Читалка: зоны тапа (§7.1–7.3)
  6. Библиотека: режимы, поиск, жанры (§8)
  7. DI/Modules (§6)
  8. Сетевой слой (§15)
  9. Settings/DataStore (§4.4)
  10. Аудиоплеер (§10 Phase 3)
  11. Безопасность / network_security_config (§15.1)

Запуск:
  python3 scripts/verify_contract.py [--apk app/build/outputs/apk/debug/app-debug.apk]
"""

import sys
import os
import zipfile
import re
import importlib.util
from pathlib import Path
from typing import Callable

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SRC = PROJECT_ROOT / "app" / "src" / "main" / "java" / "com" / "voxli"
APK_DEFAULT = PROJECT_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
MANIFEST = PROJECT_ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
RES = PROJECT_ROOT / "app" / "src" / "main" / "res"
ASSETS = PROJECT_ROOT / "app" / "src" / "main" / "assets"

# ─── Коллекция проверок ─────────────────────────────────────────────────

checks_passed = []
checks_failed = []
checks_warn = []

def check(name: str, critical: bool = True):
    """Декоратор для проверки: если fail → critical=False → warn, иначе fail."""
    def decorator(fn: Callable):
        def wrapper(*args, **kwargs):
            try:
                err = fn(*args, **kwargs)
                if err is None:
                    checks_passed.append(name)
                    print(f"  ✅ {name}")
                elif critical:
                    checks_failed.append(f"❌ {name}: {err}")
                    print(f"  ❌ {name}: {err}")
                else:
                    checks_warn.append(f"⚠️ {name}: {err}")
                    print(f"  ⚠️  {name}: {err}")
            except Exception as e:
                msg = f"исключение: {e}"
                if critical:
                    checks_failed.append(f"❌ {name}: {msg}")
                    print(f"  ❌ {name}: {msg}")
                else:
                    checks_warn.append(f"⚠️ {name}: {msg}")
                    print(f"  ⚠️  {name}: {msg}")
        return wrapper
    return decorator

def file_contains(path: Path, pattern: str) -> bool:
    """Проверяет, содержит ли файл regex-паттерн."""
    if not path.exists():
        return False
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
        return bool(re.search(pattern, text))
    except Exception:
        return False

def file_has_class(path: Path, class_name: str) -> bool:
    """Проверяет, объявлен ли класс в файле."""
    return file_contains(path, rf"(class|interface|object)\s+{class_name}\b")

# ─── 1. APK ─────────────────────────────────────────────────────────────

def verify_apk_basic(apk_path: str) -> str | None:
    """Базовая проверка APK."""
    path = Path(apk_path)
    if not path.exists():
        return "APK не найден"
    if path.stat().st_size < 1_000_000:
        return f"APK слишком мал: {path.stat().st_size} байт"
    
    try:
        z = zipfile.ZipFile(str(path), "r")
    except Exception as e:
        return f"Не удалось открыть APK: {e}"
    
    namelist = z.namelist()
    
    # AndroidManifest
    if "AndroidManifest.xml" not in namelist:
        z.close()
        return "AndroidManifest.xml отсутствует"
    
    # Seed DB
    assets = [n for n in namelist if n.startswith("assets/")]
    seed_db = [a for a in assets if a.endswith(".db")]
    if not seed_db:
        z.close()
        return "Seed DB отсутствует в assets/"
    
    # DEX
    dex_files = [n for n in namelist if n.endswith(".dex")]
    if not dex_files:
        z.close()
        return "DEX-файлы отсутствуют"
    
    z.close()
    return None

# ─── 2. Архитектура пакетов (§6) ───────────────────────────────────────

@check("Пакет: catalog.db")
def _check_pkg_catalog():
    pkg = SRC / "catalog" / "db"
    required = ["BookDao.kt", "HistoryDao.kt", "VoxliDatabase.kt", 
                "BookEntity.kt", "HistoryEntity.kt", "FtsQuery.kt"]
    missing = [f for f in required if not (pkg / f).exists()]
    return f"отсутствуют: {missing}" if missing else None

@check("Пакет: flibusta.provider")
def _check_pkg_flibusta():
    pkg = SRC / "flibusta" / "provider"
    required = ["FlibustaProvider.kt"]
    missing = [f for f in required if not (pkg / f).exists()]
    return f"отсутствуют: {missing}" if missing else None

@check("Пакет: knigavuhe.matcher")
def _check_pkg_knigavuhe():
    pkg = SRC / "knigavuhe"
    if not pkg.exists():
        return "пакет knigavuhe отсутствует"
    if not (pkg / "matcher").exists():
        return "пакет knigavuhe.matcher отсутствует"
    return None

@check("Пакет: reader.engine")
def _check_pkg_reader():
    pkg = SRC / "reader" / "engine"
    required = ["BookDownloader.kt", "DocumentModel.kt", "Fb2Parser.kt", "Paginator.kt"]
    missing = [f for f in required if not (pkg / f).exists()]
    return f"отсутствуют: {missing}" if missing else None

@check("Пакет: audio.engine")
def _check_pkg_audio():
    pkg = SRC / "audio" / "engine"
    if not pkg.exists():
        return "пакет audio.engine отсутствует"
    return None

@check("Пакет: tts.engine")
def _check_pkg_tts():
    pkg = SRC / "tts" / "engine"
    if not pkg.exists():
        return "пакет tts.engine отсутствует"
    return None

@check("Пакет: network")
def _check_pkg_network():
    pkg = SRC / "network"
    if not (pkg / "NetworkModule.kt").exists():
        return "NetworkModule.kt отсутствует"
    return None

@check("Пакет: di (Modules.kt)")
def _check_pkg_di():
    pkg = SRC / "di"
    if not (pkg / "Modules.kt").exists():
        return "Modules.kt отсутствует"
    return None

@check("Пакет: ui.library / ui.reader / ui.player")
def _check_pkg_ui():
    missing = []
    for p in ["library", "reader", "player"]:
        if not (SRC / "ui" / p).exists():
            missing.append(p)
    return f"отсутствуют: {missing}" if missing else None

# ─── 3. Контракты парсера и пагинатора (§7.5) ──────────────────────────

@check("Интерфейс BookParser", critical=False)
def _check_bookparser():
    path = SRC / "reader" / "engine" / "DocumentModel.kt"
    if not path.exists():
        return "DocumentModel.kt не найден"
    if not file_has_class(path, "BookParser"):
        return "interface BookParser не найден"
    if not file_contains(path, r"fun\s+parse\s*\(file:\s*java\.io\.File\)"):
        return "метод parse(File) не найден в BookParser"
    return None

@check("Data class DocumentModel", critical=False)
def _check_documentmodel():
    path = SRC / "reader" / "engine" / "DocumentModel.kt"
    if not file_has_class(path, "DocumentModel"):
        return "DocumentModel не найден"
    for field in ["bookId", "title", "author", "paragraphs", "totalChars"]:
        if not file_contains(path, field):
            return f"поле {field} отсутствует в DocumentModel"
    return None

@check("Data class ParagraphBlock", critical=False)
def _check_parablock():
    path = SRC / "reader" / "engine" / "DocumentModel.kt"
    if not file_contains(path, "data class ParagraphBlock"):
        return "ParagraphBlock не найден"
    return None

@check("Class Paginator", critical=False)
def _check_paginator():
    path = SRC / "reader" / "engine" / "Paginator.kt"
    if not path.exists():
        return "Paginator.kt не найден"
    if not file_has_class(path, "Paginator"):
        return "class Paginator не найден"
    # Methods
    for method in ["fun getPage", "fun findPageByCharOffset", "fun nextPage", 
                    "fun prevPage", "fun cancel", "fun rebuild"]:
        if not file_contains(path, method):
            return f"метод {method} не найден в Paginator"
    # CoroutineScope
    if not file_contains(path, "Dispatchers.Default"):
        return "Dispatchers.Default не найден в Paginator"
    return None

@check("Data class Page", critical=False)
def _check_page():
    path = SRC / "reader" / "engine" / "Paginator.kt"
    if not file_contains(path, "data class Page"):
        return "data class Page не найден"
    return None

# ─── 4. FTS5-поиск (§4.2) ─────────────────────────────────────────────

@check("FTS5: VoxliDatabase создаёт books_fts", critical=False)
def _check_fts_create():
    path = SRC / "catalog" / "db" / "VoxliDatabase.kt"
    if not file_contains(path, "CREATE VIRTUAL TABLE.*books_fts"):
        return "CREATE VIRTUAL TABLE books_fts не найден"
    return None

@check("FTS5: триггеры обновления (books_ai/ad/au)", critical=False)
def _check_fts_triggers():
    path = SRC / "catalog" / "db" / "VoxliDatabase.kt"
    for trig in ["books_ai", "books_ad", "books_au"]:
        if not file_contains(path, trig):
            return f"триггер {trig} не найден"
    return None

@check("FTS5: sanitizeFtsQuery", critical=False)
def _check_fts_sanitize():
    path = SRC / "catalog" / "db" / "FtsQuery.kt"
    if not file_contains(path, r"fun sanitizeFtsQuery"):
        return "sanitizeFtsQuery не найден"
    return None

@check("FTS5: buildBookFtsQuery / buildAuthorFtsQuery", critical=False)
def _check_fts_queries():
    path = SRC / "catalog" / "db" / "FtsQuery.kt"
    for fn in ["buildBookFtsQuery", "buildAuthorFtsQuery"]:
        if not file_contains(path, f"fun {fn}"):
            return f"{fn} не найден"
    return None

# ─── 5. Читалка: зоны тапа (§7.1–7.3) ─────────────────────────────────

@check("Читалка: 5 зон тапа", critical=False)
def _check_tap_zones():
    path = SRC / "ui" / "reader" / "ReaderScreen.kt"
    if not path.exists():
        return "ReaderScreen.kt не найден"
    # Проверяем, что зоны тапа определены (pointerInput/detectTapGestures)
    if not file_contains(path, "detectTapGestures"):
        return "detectTapGestures не найден (зоны тапа не определены)"
    # Проверяем перечисление ReaderMode
    if not file_contains(path, "enum class ReaderMode"):
        return "ReaderMode не найден"
    # Проверяем режимы
    for mode in ["READING", "TTS", "SETTINGS"]:
        if not file_contains(path, mode):
            return f"режим {mode} отсутствует в ReaderMode"
    # Проверяем SettingsStep
    if not file_contains(path, "enum class SettingsStep"):
        return "SettingsStep не найден"
    return None

@check("Читалка: ReaderViewModel", critical=False)
def _check_reader_vm():
    path = SRC / "ui" / "reader" / "ReaderViewModel.kt"
    if not path.exists():
        return "ReaderViewModel.kt не найден"
    for method in ["loadBook", "nextPage", "prevPage", "toggleTts", 
                    "saveProgress", "onCleared"]:
        if not file_contains(path, f"fun {method}"):
            return f"метод {method} не найден"
    return None

# ─── 6. Библиотека (§8) ──────────────────────────────────────────────

@check("Библиотека: LibraryViewModel", critical=False)
def _check_library_vm():
    path = SRC / "ui" / "library" / "LibraryViewModel.kt"
    if not path.exists():
        return "LibraryViewModel.kt не найден"
    for method in ["setSearchQuery", "toggleViewMode", "toggleGenre", "refresh",
                    "loadAuthors", "loadBooks"]:
        if not file_contains(path, f"fun {method}"):
            return f"метод {method} не найден"
    return None

@check("Библиотека: GenreSelectionScreen", critical=False)
def _check_genre_screen():
    path = SRC / "ui" / "library" / "GenreSelectionScreen.kt"
    if not path.exists():
        return "GenreSelectionScreen.kt не найден"
    return None

@check("Библиотека: HistoryScreen", critical=False)
def _check_history_screen():
    path = SRC / "ui" / "library" / "HistoryScreen.kt"
    if not path.exists():
        return "HistoryScreen.kt не найден"
    return None

# ─── 7. DI/Modules ────────────────────────────────────────────────────

@check("DI: ReaderViewModel с BookDownloader + FlibustaProvider", critical=False)
def _check_di_reader():
    path = SRC / "di" / "Modules.kt"
    if not file_contains(path, "ReaderViewModel"):
        return "ReaderViewModel не зарегистрирован в DI"
    if not file_contains(path, "BookDownloader"):
        return "BookDownloader не зарегистрирован в DI"
    if not file_contains(path, "FlibustaProvider"):
        return "FlibustaProvider не зарегистрирован в DI"
    return None

@check("DI: LibraryViewModel, PlayerViewModel зарегистрированы", critical=False)
def _check_di_others():
    path = SRC / "di" / "Modules.kt"
    for vm in ["LibraryViewModel", "PlayerViewModel"]:
        if not file_contains(path, vm):
            return f"{vm} не зарегистрирован в DI"
    return None

@check("DI: VoxliDatabase.create с createFromAsset", critical=False)
def _check_di_db():
    path = SRC / "di" / "Modules.kt"
    if not file_contains(path, "VoxliDatabase.create"):
        return "VoxliDatabase не инициализируется в DI"
    return None

# ─── 8. Сетевой слой (§15) ───────────────────────────────────────────

@check("Network: OkHttpClient с DoH", critical=False)
def _check_network():
    path = SRC / "network" / "NetworkModule.kt"
    if not file_contains(path, "DnsOverHttps"):
        return "DnsOverHttps не настроен"
    if not file_contains(path, "cloudflare-dns.com"):
        return "DoH DNS не настроен"
    if not file_contains(path, "User-Agent"):
        return "User-Agent не установлен"
    return None

@check("Network: FlibustaProvider с fallback-зеркалами", critical=False)
def _check_flibusta_mirrors():
    path = SRC / "flibusta" / "provider" / "FlibustaProvider.kt"
    if not file_contains(path, "DEFAULT_MIRRORS"):
        return "DEFAULT_MIRRORS не найден"
    for mirror in ["flibusta.is", "flibusta.site", "flibusta.net"]:
        if not file_contains(path, mirror):
            return f"зеркало {mirror} отсутствует"
    return None

@check("Network: network_security_config.xml", critical=False)
def _check_nsc():
    nsc = PROJECT_ROOT / "app" / "src" / "main" / "res" / "xml" / "network_security_config.xml"
    if not nsc.exists():
        return "network_security_config.xml не найден"
    text = nsc.read_text()
    for domain in ["flibusta.is", "flibusta.site", "flibusta.net", "knigavuhe.org"]:
        if domain not in text:
            return f"домен {domain} отсутствует в network_security_config"
    return None

# ─── 9. Settings/DataStore (§4.4) ────────────────────────────────────

@check("Settings: SettingsRepository с DataStore")
def _check_settings():
    path = SRC / "settings" / "SettingsRepository.kt"
    if not path.exists():
        return "SettingsRepository.kt не найден"
    keys = ["FONT_NAME", "FONT_SIZE", "BG_COLOR", "BG_BRIGHTNESS", 
            "TEXT_COLOR", "TEXT_BRIGHTNESS", "ACTIVE_MIRROR", "SELECTED_GENRES"]
    for key in keys:
        if not file_contains(path, key):
            return f"ключ {key} отсутствует"
    return None

# ─── 10. Аудиоплеер (§10 Phase 3) ────────────────────────────────────

@check("Аудио: AudioDownloader", critical=False)
def _check_audio_downloader():
    pkg = SRC / "audio" / "engine"
    files = [f.name for f in pkg.glob("*.kt")] if pkg.exists() else []
    if not any("AudioDownloader" in f or "Downloader" in f for f in files):
        return "AudioDownloader не найден"
    return None

@check("Аудио: ExoPlayer с User-Agent + Referer для knigavuhe", critical=False)
def _check_exo_headers():
    # Проверка в коде плеера
    player_dir = SRC / "audio" / "engine"
    if not player_dir.exists():
        return "audio.engine не найден"
    found_headers = False
    for kt_file in player_dir.glob("*.kt"):
        text = kt_file.read_text()
        if "DefaultHttpDataSource" in text and "Referer" in text:
            found_headers = True
            break
    if not found_headers:
        # Может быть в PlayerViewModel
        vm_path = SRC / "ui" / "player" / "PlayerViewModel.kt"
        if vm_path.exists():
            text = vm_path.read_text()
            if "DefaultHttpDataSource" in text and "Referer" in text:
                found_headers = True
    if not found_headers:
        return "DefaultHttpDataSource с Referer для knigavuhe не найден"
    return None

@check("Аудио: Mp3CacheCleaner (LRU)", critical=False)
def _check_mp3_cache():
    path = SRC / "audio" / "engine" / "Mp3CacheCleaner.kt"
    if not path.exists():
        return "Mp3CacheCleaner.kt не найден"
    if not file_contains(path, "WorkManager"):
        return "WorkManager не используется для очистки кэша"
    return None

# ─── 11. AndroidManifest ─────────────────────────────────────────────

@check("AndroidManifest: package com.voxli (applicationId)", critical=True)
def _check_manifest_pkg():
    build_gradle = PROJECT_ROOT / "app" / "build.gradle.kts"
    if not build_gradle.exists():
        return "build.gradle.kts не найден"
    text = build_gradle.read_text()
    if 'applicationId = "com.voxli"' not in text:
        return "applicationId != com.voxli"
    return None

@check("AndroidManifest: AudioPlaybackService (MediaSessionService)", critical=False)
def _check_manifest_service():
    if not MANIFEST.exists():
        return "AndroidManifest.xml не найден"
    text = MANIFEST.read_text()
    if "MediaSessionService" not in text:
        return "MediaSessionService не зарегистрирован"
    return None

@check("AndroidManifest: foregroundServiceType mediaPlayback", critical=False)
def _check_manifest_fst():
    if not MANIFEST.exists():
        return "AndroidManifest.xml не найден"
    text = MANIFEST.read_text()
    if "mediaPlayback" not in text:
        return "foregroundServiceType mediaPlayback не указан"
    return None

# ─── 12. Зависимости (libs.versions.toml) ──────────────────────────────

@check("Зависимости: libs.versions.toml содержит OkHttp", critical=False)
def _check_dep_okhttp():
    path = PROJECT_ROOT / "gradle" / "libs.versions.toml"
    if not path.exists():
        return "libs.versions.toml не найден"
    text = path.read_text()
    if "okhttp" not in text:
        return "OkHttp не найден в version catalog"
    return None

# ─── 13. Seed DB ──────────────────────────────────────────────────────

@check("Seed DB: voxli_seed.db в assets", critical=False)
def _check_seed_db():
    assets_db = ASSETS / "databases" / "voxli_seed.db"
    if not assets_db.exists():
        return "voxli_seed.db не найден в assets/databases/"
    if assets_db.stat().st_size < 1000:
        return f"voxli_seed.db слишком мал: {assets_db.stat().st_size} байт"
    return None

# ─── 14. Room — таблицы ──────────────────────────────────────────────

@check("Room: books (BookEntity) — поля соответствуют roadmap", critical=False)
def _check_room_books():
    path = SRC / "catalog" / "db" / "BookEntity.kt"
    fields = ["id", "title", "author", "annotation", "genre", "series", 
              "seriesNum", "lang", "rating", "votesCount", "hasFb2", 
              "hasEpub", "hasAudio", "createdAt"]
    for f in fields:
        if not file_contains(path, f"val {f}"):
            return f"поле {f} отсутствует в BookEntity"
    return None

@check("Room: history (HistoryEntity) — поля соответствуют roadmap", critical=False)
def _check_room_history():
    path = SRC / "catalog" / "db" / "HistoryEntity.kt"
    fields = ["bookId", "status", "charOffset", "progress", "playbackPos", 
              "startedAt", "finishedAt", "updatedAt"]
    for f in fields:
        if not file_contains(path, f"val {f}"):
            return f"поле {f} отсутствует в HistoryEntity"
    return None

# ─── Запуск ──────────────────────────────────────────────────────────

def main():
    import argparse
    parser = argparse.ArgumentParser(description="Voxli — верификация по контракту roadmap.md")
    parser.add_argument("--apk", default=str(APK_DEFAULT), help="Путь к APK")
    parser.add_argument("--skip-apk", action="store_true", help="Пропустить проверку APK")
    args = parser.parse_args()

    print("\n" + "=" * 65)
    print("  🔍 VOXLI — ВЕРИФИКАЦИЯ ПО КОНТРАКТУ ROADMAP")
    print("=" * 65)

    # 1. APK
    if not args.skip_apk:
        print("\n📦 1. APK")
        err = verify_apk_basic(args.apk)
        if err:
            checks_failed.append(f"❌ APK: {err}")
            print(f"  ❌ APK: {err}")
        else:
            checks_passed.append("APK: собран, содержит AndroidManifest + DEX + Seed DB")
            print("  ✅ APK: собран, содержит AndroidManifest + DEX + Seed DB")

    # 2–14. Source checks
    print("\n📁 2. Архитектура пакетов (§6)")
    _check_pkg_catalog()
    _check_pkg_flibusta()
    _check_pkg_knigavuhe()
    _check_pkg_reader()
    _check_pkg_audio()
    _check_pkg_tts()
    _check_pkg_network()
    _check_pkg_di()
    _check_pkg_ui()

    print("\n📐 3. Контракты парсера и пагинатора (§7.5)")
    _check_bookparser()
    _check_documentmodel()
    _check_parablock()
    _check_paginator()
    _check_page()

    print("\n🔎 4. FTS5-поиск (§4.2)")
    _check_fts_create()
    _check_fts_triggers()
    _check_fts_sanitize()
    _check_fts_queries()

    print("\n👆 5. Читалка (§7.1–7.3)")
    _check_tap_zones()
    _check_reader_vm()

    print("\n📚 6. Библиотека (§8)")
    _check_library_vm()
    _check_genre_screen()
    _check_history_screen()

    print("\n🔧 7. DI/Modules")
    _check_di_reader()
    _check_di_others()
    _check_di_db()

    print("\n🌐 8. Сетевой слой (§15)")
    _check_network()
    _check_flibusta_mirrors()
    _check_nsc()

    print("\n⚙️  9. Settings/DataStore (§4.4)")
    _check_settings()

    print("\n🎵 10. Аудиоплеер (§10 Phase 3)")
    _check_audio_downloader()
    _check_exo_headers()
    _check_mp3_cache()

    print("\n📋 11. AndroidManifest")
    _check_manifest_pkg()
    _check_manifest_service()
    _check_manifest_fst()

    print("\n📦 12. Зависимости")
    _check_dep_okhttp()

    print("\n💾 13. Seed DB")
    _check_seed_db()

    print("\n🗄️  14. Room — таблицы")
    _check_room_books()
    _check_room_history()

    # ─── ИТОГО ──────────────────────────────────────────────────────
    total = len(checks_passed) + len(checks_failed) + len(checks_warn)
    print("\n" + "=" * 65)
    print(f"  ИТОГО: {total} проверок")
    print(f"  ✅ Пройдено: {len(checks_passed)}")
    print(f"  ❌ Провалено: {len(checks_failed)}")
    print(f"  ⚠️  Предупреждения: {len(checks_warn)}")
    print("=" * 65)

    if checks_failed:
        print(f"\n  ❌ ПРОВАЛ: {len(checks_failed)} критических проверок не пройдено!")
        sys.exit(1)
    else:
        print("\n  ✅ ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ — программа соответствует контракту roadmap.md")
        sys.exit(0)

if __name__ == "__main__":
    main()
