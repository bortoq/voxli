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

- [x] Создание Android-проекта (Gradle, модули, Koin DI)
- [ ] SQLite-схема: books + history + settings ✅ код есть, **но** seed DB не сгенерирован
- [ ] FlibustaProvider: OPDS-обход, парсинг HTML страницы книги ✅ (Ksoup-парсер — TODO)
- [ ] KnigavuheMatcher: поиск аудио по (title, author), извлечение URL ✅ (regex парсер — TODO Ksoup)
- [ ] Загрузчик книг: скачивание FB2/EPUB в кэш ❌ (не реализован)
- [ ] UI библиотеки: верхняя панель (поиск + цикл авторы/названия) ✅ (базовый UI)
- [ ] UI: список авторов/названий ✅ (каркас с Placeholder)
- [ ] UI: нижняя панель (цикл: сортировка, жанры, цвета, шрифт) ✅ (базовый BottomSettingsBar)
- [ ] UI: экран жанров (22 чекбокса) ❌
- [ ] DataStore: сохранение настроек ❌

## Fixes Needed

- [ ] `Ktor` полностью исключён ✅
- [ ] `OkHttp` + `okhttp-dnsoverhttps` ✅
- [ ] `StaticLayout` для background измерения ✅ (в spec, код пагинатора — Phase 2)
- [ ] `DocumentModel` / `BookParser` контракт ✅
- [ ] `ConcurrentHashMap` для NarratorCache ✅
- [ ] `SupervisorJob()` вместо `supervisorJob` ✅
- [ ] Пакеты в одном `:app` модуле ✅

## Code Issues Found

1. **Blocking OkHttp calls in suspend functions** — `FlibustaProvider.fetchUrl()` and `KnigavuheMatcher.fetchHtml()` use `.execute()` without `withContext(Dispatchers.IO)`. Will crash if called from Main dispatcher. **Fix below.**

2. **Seed DB not generated** — `createFromAsset("databases/voxli_seed.db")` will crash if file missing. Need graceful fallback.

3. **DataStore not wired** — Settings are meant to use DataStore but only Room `settings` table is created.

4. **BookDownloader missing** — No download manager for FB2/EPUB files.

5. **Genre screen missing** — 22 flibusta genres with checkboxes not implemented.

6. **gradlew script missing** — Cannot build without Gradle wrapper scripts.
