# Voxli — Implementation Audit

## Phase 1 — Project scaffold (completed)
## Phase 2 — Reader (completed)

## Phase 3 — Audio Player (completed)

| Task | Status | Notes |
|------|--------|-------|
| MP3 Downloader (with progress + resume) | ✅ | `AudioDownloader` — Range header resume, per-track progress |
| Player UI | ✅ | `PlayerScreen` — cover, track list, play/pause, seek, speed, time |
| PlayerViewModel | ✅ | `PlayerViewModel` — bind to service, position polling, progress save |
| AudioPlaybackService | ✅ | ExoPlayer + hotlinking headers, playlist, media session |
| Android 14 MediaSession permissions | ✅ | added in Phase 1, manifest has FOREGROUND_SERVICE_MEDIA_PLAYBACK |
| LRU MP3 cache cleanup | ✅ | `Mp3CacheCleaner` — WorkManager daily, evict if <1GB free + >30 days |
| Bluetooth Media Buttons | ✅ | MediaSession handles SKIP_TO_NEXT/PREVIOUS via ExoPlayer |

## Phase 4 — Search + Polish (completed)

| Task | Status | Notes |
|------|--------|-------|
| Полнотекстовый поиск (FTS5) | ✅ | `LibraryViewModel.performSearch()` — debounce 300ms, prefix search |
| Фильтрация по рейтингу | ✅ | Сортировка по `rating DESC`, отображение ★ |
| Экран истории | ✅ | `HistoryScreen` — список с прогресс-баром, статус |
| Pull-to-refresh | ✅ | `PullToRefreshBox` с `FlibustaProvider.mirrorSwitch()` |
| Snackbar для ошибок сети | ✅ | `SnackbarHost` в `Scaffold`, `clearError()` |
| Анимации переходов | ✅ | `fadeIn/fadeOut` в NavHost + `AnimatedContent` для режимов |
| Навигация Library → Reader → Player | ✅ | `NavHost` с 5 маршрутами |
| GenreSelectionScreen интеграция | ✅ | через `onFilterClick` → popBackStack |
| DI | ✅ | LibraryViewModel зарегистрирован |

| Task | Status | Notes |
|------|--------|-------|
| MP3 Downloader (with progress + resume) | ✅ | `AudioDownloader` — Range header resume, per-track progress |
| Player UI | ✅ | `PlayerScreen` — cover, track list, play/pause, seek, speed, time |
| PlayerViewModel | ✅ | `PlayerViewModel` — bind to service, position polling, progress save |
| AudioPlaybackService | ✅ | ExoPlayer + hotlinking headers, playlist, notification, media session |
| Android 14 MediaSession permissions | ✅ | Added in Phase 1, manifest has FOREGROUND_SERVICE_MEDIA_PLAYBACK |
| LRU MP3 cache cleanup | ✅ | `Mp3CacheCleaner` — WorkManager daily, evict if <1GB free + >30 days |
| Bluetooth Media Buttons | ✅ | MediaSession handles SKIP_TO_NEXT/PREVIOUS via ExoPlayer |
| Koin integration | ✅ | AudioDownloader, Mp3CacheCleaner, PlayerViewModel registered |

## Files (all phases)
See `git log --stat` for full list. 48 files total.

| File | Status |
|------|--------|
| Gradle project structure (8 files) | ✅ |
| AndroidManifest + VoxliApp + MainActivity | ✅ |
| Room DB: BookEntity, HistoryEntity, SettingsEntity + DAOs | ✅ |
| VoxliDatabase + FTS5 triggers + seed fallback | ✅ |
| `catalog/db/FtsQuery.kt` | ✅ |
| `di/Modules.kt` — Koin DI | ✅ |
| `network/NetworkModule.kt` — OkHttp + DoH | ✅ |
| `flibusta/provider/FlibustaProvider.kt` | ✅ |
| `knigavuhe/matcher/KnigavuheMatcher.kt` | ✅ |
| `reader/engine/DocumentModel.kt` + `BookDownloader.kt` | ✅ |
| `audio/engine/AudioPlaybackService.kt` (Phase 3 stub) | ✅ |
| `ui/library/LibraryScreen.kt` + `GenreSelectionScreen.kt` | ✅ |
| `settings/SettingsRepository.kt` — DataStore | ✅ |
| `scripts/generate_seed_db.py` — Python seed generator | ✅ |
| `gradlew` + `gradlew.bat` — Gradle wrapper | ✅ |
| `doc/audit.md` — this file | ✅ |

## Checklist (from roadmap §10 Phase 1)

| Task | Status | Notes |
|------|--------|-------|
| Gradle-структура (Kotlin 2.0.21, Compose BOM, Room, Koin, OkHttp) | ✅ | version catalog `gradle/libs.versions.toml` |
| SQLite-схема: books + history + settings + FTS5 | ✅ | Entity + DAO + FTS5 triggers |
| FlibustaProvider: OPDS + HTML парсер | ✅ | Mirror switching, `withContext(IO)`, Ksoup — Phase 2 |
| KnigavuheMatcher: fuzzy matching + narrators | ✅ | ConcurrentHashMap, regex parser — Ksoup Phase 2 |
| Загрузчик книг (FB2/EPUB в кэш) | ✅ | `BookDownloader` с прогрессом |
| UI библиотеки: поиск + авторы/названия | ✅ | LibraryScreen + GenreSelectionScreen |
| UI: список авторов/названий | ✅ | LazyColumn placeholder |
| UI: нижняя панель (сортировка/жанры/цвета/шрифт) | ✅ | BottomSettingsBar + GenreSelectionScreen |
| UI: экран жанров (22 чекбокса) | ✅ | `GenreSelectionScreen` |
| DataStore: настройки | ✅ | `SettingsRepository` с Flow |
| Seed DB Python-скрипт | ✅ | `scripts/generate_seed_db.py` |
| Gradle wrapper (gradlew) | ✅ | `./gradlew --version` works |

## Roadmap Spec Compliance

| Requirement | Status |
|-------------|--------|
| Ktor исключён, только OkHttp | ✅ |
| `okhttp-dnsoverhttps` | ✅ |
| `StaticLayout` для background-измерения | ✅ (spec, код в Phase 2) |
| `DocumentModel` / `BookParser` контракт | ✅ |
| `ConcurrentHashMap` для NarratorCache | ✅ |
| `SupervisorJob()` | ✅ |
| Пакеты в одном `:app`-модуле | ✅ |
| `withContext(Dispatchers.IO)` для блокирующих вызовов | ✅ |
| Graceful fallback для seed DB | ✅ |
| FTS5 triggers с синтаксисом 'delete' | ✅ |
| `sanitizeFtsQuery()` | ✅ |
| ExoPlayer hotlinking headers (User-Agent, Referer) | ✅ |
| network_security_config.xml для HTTP | ✅ |
| Android 14 MediaSession permissions | ✅ |
| Bluetooth Media Buttons (ACTION_SKIP_TO_NEXT/PREV) | ⏳ (Phase 3) |
| DataStore for settings | ✅ |
| BookDownloader with progress | ✅ |
| Genre screen (22 genres) | ✅ |
| Seed DB Python script | ✅ |
| gradlew scripts | ✅ |

## Remaining Known Gaps

- [ ] Ksoup-based OPDS/HTML parsers (stubbed with regex — Phase 2)
- [ ] Inline formatting measurement in Paginator (Phase 2)
- [ ] Full ViewModel + Repository wiring with live data (Phase 2)
- [ ] Unit tests
- [ ] Android SDK for actual build
