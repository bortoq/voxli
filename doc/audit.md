# Voxli — Phase 1 Implementation Audit

## Files Created
See `git log --stat` for full list. Key files:

| File | Status |
|------|--------|
| `settings.gradle.kts` | ✅ |
| `build.gradle.kts` (root) | ✅ |
| `gradle.properties` | ✅ |
| `gradle/libs.versions.toml` | ✅ |
| `app/build.gradle.kts` | ✅ |
| `app/proguard-rules.pro` | ✅ |
| `app/src/main/AndroidManifest.xml` | ✅ |
| `res/values/{themes,colors}.xml` | ✅ |
| `res/xml/network_security_config.xml` | ✅ |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | ✅ |
| `res/drawable/ic_launcher_foreground.xml` | ✅ |
| `catalog/db/BookEntity.kt` | ✅ |
| `catalog/db/BookDao.kt` | ✅ |
| `catalog/db/HistoryEntity.kt` | ✅ |
| `catalog/db/HistoryDao.kt` | ✅ |
| `catalog/db/SettingsDao.kt` | ✅ |
| `catalog/db/VoxliDatabase.kt` | ✅ |
| `catalog/db/FtsQuery.kt` | ✅ |
| `di/Modules.kt` | ✅ |
| `network/NetworkModule.kt` | ✅ |
| `flibusta/provider/FlibustaProvider.kt` | ✅ (stub OPDS parser) |
| `knigavuhe/matcher/KnigavuheMatcher.kt` | ✅ (stub HTML parser) |
| `reader/engine/DocumentModel.kt` | ✅ |
| `audio/engine/AudioPlaybackService.kt` | ✅ (stub for Phase 3) |
| `ui/library/LibraryScreen.kt` | ✅ (mock data) |
| `VoxliApp.kt` | ✅ |
| `MainActivity.kt` | ✅ |

## Checklist (from roadmap §10 Phase 1)

| Task | Status | Notes |
|------|--------|-------|
| Gradle-структура (Kotlin 2.0.21, Compose BOM, Room, Koin, OkHttp) | ✅ | version catalog `gradle/libs.versions.toml` |
| SQLite-схема: books + history + settings + FTS5 | ✅ | Entity + DAO + FTS5 triggers в Callback |
| FlibustaProvider: OPDS + HTML парсер | ✅ | Mirror switching, `withContext(IO)`, Ksoup — todo |
| KnigavuheMatcher: fuzzy matching + narrators | ✅ | ConcurrentHashMap, regex parser — todo Ksoup |
| Загрузчик книг (FB2/EPUB в кэш) | ❌ | Phase 2 |
| UI библиотеки: поиск + авторы/названия | ✅ | Basic Compose screen with mock data |
| UI библиотеки: список авторов | ✅ | Placeholder items |
| UI библиотеки: список названий | ✅ | Placeholder items |
| UI библиотеки: нижняя панель (сортировка/жанры/цвета/шрифт) | ✅ | BottomSettingsBar stub |
| UI библиотеки: экран жанров (22 чекбокса) | ❌ | Not implemented |
| DataStore: настройки | ❌ | Not implemented (Room `settings` table exists) |

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

## Code Issues Found (fixed during audit)

| # | Issue | Fix |
|---|-------|-----|
| 1 | Blocking OkHttp calls in suspend functions | ✅ wrapped with `withContext(Dispatchers.IO)` |
| 2 | `createFromAsset` crash if seed DB absent | ✅ graceful try/catch fallback |
| 3 | `HistoryDao.getAllHistory()` column mapping | ✅ explicit SQL aliases (`AS bookId`, etc) |
| 4 | unused `getRandomBooks()` | ✅ replaced with `getBooksNeedingAudioCheck()` |
| 5 | Missing `org.gradle.jvmargs` | ✅ set in `gradle.properties` |
| 6 | No gradlew scripts | ⚠️ generate via `gradle wrapper` on dev machine |

## Remaining for Phase 1

- [ ] Generate `gradlew` scripts (requires Gradle SDK on dev machine)
- [ ] Generate `voxli_seed.db` (separate Python/Kotlin script, roadmap §14.3)
- [ ] Implement Ksoup-based OPDS/HTML parsers in FlibustaProvider
- [ ] Implement Ksoup-based HTML parsers in KnigavuheMatcher
- [ ] Implement BookDownloader (download FB2/EPUB to cache dir)
- [ ] Implement GenreSelectionScreen (22 checkboxes)
- [ ] Wire DataStore for settings
- [ ] Wire ViewModel + Repository layers for live data
- [ ] Write unit tests for DAOs, FtsQuery, sanitizeFtsQuery, KnigavuheMatcher
