# Voxli — Полный аудит кода

**Дата:** 2026-07-16  
**Аудитор:** Senior Code Forensic & AppSec QA  
**Проверено:** 33 файла исходного кода, 6 файлов тестов, roadmap.md  
**Строк кода:** ~5 500

---

## Содержание

1. [Критические проблемы](#1-критические-проблемы)
2. [Предупреждения](#2-предупреждения)
3. [Нарушения roadmap](#3-нарушения-roadmap)
4. [Утечки памяти и ресурсов](#4-утечки-памяти-и-ресурсов)
5. [Проблемы безопасности](#5-проблемы-безопасности)
6. [Проблемы сложности](#6-проблемы-сложности)
7. [Проблемы читаемости](#7-проблемы-читаемости)
8. [Покрытие тестами](#8-покрытие-тестами)
9. [План рефакторинга](#9-план-рефакторинга)

---

## 1. Критические проблемы

### C1 — Mp3CacheCleaner: переполнение Int (баг логики)

**Файл:** `audio/engine/Mp3CacheCleaner.kt:52`  
**Тип:** Logic Bug  
**Описание:**
```kotlin
val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24 * 60 * 60 * 1000
// MAX_AGE_DAYS = 30 → 30 * 24 * 60 * 60 * 1000 = 2 592 000 000
// Int.MAX_VALUE = 2 147 483 647 → ПЕРЕПОЛНЕНИЕ!
```
Произведение вычисляется как `Int` и переполняется в отрицательное значение. `currentTimeMillis() - отрицательное` даёт дату в ДАЛЁКОМ будущем. Ни один файл никогда не удалится. Кэш аудио растёт бесконтрольно.

**Исправление:** `MAX_AGE_DAYS.toLong() * 24 * 60 * 60 * 1000`

---

### C2 — ReaderViewModel: дублирование корутин при updateDimensions

**Файл:** `ui/reader/ReaderViewModel.kt:127-167`  
**Тип:** Resource Leak  
**Описание:**  
`buildPaginator()` запускает 2 корутины на сбор flow (`currentPageIndex`, `pageCount`).  
`updateDimensions()` запускает ЕЩЁ 2 корутины для нового пагинатора, НЕ отменяя старые.  
Хотя старый пагинатор cancelled (его flow не эмитят), 2 корутины-коллектора остаются висеть в `viewModelScope` до очистки ViewModel.

Побочный эффект: при быстрых `updateDimensions` старые корутины могут успеть эмитнуть значения после обновления состояния → race condition на `_currentPageIndex`.

**Исправление:** Отменять старые корутины (сохранять Job handles) или использовать `collectLatest`.

---

### C3 — AudioDownloader.resumeDownload(): readBytes() → OOM

**Файл:** `audio/engine/AudioDownloader.kt:124-125`  
**Тип:** Crash Risk (OutOfMemoryError)  
**Описание:**  
```kotlin
body.byteStream().use { input ->
    file.appendBytes(input.readBytes())  // читает ВЕСЬ остаток в память
}
```
Если докачка прервалась на 50 МБ, `readBytes()` выделяет 50 МБ буфер. На устройствах с 256 МБ heap → OOM.

**Исправление:** Использовать буферизированное копирование (как в свежей загрузке, строки 80-93):
```kotlin
input.copyTo(output)
```

---

### C4 — AudioDownloader / BookDownloader: mkdirs() на каждый вызов

**Файлы:**  
- `audio/engine/AudioDownloader.kt:19`  
- `reader/engine/BookDownloader.kt:17`  
**Тип:** Performance (лишний I/O)  

**Описание:** `cacheDir` — computed property, вызывающий `mkdirs()` при каждом доступе. Это I/O-операция на каждый вызов `isCached()`, `download()`, `clearCache()`, `getCacheSize()`, `deleteBookCache()`.  

**Исправление:**  
```kotlin
private val cacheDir: File by lazy { File(context.cacheDir, "books").also { it.mkdirs() } }
```

---

### C5 — PlayerViewModel: onCleared() не дожидается сохранения прогресса

**Файл:** `ui/player/PlayerViewModel.kt:242-247`  
**Тип:** Data Loss Risk  
**Описание:**  
```kotlin
override fun onCleared() {
    saveProgressSync()  // запускает корутину без await
    ...
}
```
Если процесс убит сразу после `onCleared()`, корутина не успевает выполнить запись в БД. Последние секунды прогресса теряются.

**Исправление:** Использовать `runBlocking { saveProgress() }` с таймаутом, или синхронную запись.

---

### C6 — Paginator: lazy paragraphLayouts может заблокировать UI тред

**Файл:** `reader/engine/Paginator.kt:44-46`  
**Тип:** UI Freeze Risk  
**Описание:**  
`paragraphLayouts` инициализируется лениво. Если `getPage()` или `findPageByCharOffset()` вызваны до `calculatePages()`, ленивая инициализация вызывает `textMeasurer.measureParagraphs()` на вызывающем трэде. В `ReaderViewModel` эти вызовы идут из flow-коллекторов на `Dispatchers.Main` → **freeze UI**.

**Исправление:** Вызывать `paragraphLayouts` явно в `calculatePages()` перед корутиной:
```kotlin
fun calculatePages() {
    val layouts = paragraphLayouts  // форсировать инициализацию
    scope.launch { ... }
}
```

---

## 2. Предупреждения

### W1 — OkHttp Response не закрыт на error-путях

**Файлы:**  
- `flibusta/provider/FlibustaProvider.kt:45-50,81-89`  
- `knigavuhe/matcher/KnigavuheMatcher.kt:64`  

**Описание:** При `!response.isSuccessful` тело ответа не потребляется и соединение не возвращается в пул немедленно. OkHttp со временем утилизирует их, но при большом количестве ошибок возможно истощение connection pool.

**Исправление:** Использовать `response.use { ... }` или `response.close()` во всех ветках.

---

### W2 — Fb2Parser: file.readText() загружает весь файл

**Файл:** `reader/engine/Fb2Parser.kt:27`  
**Описание:** FB2-файлы могут быть 5–10 МБ. `readText()` загружает весь файл в память.

**Исправление:** Использовать `BufferedReader` для потокового чтения.

---

### W3 — LibraryViewModel: жёсткий лимит 200 книг

**Файл:** `ui/library/LibraryViewModel.kt:135`  
**Описание:** `getAllBooks(limit = 200)`. В seed DB ~5000 книг. Пользователь видит только 200.

**Исправление:** Увеличить лимит или убрать (Room без лимита).

---

### W4 — LibraryViewModel: сортировка "по популярности" ничего не делает

**Файл:** `ui/library/LibraryViewModel.kt:141`  
**Описание:** `SortField.POPULARITY -> all` возвращает книги в порядке БД (по рейтингу). Поле `popularity` в таблице отсутствует.

**Исправление:** Либо добавить поле `popularity` в `BookEntity`, либо удалить опцию сортировки.

---

### W5 — GenreSelectionScreen: Select All делает N операций

**Файл:** `ui/library/GenreSelectionScreen.kt:78-90`  
**Описание:** Для каждого жанра вызывается `onGenreToggle()`, который дёргает DataStore и `loadBooks()`. Для 22 жанров → 22 записи в DataStore и 22 загрузки.

**Исправление:** Добавить bulk-операцию `setAllGenres(Set<String>)` во ViewModel.

---

### W6 — SettingsRepository: хрупкое преобразование цвета

**Файл:** `settings/SettingsRepository.kt:46,54`  
**Описание:** `0xFFFFFFFF.toInt()` = `-1` (overflow Int). `Color(-1)` в Compose работает, но если когда-либо потребуется читать как Long, значения разойдутся.

**Исправление:** Хранить как hex String или использовать `Color(0xFFFFFFFFL.toInt())`.

---

### W7 — BookDao: WHERE IN () с пустой коллекцией — SQLite syntax error

**Файл:** `catalog/db/BookDao.kt:12`  
**Описание:** Если кто-то вызовет `getBooksByGenres(emptyList())`, Room кинет `IllegalArgumentException`. Сейчас защищено на уровне ViewModel, но это хрупко.

**Исправление:** Добавить проверку в DAO или документировать контракт.

---

### W8 — VoxliDatabase: лишняя проверка наличия seed-файла

**Файл:** `catalog/db/VoxliDatabase.kt:31-33`  
**Описание:** `context.assets.open(...)` только для проверки существования. Любая ошибка (IO/permissions) трактуется как "файла нет" и создаётся пустая БД.  

**Исправление:** Использовать `context.assets.list("databases")?.contains("voxli_seed.db")`.

---

### W9 — AndroidTextMeasurer: жёстко заданный +4px межстрочный интервал

**Файл:** `reader/android/AndroidTextMeasurer.kt:51`  
**Описание:** `fm.descent - fm.ascent + 4` не учитывает плотность экрана.  

**Исправление:** Использовать `fm.descent - fm.ascent + fm.leading`.

---

### W10 — MainActivity: bookId по умолчанию 0

**Файл:** `MainActivity.kt:116`  
**Описание:** При отсутствии аргумента навигации `bookId = 0L`. `loadBook(0)` пытается скачать книгу с flibusta ID 0, что вызывает лишний сетевой запрос.

**Исправление:** Добавить проверку `if (bookId == 0L) { error = "ID книги не указан" }`.

---

### W11 — ReaderScreen: настройки не видны при загруженной странице

**Файл:** `ui/reader/ReaderScreen.kt:106`  
**Описание:** Условие `readerMode == SETTINGS && currentPage == null` — настройки показываются ТОЛЬКО когда страница не загружена. При загруженной книге settings mode показывает контент, но зоны тапа ничего не делают.  

**Исправление:** Показывать оверлей настроек поверх страницы.

---

## 3. Нарушения roadmap

### R1 — §8: Отсутствует карточка книги при тапе

**Описание:** Roadmap §8.2: "тап по книге → карточка с аннотацией + список чтецов + кнопка [Читать]".  
**Реальность:** Тап по книге сразу открывает Reader (или навигацию). Карточки нет, списка чтецов нет.  

---

### R2 — §8: Прогресс-бар библиотеки — не те действия цикла

**Описание:** Roadmap §8.3: цикл "Сорт→Фон→Текст→Шрифт→узкий бар".  
**Реальность:** В LibraryScreen.kt цикл: `1=Сорт, 2=Фон, 3=Текст, 4=Шрифт, →0`. Это верно.  
**Но:** Действия свайпов (◄►▲▼) для фона/текста/шрифта не реализованы нигде, кроме FONT size в ReaderViewModel.  

---

### R3 — §7.1 (roadmap): центр зона — переключение Reader/Player

**Описание:** Zone 3 (центр) в ReaderScreen должна переключать между Reader и Player.  
**Реальность:** Zone 3 вызывает `navController.navigate(Routes.player(bookId, 0))`, что всегда открывает нового Player (создаёт новый стэк). Из Player нет тапа для возврата в Reader.  

---

### R4 — §10 Phase 2: AndroidTextMeasurerTest

**Описание:** Roadmap упоминает "AndroidTextMeasurerTest — 1 тест с Robolectric".  
**Реальность:** Тест не существует.  

---

### R5 — §4.2: FTS5 сантайзер цитирует все токены

**Описание:** Roadmap показывает `"война"* "и"*` (все токены в кавычках).  
**Реальность:** `FtsQuery.kt` цитирует только токены со спецсимволами. Это ПРАВИЛЬНО (FTS5 не поддерживает `*` после quoted term), roadmap нужно исправить.  

---

## 4. Утечки памяти и ресурсов

| # | Файл | Описание |
|---|------|----------|
| M1 | `ReaderViewModel.kt:127-167` | Корутины-коллекторы не отменяются при updateDimensions |
| M2 | `AudioPlaybackService.kt` | `MediaSession` не освобождается при уничтожении service |
| M3 | `FlibustaProvider.kt` | OkHttp Response не closed на error-путях |
| M4 | `KnigavuheMatcher.kt` | OkHttp Response не closed на error-путях |
| M5 | `PlayerViewModel.kt:35` | Ссылка на `AudioPlaybackService` может стать stale |
| M6 | `Paginator.kt:31` | `CoroutineScope` не cancellable извне |

---

## 5. Проблемы безопасности

| # | Файл | Описание |
|---|------|----------|
| S1 | `network/NetworkModule.kt` | DNS через Cloudflare (1.1.1.1) — все запросы видны Cloudflare |
| S2 | `res/xml/network_security_config.xml` | Разрешён HTTP (cleartext) к flibusta и knigavuhe |
| S3 | `network/NetworkModule.kt` | Нет certificate pinning — возможен MITM |
| S4 | `FlibustaProvider.kt` | Regex-парсинг XML — возможна инъекция при маломерном фиде |
| S5 | `FtsQuery.kt` | FTS5 query строится из пользовательского ввода — требуется санитайзинг (есть) |

---

## 6. Проблемы сложности

| # | Файл | Описание |
|---|------|----------|
| X1 | `LibraryScreen.kt:257-263` | Двойной проход по списку книг (series / standalone) |
| X2 | `Paginator.kt:138-147` | `findPageByCharOffset()` — O(n) линейный поиск |
| X3 | `FlibustaProvider.kt` | Regex-парсинг XML — хрупкий и медленный на больших фидах |
| X4 | `ReaderViewModel.kt` | Двойная пагинация (hardcoded 1080x1600 → layout → updateDimensions) |
| X5 | `AudioDownloader.kt:39-62` | HEAD-запрос для каждого трека при открытии книги (50+ запросов) |
| X6 | `LibraryViewModel.kt:183-185` | Fire-and-forget DataStore write — ошибка молчаливо проглатывается |

---

## 7. Проблемы читаемости

| # | Файл | Описание |
|---|------|----------|
| L1 | `ReaderViewModel.kt:232-246` | `SimpleDateFormat` создаётся на каждый page turn |
| L2 | `LibraryScreen.kt:56` | `settingsStep` как локальный Int — неочевидная логика цикла |
| L3 | `Fb2Parser.kt` | Двойной проход парсинга (parseFb2 + extractMeta) |
| L4 | `EpubParser.kt:145` | `author.isEmpty()` — почему не `author.isBlank()`? |
| L5 | `FlibustaProvider.kt` | 214 строк в одном файле — слишком много для одного класса |

---

## 8. Покрытие тестами

### Существующие тесты: 44 теста (все проходят)

| Файл | Тестов | Что покрывают |
|------|--------|--------------|
| `PaginatorTest.kt` | 13 | Пагинация, навигация, rebuild, cancel, empty |
| `LibraryViewModelTest.kt` | 12 | Поиск, жанры, сортировка, refresh |
| `ReaderViewModelTest.kt` | 10 | Initial state, load, navigation, cycleSettings |
| `FlibustaProviderTest.kt` | 8 | OPDS parsing |
| `LibraryViewModelCrashTest.kt` | 5 | Crash regression (пустые жанры, исключения БД) |

### Не покрыто тестами (17 компонентов)

| Компонент | Нужен тест |
|-----------|-----------|
| `FtsQuery.sanitizeFtsQuery()` | Unit |
| `FtsQuery.buildBookFtsQuery()` | Unit |
| `FtsQuery.buildAuthorFtsQuery()` | Unit |
| `AudioDownloader` | Unit/Integration |
| `AudioPlaybackService` | Integration |
| `Mp3CacheCleaner` | Unit |
| `BookDownloader` | Unit |
| `Fb2Parser` | Unit |
| `EpubParser` | Unit |
| `AndroidTextMeasurer` | Unit (Robolectric) |
| `TtsEngine` | Unit |
| `KnigavuheMatcher.parseSearchResults()` | Unit |
| `KnigavuheMatcher.parseNarrators()` | Unit |
| `PlayerViewModel` | Unit |
| `SettingsRepository` | Unit |
| `NetworkModule` | Unit |
| `GenreSelectionScreen` | Compose UI test |

---

## 9. План рефакторинга

### Фаза 1 — Блокирующие баги (немедленно)

| # | Задача | Файл | Трудоёмкость |
|---|--------|------|-------------|
| 1.1 | Исправить Int overflow в Mp3CacheCleaner | `Mp3CacheCleaner.kt:52` | 5 мин |
| 1.2 | Заменить `readBytes()` на буферизированное копирование | `AudioDownloader.kt:124-125` | 15 мин |
| 1.3 | Добавить cancel для старых корутин в `updateDimensions` | `ReaderViewModel.kt:127-167` | 30 мин |
| 1.4 | Исправить `cacheDir` на `by lazy` | `AudioDownloader.kt:19`, `BookDownloader.kt:17` | 10 мин |
| 1.5 | Исправить сохранение прогресса в `onCleared()` | `PlayerViewModel.kt:242-247` | 15 мин |
| 1.6 | Форсировать `paragraphLayouts` в `calculatePages()` | `Paginator.kt` | 10 мин |

### Фаза 2 — Roadmap compliance

| # | Задача | Трудоёмкость |
|---|--------|-------------|
| 2.1 | Реализовать карточку книги при тапе (аннотация + чтецы) | 2-3 дня |
| 2.2 | Реализовать reader↔player toggle через центр зоны (оба направления) | 1 день |
| 2.3 | Реализовать actions для settings (◄►▲▼ для всех шагов) | 1-2 дня |
| 2.4 | Показывать оверлей настроек поверх текста | 0.5 дня |
| 2.5 | Добавить bulk-операцию Select All/Deselect All в жанровый фильтр | 0.5 дня |
| 2.6 | Убрать/увеличить лимит 200 книг | 5 мин |
| 2.7 | Исправить сортировку "по популярности" (добавить поле или убрать) | 1 день |
| 2.8 | Реализовать per-mode настройки (отдельные keys в DataStore) | 1 день |

### Фаза 3 — Производительность и надёжность

| # | Задача | Трудоёмкость |
|---|--------|-------------|
| 3.1 | OkHttp Response.close() на error-путях (FlibustaProvider + KnigavuheMatcher) | 30 мин |
| 3.2 | Закешировать HEAD-запросы в AudioDownloader | 0.5 дня |
| 3.3 | Избежать двойной пагинации (убрать hardcoded 1080x1600) | 1 день |
| 3.4 | Оптимизировать saveProgress: сохранять каждые 5 сек, а не каждый page turn | 0.5 дня |
| 3.5 | Убрать двойной проход списка книг в LibraryScreen | 15 мин |

### Фаза 4 — Тесты

| # | Задача | Трудоёмкость |
|---|--------|-------------|
| 4.1 | Unit-тесты для FtsQuery (sanitize + build) | 1 день |
| 4.2 | Unit-тесты для Mp3CacheCleaner | 0.5 дня |
| 4.3 | Unit-тесты для AudioDownloader (mock HTTP) | 1 день |
| 4.4 | Unit-тесты для PlayerViewModel | 1 день |
| 4.5 | Unit-тесты для SettingsRepository (DataStore mocking) | 0.5 дня |
| 4.6 | AndroidTextMeasurerTest (Robolectric) | 0.5 дня |
| 4.7 | Fb2Parser / EpubParser тесты | 2 дня |

### Фаза 5 — Чистота кода

| # | Задача | Трудоёмкость |
|---|--------|-------------|
| 5.1 | Вынести `SimpleDateFormat` в companion object | 5 мин |
| 5.2 | `author.isEmpty()` → `author.isBlank()` | 5 мин |
| 5.3 | Проверить и поправить roadmap.md (§4.2 FTS5, §10 Phase 2 test) | 30 мин |
| 5.4 | Переименовать неочевидные переменные | 1 час |

---

## Итого

- **Critical:** 6
- **Warning:** 11
- **Roadmap violations:** 5
- **Untested components:** 17

Общая трудоёмкость: **~2-3 недели** для полного рефакторинга одним разработчиком.
