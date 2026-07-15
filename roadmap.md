# Voxli — Roadmap

Читалка электронных книг (FB2, EPUB) с in-situ озвучкой через TTS и аудиокнигами с knigavuhe.org.
Источник книг: flibusta.is (OPDS-каталог).

---

## 1. Обзор

Voxli — Android-приложение (Kotlin / Jetpack Compose) для чтения FB2/EPUB и прослушивания аудиокниг.
Книги — из flibusta.is. Озвучка — из knigavuhe.org (матчинг по названию+автору, флаг `has_audio`).
Локальные файлы — FB2, FB2.ZIP, EPUB, TXT, MP3.

---

## 2. Форматы

| Формат | Источник | Статус |
|--------|----------|--------|
| FB2 | flibusta.is, локальный | v1 |
| EPUB | flibusta.is, локальный | v1 |
| FB2.ZIP | flibusta.is, локальный | v1 |
| TXT | локальный | v1 |
| MP3 (аудиокнига) | knigavuhe.org (матчинг) | v1 |

PDF, MOBI, CBR/CBZ — не поддерживаются.

---

## 3. Источники данных

### 3.1. Flibusta.is (книги FB2/EPUB)

- **Доступ**: OPDS-каталог (`/opds/`), без авторизации.
- **Каталог**: 22 жанра, ~300 000 книг.
- **Поиск**: `/opds/search?searchType=books&searchTerm={query}`, `/opds/search?searchType=authors&searchTerm={query}`.
- **Новинки**: `/opds/new/0/new`, пагинация по offset (0, 100, 200…), ~770 книг в неделю.
- **Скачивание**: с HTML-страницы книги (`/b/<id>`) — прямые ссылки на FB2, EPUB.
- **Рейтинг**: из пользовательских оценок в комментариях.
- **Лицензия контента**: общественное достояние / свободно распространяется.

### 3.2. Knigavuhe.org (только источник аудио)

- Не является отдельным каталогом.
- При первичном заполнении БД для каждой книги flibusta проверяется наличие аудиоверсии на knigavuhe по `(title, author)`.
- Ставится флаг `has_audio = 1`.
- URL аудио не хранятся — запрос выполняется в момент тапа пользователя (1–2 сек).
- **Матчинг — нечёткий**: названия и имена авторов различаются на сайтах (инициалы vs полное имя, разные регистры, пунктуация).
  Алгоритм (по приоритету):
  1. Нормализация: lower case, удаление пунктуации, `"Толстой Лев Николаевич"` → `"толстой лев"`, `"Толстой Л.Н."` → `"толстой"` (только фамилия).
  2. Точное совпадение `(normalize(title), normalize(author))` — ~40% успеха.
  3. Совпадение по фамилии + первому слову названия — ещё ~30%.
  4. Levenshtein < 30% длины строки — ~20%. Выполняется в WorkManager, не блокирует UI.
     **Важно**: перед вычислением Левенштейна — фильтрация кандидатов в SQLite:
     ```sql
     SELECT id, title, author FROM books
     WHERE author LIKE :firstLetter '%'        -- первая буква фамилии
       AND LENGTH(title) BETWEEN :minLen AND :maxLen  -- ±20% от длины исходного названия
     LIMIT 100;
     ```
     Только для полученных 10–100 кандидатов вычисляется расстояние Левенштейна в Kotlin.
     Полная выборка 300k строк в Kotlin не производится.
  5. Нет совпадения → `has_audio = 0` (лучше ложноотрицательный, чем ложноположительный).
- Для shipped DB (топ-5000) матчинг выполняется скриптом однократно.

### 3.3. Локальные файлы

Импорт из файловой системы: FB2, FB2.ZIP, EPUB, TXT, MP3.

---

## 4. База данных (SQLite / Room)

Одна БД, поставляется с приложением (shipped DB).

### 4.1. Размер

- ~300 000 книг flibusta × ~250 байт/запись = **75 MB** (raw SQLite).
- Сжатие в APK (ZIP) ≈ **25–30 MB**.
- Индексы: ~15 MB дополнительно.

БД вшивается в APK, распаковывается при первом запуске в `databases/`.

### 4.2. Таблица: books

| Поле | Тип | Описание |
|------|-----|----------|
| id | INTEGER PK | flibusta_id из `/b/<id>` |
| title | TEXT | Название |
| author | TEXT | Автор |
| annotation | TEXT | Аннотация |
| genre | TEXT | Жанр (из 22 жанров flibusta) |
| series | TEXT | Серия / цикл |
| series_num | INTEGER | Номер в серии |
| lang | TEXT | Язык |
| rating | REAL | 0.0–5.0 |
| votes_count | INTEGER | Количество оценок |
| has_fb2 | BOOLEAN | Доступен FB2 |
| has_epub | BOOLEAN | Доступен EPUB |
| **has_audio** | **BOOLEAN** | **Есть аудиоверсия на knigavuhe** |
| created_at | TEXT | Дата добавления на flibusta |

Индексы: `(title COLLATE NOCASE)`, `(author COLLATE NOCASE)`, `(genre)`, `(rating)`, `(has_audio)`.

**FTS5 для поиска:**
```sql
-- Виртуальная таблица с внешним контентом
CREATE VIRTUAL TABLE books_fts USING fts5(
    title, author, content=books, content_rowid=id,
    tokenize='unicode61 remove_diacritics 1'
);

-- Триггеры: для content=books требуется двухэтапное 'delete' + insert
CREATE TRIGGER books_ai AFTER INSERT ON books BEGIN
    INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author);
END;

CREATE TRIGGER books_ad AFTER DELETE ON books BEGIN
    INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author);
END;

CREATE TRIGGER books_au AFTER UPDATE ON books BEGIN
    INSERT INTO books_fts(books_fts, rowid, title, author) VALUES ('delete', old.id, old.title, old.author);
    INSERT INTO books_fts(rowid, title, author) VALUES (new.id, new.title, new.author);
END;

-- Поиск: 5–20 мс на 300k записей
SELECT b.* FROM books b
JOIN books_fts ON b.id = books_fts.rowid
WHERE books_fts MATCH ?
ORDER BY rank
LIMIT 50;
```
- **Debounce** ввода: 300 мс после последнего символа.
- **Санитизация запроса** (экранирование спецсимволов FTS5: `: - " * ( )`):
  ```kotlin
  fun sanitizeFtsQuery(query: String): String {
      return query.trim()
          .split(Regex("\\s+"))
          .filter { it.isNotBlank() }
          .joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"*" }
  }
  // "война и мир 1869" → ""война"* "и"* "мир"* "1869"*"
  ```
- **Русская морфология**: стемминг не используется (FTS5 не поддерживает русский стеммер).
  Компенсация: все слова поиска автоматически получают суффикс `*` (префиксный поиск).
  `"война"` → `"война"*` находит `война`, `войне`, `войны`, `войной`.
- `rank = BM25` — встроенная релевантность FTS5.

### 4.3. Таблица: history

| Поле | Тип | Описание |
|------|-----|----------|
| book_id | INTEGER | id из books |
| status | TEXT | 'reading' / 'listening' / 'finished' / 'dropped' |
| **char_offset** | **INTEGER** | **Индекс символа в тексте книги (для текста)** |
| progress | REAL | 0.0–1.0 (производное от char_offset / total_chars) |
| playback_pos | INTEGER | Позиция в мс (для аудио) |
| started_at | TEXT | — |
| finished_at | TEXT | — |
| updated_at | TEXT | — |

**Почему char_offset, а не current_page**: номер страницы меняется при смене шрифта/размера/ориентации.
Индекс символа (символ N от начала текста) инвариантен к настройкам отображения.
Пагинатор восстанавливает позицию: `paginator.findPageByCharOffset(savedCharOffset)`.

### 4.4. Таблица: settings

| Поле | Тип | Описание |
|------|-----|----------|
| key | TEXT PK | Название настройки |
| value | TEXT | Значение |

Хранятся: font_name, font_size, bg_color, bg_brightness, text_color, text_brightness.

### 4.5. Обновление БД

- При каждом запуске — фоновая проверка новинок flibusta (OPDS `/new/`).
- Раз в неделю — полная перепроверка has_audio (knigavuhe).
- WorkManager, низкий приоритет, неспешно.

---

## 5. Нормализация рейтинга

### Flibusta

Парсинг оценок из комментариев: «отлично!» = 5, «хорошо» = 4, «неплохо» = 3, «плохо» = 2, «нечитаемо» = 1.
Среднее арифметическое по всем оценкам пользователей. Результат 0.0–5.0.

---

## 6. Архитектура приложения

```
┌─────────────────────────────────────────────┐
│                 UI (Compose)                 │
│  LibraryScreen / ReaderScreen / PlayerScreen │
└────────────────────┬────────────────────────┘
                     │
┌────────────────────┴────────────────────────┐
│              ViewModel / UseCases             │
│  LibraryViewModel / ReaderViewModel / ...     │
└────────────────────┬────────────────────────┘
                     │
┌────────────────────┴────────────────────────┐
│              Repository Layer                 │
│  ┌──────────────┐  ┌───────────────────┐     │
│  │  BookRepo    │  │  HistoryRepo      │     │
│  └──────┬───────┘  └───────────────────┘     │
└─────────┼────────────────────────────────────┘
          │
┌─────────┴────────────────────────────────────┐
│  Providers + Services                        │
│  ┌──────────┐ ┌───────────┐ ┌─────────────┐ │
│  │Flibusta  │ │ Knigavuhe │ │ Room DB     │ │
│  │(OPDS+HTML)│ │ (on-demand)│ │ (shipped)   │ │
│  └──────────┘ └───────────┘ └─────────────┘ │
└──────────────────────────────────────────────┘
```

### Модули

| Пакет | Описание |
|-------|----------|
| `catalog.db` | Room: books + history + settings |
| `flibusta.provider` | OPDS-клиент + HTML-парсер страницы книги |
| `knigavuhe.matcher` | Поиск аудио по (title, author), извлечение URL |
| `reader.engine` | FB2/EPUB парсеры (TextLector, Apache 2.0) + пагинатор |
| `audio.engine` | Загрузчик MP3 + Media3 плеер |
| `tts.engine` | Android TTS API |
| `ui.library` | Экран библиотеки (авторы/названия/поиск/настройки) |
| `ui.reader` | Экран чтения (пагинация, TTS, прогресс) |
| `ui.player` | Экран аудиоплеера |

> **Для v0.1** — все пакеты в одном Gradle-модуле `:app`. Разделение на Gradle-модули — при превышении 10k строк кода или необходимости тестировать изолированно.

---

## 7. Читалка — контракт

### 7.1. Геометрия экрана (зоны тапа без наложений)

Экран разделён на **непересекающиеся** зоны. Никакие две зоны не перекрываются.

```
┌───────────────────────────────────────────────┐
│  ═══════════ ЗОНА 1: ВЕРХ ═════════════════  │ ← 10% высоты, вся ширина
│  (тап = библиотека)                            │
├──────────┬──────────────────┬──────────────────┤
│  ЗОНА 2  │    ЗОНА 3        │    ЗОНА 4        │ ← 70% высоты
│  ◄ ЛЕВО  │    ЦЕНТР         │    ПРАВО ►        │
│  (30%)   │    (40%)         │    (30%)          │
│          │                  │                   │
│          │                  │                   │
├──────────┴──────────────────┴──────────────────┤
│  ═══════════ ЗОНА 5: НИЗ ═══════════════════  │ ← 10% высоты, вся ширина
│  (зарезервировано / в TTS: громкость −)        │
├───────────────────────────────────────────────┤
│  ═══════════ ПРОГРЕСС-БАР ═══════════════════ │ ← фиксированная высота
│  (тап = цикл настроек)                         │  (8 dp / 24 dp)
└───────────────────────────────────────────────┘
```

Зоны **не пересекаются** — вопрос приоритета не возникает. Каждый тап попадает ровно в одну зону.

Проценты — от доступной ширины/высоты контентной области (за вычетом прогресс-бара).

### 7.2. Действия по зонам (режим ЧТЕНИЕ, TTS выкл)

| Зона | Размер | Тап | Результат |
|------|--------|-----|-----------|
| **Зона 1: Верх** | 10% высоты, вся ширина | → | **← Библиотека** |
| **Зона 2: ◄ Лево** | 30% ширины, 70% высоты | → | **← Предыдущая страница** |
| **Зона 3: Центр** | 40% ширины, 70% высоты | → | **TTS: вкл** |
| **Зона 4: ► Право** | 30% ширины, 70% высоты | → | **→ Следующая страница** |
| **Зона 5: Низ** | 10% высоты, вся ширина | → | **(зарезервировано)** |
| **Прогресс-бар** | фикс. высота, вся ширина | → | Цикл настроек |

### 7.3. Действия по зонам (режим ЧТЕНИЕ+TTS, TTS вкл)

Страницы листаются **автоматически**. Механизм см. 7.6.

| Зона | Размер | Тап | Результат |
|------|--------|-----|-----------|
| **Зона 1: Верх** | 10% высоты, вся ширина | → | **← Библиотека** |
| **Зона 2: ◄ Лево** | 30% ширины, 70% высоты | → | **TTS скорость −** (0.75× → 0.5×) |
| **Зона 3: Центр** | 40% ширины, 70% высоты | → | **TTS: выкл** (возврат к чтению) |
| **Зона 4: ► Право** | 30% ширины, 70% высоты | → | **TTS скорость +** (1.0× → 1.25×) |
| **Зона 5: Низ** | 10% высоты, вся ширина | → | **Громкость −** |
| **Прогресс-бар** | фикс. высота, вся ширина | → | Цикл настроек |

### 7.4. Зарезервированные зоны (на будущее)

| Зона | Будет использована для |
|------|----------------------|
| **Зона 5 (Низ)** в режиме ЧТЕНИЕ | Оглавление / навигация по главам (свайп вверх = следующая глава) |
| **Зона 2 (◄ Лево)** в режиме TTS | Перемотка назад по главам (при добавлении TOC) |
| **Зона 4 (► Право)** в режиме TTS | Перемотка вперёд по главам |
| Двойной тап по центру | Закладки (post-MVP) |

Сейчас все зарезервированные зоны **не имеют эффекта**. При добавлении новых фич они активируются без изменения существующих жестов.

### 7.5. Пагинатор — разбивка текста на страницы

**⚠️ Измерение текста:** Используется `StaticLayout` (Android SDK) — thread-safe,
работает на `Dispatchers.Default`. Compose-стили конвертируются в `TextPaint` один раз
при создании пагинатора. Inline-форматирование (жирный/курсив) при измерении игнорируется
для скорости; `AnnotatedString` отдаётся в UI как есть, Compose сам применит SpanStyle.

**Контракт между парсерами и пагинатором:**

```kotlin
// reader-engine — интерфейс
interface BookParser {
    suspend fun parse(file: File): DocumentModel
}

data class DocumentModel(
    val bookId: Long,
    val title: String,
    val author: String,
    val paragraphs: List<ParagraphBlock>,
    val totalChars: Int,
)

data class ParagraphBlock(
    val annotatedText: AnnotatedString,
    val globalCharStart: Int,
    val globalCharEnd: Int,
    val images: List<BookImage> = emptyList(),
)

data class BookImage(
    val bytes: ByteArray? = null,   // загруженные данные (v1 — только если есть)
    val url: String? = null,        // отложенная загрузка (post-MVP)
    val altText: String = "",
)
```

```kotlin
// Пагинатор работает на Dispatchers.Default, не блокирует UI
class Paginator(
    private val document: DocumentModel,
    private val pageWidthPx: Float,   // в пикселях, переведено из Compose заранее
    private val pageHeightPx: Float,
    private val textPaint: TextPaint, // сконвертирован из TextStyle+Density на UI-треде
) {
    private val pageCache = mutableMapOf<Int, Page>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    data class Page(
        val blocks: List<ParagraphBlock>,
        val charOffsetStart: Int,
        val charOffsetEnd: Int,
    )

    suspend fun getPage(n: Int): Page { ... }
    suspend fun findPageByCharOffset(charOffset: Int): Int { ... }

    private fun measureParagraph(block: ParagraphBlock): StaticLayout {
        val text = block.annotatedText.text
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, pageWidthPx.toInt())
            .setLineSpacing(0f, 1.0f)
            .setBreakStrategy(android.text.Layout.BreakStrategy.SIMPLE)
            .build()
    }
}
```

**⚠️ Lifecycle пагинатора:** Внутри пагинатора собственный `CoroutineScope(Dispatchers.Default + SupervisorJob())`. 
При уничтожении ViewModel читалки **обязательно вызывать `paginator.cancel()`**, иначе фоновые
задачи предрасчёта страниц продолжат висеть после выхода из книги — утечка корутин + лишняя
нагрузка на CPU.

**Изображения в тексте:** для v1 — placeholder (серый прямоугольник). Загрузка — отложена до post-MVP. Блок с `images.isNotEmpty()` занимает фиксированную высоту (100dp) и рендерится как `Box(Modifier.background(Color.Gray).height(100.dp))` при отсутствии `bytes`.

**Стратегия:**
- При открытии книги: рассчитать первые 20 страниц на фоне (~50 мс). Показывать спиннер.
- При перелистывании: страница из кэша → мгновенно. Если нет → расчёт на фоне.
- При изменении шрифта/размера: очистить кэш, пересчитать заново.
- **Сохраняется `char_offset`**, не номер страницы (см. §4.3).
- При повторном открытии: `paginator.findPageByCharOffset(savedCharOffset)`.
- `Density` и `TextStyle` передаются из UI-контекста в конструктор пагинатора при создании (через ViewModel / Factory).

### 7.6. TTS — авто-листание страниц

#### Для синтезированной речи (Android TTS)

**Механизм точный, без приближений:**

1. При включении TTS берётся текст **текущей страницы** (массив блоков/абзацев).
2. Весь текст страницы склеивается в одну строку (из `AnnotatedString.text` — plain text без SpanStyle).
3. Вызывается `tts.speak(text, QUEUE_FLUSH, null, "page_<N>")`.
4. Регистрируется `OnUtteranceProgressListener`:
   - `onDone("page_<N>")` → страница завершена → **автоматически перелистнуть** на следующую страницу.
   - Если это была последняя страница — TTS выключается.
5. При ручном перелистывании (тап ◄/►) → `tts.stop()` + запуск с новой страницы.
6. При выключении TTS → `tts.stop()`.

**Плюсы**: точность 100%, не нужно вычислять длительность, не нужно синхронизировать.

#### Для готовой озвучки (knigavuhe MP3)

Здесь концепция «страницы» к аудио **не применяется**. Плеер работает с **треками** — MP3-файлами, по одному на главу/часть. Авто-листание = Media3 автоматически воспроизводит следующий трек после завершения текущего.

Для привязки к тексту (v2) потребуется forced alignment, что отложено до post-MVP.

### 7.7. Прогресс-бар (нижняя панель)

**В режиме чтения:**
- Тонкая полоса (8 dp)
- `████░░░ 45%`
- Тап → вход в цикл настроек

**В режиме настроек:**
- Полоса утолщается (24 dp)
- Слева `[≡≡]` (3 чёрточки — индикатор настроек)
- Подпись: что настраивается
- Тап → следующий шаг цикла
- **Зоны тапа читалки временно меняют назначение**: они управляют активной настройкой вместо навигации (см. таблицу ниже). Как только пользователь выходит из настроек (шаг 4), зоны возвращаются к обычным функциям (§7.2–7.3).

**Цикл настроек читалки** (свой, не библиотечный):

| Шаг | Надпись | Зона ◄ | Зона ► | Зона ▲ | Зона ▼ |
|-----|---------|--------|--------|--------|--------|
| **1. Цвет фона** | `Фон: ◄►цвет  ▲▼ярк` | пред. цвет | след. цвет | яркость + | яркость − |
| **2. Цвет текста** | `Текст: ◄►цвет  ▲▼ярк` | пред. цвет | след. цвет | яркость + | яркость − |
| **3. Шрифт + размер** | `Шрифт: ◄►гарн  ▲▼разм` | пред. шрифт | след. шрифт | размер + | размер − |
| **4. ➤ Чтение** | `чтение` | — | — | — | — |

- Шаг 4 = выход из настроек, возврат к тонкому прогресс-бару. Зоны тапа восстанавливают функции из §7.2.
- Зона Центр и Прогресс-бар сохраняют свои функции (TTS toggle и цикл) в любом режиме.
## 8. Библиотека — контракт

### 8.1. Макет экрана

```
┌───────────────────────────────────────────────┐
│  🔍 _____________________   [Авторы ▼]        │ ← верхняя панель
│  (клавиатура всегда открыта)  тап = цикл      │
├───────────────────────────────────────────────┤
│                                               │
│          КОНТЕНТ (зависит от режима)           │
│                                               │
│  Авторы → список авторов (скролл)            │
│  Названия → произведения (скролл, групп.      │
│              по сериям)                       │
│                                               │
├───────────────────────────────────────────────┤
│  ═══ [≡≡] Авторы: по популярности ════════  │ ← нижняя панель
│  тап = след. режим                            │
└───────────────────────────────────────────────┘
```

### 8.2. Верхняя панель

Две зоны:

| Зона | Действие | Результат |
|------|----------|-----------|
| 🔍 Поле поиска | Ввод текста | Фильтрация списка в реальном времени |
| **[Авторы ▼]** | Тап | Цикл: **Авторы → Названия → Авторы → …** |

- **Клавиатура**: автоматически открывается при входе в библиотеку (фокус на поиске). Пользователь может закрыть клавиатуру системной кнопкой «назад» или свайпом вниз. При начале скролла списка — клавиатура автоматически скрывается (фокус снимается).
- **imePadding() на нижней панели**: панель библиотеки всегда видна над клавиатурой. Список скроллируется под клавиатурой (не через imePadding, а через systemBars).
- Плейсхолдер поиска: `"Поиск авторов…"` / `"Поиск по названию…"`.
- Фильтрация: FTS5 (MATCH) + debounce 300 мс. Первые 3 символа — `'запрос*'` (prefix search в FTS5).

### 8.3. Режимы контента

#### Режим «Авторы»

```
  Достоевский Фёдор Михайлович
    Преступление и наказание, Идиот

  Толстой Лев Николаевич
    Война и мир, Анна Каренина  🎧

  Пушкин Александр Сергеевич
    Евгений Онегин, Капитанская дочка

  Булгаков Михаил Афанасьевич   🎧
    Мастер и Маргарита, Собачье сердце
```

- Имя автора.
- Под именем — 2 самые рейтинговые книги.
- 🎧 если у автора есть аудиокниги на knigavuhe.
- Тап по автору → **режим «Названия», отфильтрованный по этому автору**.

#### Режим «Названия»

```
  📚 ХРОНИКИ ДРЕЗДЕНА
  ├─ 1. Гроза из преисподней       🎧 ★★★★
  ├─ 2. Луна светит безумным            ★★★
  ├─ 3. Могила в подарок           🎧 ★★★
  ├─ 4. Летний рыцарь              🎧 ★★★
  └─ 5. Маска смерти                   ★★★

  📚 ПЕСНЬ ЛЬДА И ПЛАМЕНИ
  ├─ 1. Игра престолов             🎧 ★★★★★
  ├─ 2. Битва королев                  ★★★★
  └─ 3. Буря мечей                 🎧 ★★★★

  1984                              🎧 ★★★★
  Война и мир                       🎧 ★★★★
  Преступление и наказание               ★★★★
```

- **Серии**: заголовок + книги с `series_num`. Группировка всегда, независимо от сортировки.
- **Без серии**: просто строки, без заголовка «Без серии», без иконки 📚.
- Тап → **установка книги текущей**:
  1. Скачать FB2/EPUB в кэш (из flibusta)
  2. Если `has_audio` → запрос knigavuhe (1–2 сек) → начать загрузку MP3
  3. Открыть читалку (текст) или плеер (если только аудио)
- **Свайп книги влево**: удалить из кэша.

### 8.4. Нижняя панель (прогресс-бар библиотеки)

Работает аналогично читалке. Полный цикл режимов (тап по панели = следующий шаг):

| Шаг | Надпись | Зона ◄ | Зона ► | Зона ▲ | Зона ▼ |
|-----|---------|--------|--------|--------|--------|
| **1. Сортировка авторов** | `Авторы: по популярности` | пред. поле | след. поле | — | — |
| **2. Сортировка названий** | `Назв: по популярности` | пред. поле | след. поле | — | — |
| **3. Фильтр жанров** | `Жанры: выбрано 2` | (через чекбоксы — см. ниже) | | |
| **4. Цвет фона** | `Фон: ◄►цвет  ▲▼ярк` | пред. цвет | след. цвет | яркость + | яркость − |
| **5. Цвет текста** | `Текст: ◄►цвет  ▲▼ярк` | пред. цвет | след. цвет | яркость + | яркость − |
| **6. Шрифт + размер** | `Шрифт: ◄►гарн  ▲▼разм` | пред. шрифт | след. шрифт | размер + | размер − |

**Важно**: в режиме просмотра библиотеки (список авторов или названий) **тапы не работают** — работает только скролл пальцем. Зоны тапа (◄►▲▼) активируются **только когда пользователь вошёл в режим настройки** (тап по прогресс-бару). В режиме настройки зоны работают идентично читалке (§7.1): 5 зон + прогресс-бар.

**Шаг 3 — фильтр жанров**:
- Вместо списка книг — показываются чекбоксы всех 22 жанров flibusta (табличка §14.1).
- Пользователь отмечает нужные жанры (тап по строке = переключение чекбокса).
- Выбранные жанры фиксируются в фильтре.
- При переключении на Авторы/Названия — список фильтруется по выбранным жанрам.
- Если фильтр пуст (ничего не выбрано) — показывается всё.

Нижняя панель **не перекрывается клавиатурой** (через `imePadding()` в Compose).

---

## 9. Стратегия обновления каталога

| Действие | Периодичность | Механизм |
|----------|---------------|----------|
| Новинки flibusta | При каждом запуске + WorkManager раз/день | OPDS `/opds/new/0/new`, постранично (100 за раз) |
| has_audio (knigavuhe) | Раз в неделю | Для книг без флага — проверка по (title, author) |
| Полный рескрап | По запросу пользователя | Обход всех жанров через OPDS |

Все обновления — в фоне, низкий приоритет, без нагрузки на пользователя.

---

## 10. План реализации (фазы)

### Фаза 1: База + библиотека (v0.1)

- [ ] Создание Android-проекта (Gradle, модули, Koin DI)
- [ ] SQLite-схема: books + history + settings. Подготовка shipped DB (топ-5000)
- [ ] FlibustaProvider: OPDS-обход, парсинг HTML страницы книги
- [ ] KnigavuheMatcher: поиск аудио по (title, author), извлечение URL
- [ ] Загрузчик книг: скачивание FB2/EPUB в кэш
- [ ] UI библиотеки: верхняя панель (поиск + цикл авторы/названия)
- [ ] UI: список авторов (скролл, поиск, сортировка)
- [ ] UI: список названий (группировка по сериям, 🎧, ★)
- [ ] UI: нижняя панель (цикл: сортировка, жанры, цвета, шрифт)
- [ ] UI: экран жанров (чекбоксы 22 жанров flibusta)
- [ ] DataStore: сохранение настроек (шрифт, размер, цвета)

### Фаза 2: Читалка (v0.2)

- [ ] BookParser интерфейс + FB2/EPUB парсеры на TextLector (Apache 2.0)
- [ ] Пагинатор: разбивка текста на страницы (async, char_offset, Density из UI)
- [ ] UI читалки: зоны тапа (◄ ► ▲ ▼ центр + прогресс-бар)
- [ ] Пагинация: перелистывание вперёд/назад
- [ ] TTS: Android TextToSpeech API, play/pause, автоматическое перелистывание
- [ ] Сохранение прогресса: (book_id, char_offset) → history
- [ ] Восстановление при запуске: последняя книга → last_page

### Фаза 3: Аудиоплеер (v0.3)

- [ ] Загрузчик MP3 (с прогрессом, докачкой)
- [ ] Плеер на Media3 (плэйлист треков, скорость)
- [ ] UI плеера: обложка, треки, прогресс
- [ ] Фоновое воспроизведение (MediaSession)

**⚠️ Android 14 (API 34): обязательные требования для фонового аудио:
```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".service.AudioPlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```
- Без `POST_NOTIFICATIONS` (Android 13+) ОС запретит запуск Foreground Service.
- Без `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (Android 14+) сервис упадёт с `ForegroundServiceDidNotStartInTimeException`

**Очистка кэша MP3 (LRU):** аудиокниги весят 200 МБ – 1.5 ГБ. При инициализации плеера
проверять свободное место. Если < 1 ГБ — удалять MP3-файлы книг, не открывавшихся > 30 дней.
Механизм: WorkManager раз в сутки, или при старте плеера.

**Гарнитура (Bluetooth Media Buttons):** Media3 MediaSession из коробки обрабатывает
одинарное нажатие (play/pause). Для двойного/тройного нажатия (next/prev трек) необходимо
убедиться, что `MediaSession.Callback` корректно обрабатывает `onSkipToNext()`/`onSkipToPrevious()`
и что в `PlaybackState` выставлены `state actions`:
```kotlin
player.playbackState.apply {
    stateActions += PlaybackStateCompat.ACTION_SKIP_TO_NEXT
    stateActions += PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
}
```
Без этого двойное нажатие будет игнорироваться ОС.

### Фаза 4: Поиск + полировка (v0.4)

- [ ] Полнотекстовый поиск по библиотеке
- [ ] Фильтрация по рейтингу
- [ ] Экран истории

---

## 11. Post-MVP

- [ ] Оглавление / навигация по главам
- [ ] Закладки
- [ ] Android Auto
- [ ] Экспорт/импорт истории
- [ ] AI-озвучка (Piper / Kokoro) — если RHVoice не устраивает по качеству

---

## 12. Зависимости

**Единый version catalog**: `gradle/libs.versions.toml`. Все версии библиотек — там.

| Библиотека | Назначение | Лицензия |
|-----------|------------|----------|
| OkHttp | HTTP-клиент (OPDS, DoH, ExoPlayer) | Apache 2.0 |
| Ksoup | HTML-парсинг | MIT |
| Room | SQLite | Apache 2.0 |
| Koin | DI | Apache 2.0 |
| Media3 | аудиоплеер | Apache 2.0 |
| Coil | обложки | Apache 2.0 |
| DataStore | настройки | Apache 2.0 |
| Okio | файловый I/O | Apache 2.0 |
| WorkManager | фоновые задачи | Apache 2.0 |
| TextLector parsers | FB2/EPUB (Apache 2.0) | Apache 2.0 |
| AndroidX Compose | UI | Apache 2.0 |

---

## 13. Поведение при тапе по книге в библиотеке

### has_audio = 0 (только текст)

| Действие | Результат |
|----------|-----------|
| Тап по строке книги | Скачать FB2/EPUB в кэш → открыть читалку |

### has_audio = 1 (есть озвучка)

| Действие | Результат |
|----------|-----------|
| **Тап по строке книги** | **Раскрыть запись** (анимация): показать список чтецов + кнопку `[📖 Читать]` |
| Тап по **имени чтеца** | Загрузить MP3 (knigavuhe on-demand) → сразу открыть плеер |
| Тап по **[📖 Читать]** | Скачать FB2/EPUB в кэш → открыть читалку |
| Тап по **заголовку книги** (в раскрытом виде) | Скачать FB2/EPUB в кэш → открыть читалку |
| Тап по **свернутой книге** (повторно, если уже раскрыта) | Свернуть обратно |

### Раскрытая запись (пример)

```
  Преступление и наказание          🎧 ★★★★
  Ф. Достоевский
        ▼
  ┌─────────────────────────────────────────┐
  │  🎧 Чтецы:                              │
  │  ○ Иван Петров                 14ч 23м  │ → тап = плеер
  │  ○ Елена Соколова             15ч 01м   │
  │  ○ Алексей Козлов             13ч 55м   │
  │                                         │
  │  [📖 Читать]                            │ → тап = читалка
  └─────────────────────────────────────────┘
```

### Кэширование чтецов

- При первом раскрытии книги: GET-запрос к knigavuhe → парсинг HTML → список `(имя, длительность, mp3_urls[] )`.
- Сохраняется в in-memory кэш (`Map<bookId, List<Narrator>>`) на время сессии.
- Повторное раскрытие той же книги — мгновенно, без запроса.
- При перезапуске приложения кэш пуст.

Никаких долгих тапов. Только обычный тап. При has_audio=1 — всегда раскрытие, а не мгновенный переход.

---

## 14. API провайдеров — спецификация для Coder

### 14.1. Flibusta OPDS — обход каталога

#### Точки входа

| URL | Формат | Описание |
|-----|--------|----------|
| `http://flibusta.is/opds/` | OPDS (Atom/XML) | Корень каталога. Содержит ссылки на жанры, авторов по буквам, новинки |
| `http://flibusta.is/opds/new/0/new?offset=N` | OPDS (Atom/XML) | Новинки. N = 0, 100, 200… (пагинация по 100) |
| `http://flibusta.is/opds/genre/<id>/` | OPDS (Atom/XML) | Книги жанра (пагинировано) |
| `http://flibusta.is/opds/search?searchType=books&searchTerm={q}` | OPDS (Atom/XML) | Поиск по книгам |
| `http://flibusta.is/opds/search?searchType=authors&searchTerm={q}` | OPDS (Atom/XML) | Поиск по авторам |
| `http://flibusta.is/b/<id>/` | HTML | Страница книги. Парсинг рейтинга, ссылок на скачивание |

#### OPDS Entry (парсинг)

```xml
<entry>
  <id>urn:flibusta:book:12345</id>           <!-- id = 12345 -->
  <title>Война и мир</title>
  <author>
    <name>Толстой Лев Николаевич</name>
    <uri>http://flibusta.is/opds/author/6789</uri>
  </author>
  <category label="Роман" scheme="..." term="roman"/>
  <content type="text">Аннотация книги...</content>
  <link href="/opds/author/6789" rel="related" type="application/atom+xml;profile=opds-catalog"/>
  <link href="/b/12345/fb2" rel="http://opds-spec.org/acquisition" type="application/fb2+zip"/>
  <link href="/b/12345/epub" rel="http://opds-spec.org/acquisition" type="application/epub+zip"/>
  <published>2024-01-15T00:00:00Z</published>
  <updated>2024-06-20T00:00:00Z</updated>
</entry>
```

**Что извлекать из entry:**
- `id`: число после "flibusta:book:" (regex: `book:(\d+)`)
- `title`: название
- `author`: из `<author><name>` (первые буквы Фамилия Имя)
- `genre`: из `<category term="...">` (есть и русская метка в `label`)
- `annotation`: из `<content type="text">`
- `has_fb2`: есть ли `<link ... type="application/fb2+zip">`
- `has_epub`: есть ли `<link ... type="application/epub+zip">`
- `created_at`: из `<published>`
- `updated_at`: из `<updated>`

#### HTML-страница книги (`/b/<id>/`)

Парсинг для рейтинга:

```html
<!-- Оценки пользователей в комментариях -->
<div class="book-rating">
  <span class="vote-up">отлично!</span> (<span class="count">12</span>)
  <span class="vote-good">хорошо</span> (<span class="count">8</span>)
  <span class="vote-normal">неплохо</span> (<span class="count">3</span>)
  <span class="vote-bad">плохо</span> (<span class="count">1</span>)
  <span class="vote-terrible">нечитаемо</span> (<span class="count">0</span>)
</div>

<!-- Или список комментариев с оценками -->
<div class="comment">
  <span class="comment-rating">5</span>  <!-- или 4, 3, 2, 1 -->
  ...
</div>
```

**Формула рейтинга:**
```
rating = (5 * отлично + 4 * хорошо + 3 * неплохо + 2 * плохо + 1 * нечитаемо)
         / (отлично + хорошо + неплохо + плохо + нечитаемо)
```

Результат 0.0–5.0. Если голосов нет → `votes_count = 0`, `rating = 0.0`.

**Ссылки на скачивание:**
```html
<a href="/b/12345/fb2" title="FB2">FB2</a>
<a href="/b/12345/epub" title="EPUB">EPUB</a>
```

Полный URL для скачивания: `http://flibusta.is/b/12345/fb2`

#### 22 жанра flibusta (список)

Жанры извлекаются из корневого OPDS-каталога. Статический список для shipped DB:

| term | label |
|------|-------|
| prose | Проза |
| detective | Детектив |
| fantasy | Фэнтези |
| sci-fi | Научная фантастика |
| horror | Ужасы и Мистика |
| adventure | Приключения |
| romance | Любовный роман |
| thriller | Триллер |
| poetry | Поэзия |
| drama | Драматургия |
| humor | Юмор |
| child | Детская |
| history | История |
| religion | Религия |
| philosophy | Философия |
| psychology | Психология |
| technical | Техническая |
| reference | Справочная |
| nonfiction | Документальная / Публицистика |
| biography | Биография |
| military | Военная |
| classic | Классика |

Если книга не имеет жанра или жанр неизвестен — `genre = "unknown"`.

### 14.2. Knigavuhe — поиск аудио и извлечение чтецов

#### Поиск книги по (title, author)

**GET** `https://knigavuhe.org/search/?q=<title>+<author>`

Параметры:
- `q` — строка поиска. URL-encode. Пример: `q=преступление+и+наказание+достоевский`

HTML-ответ, парсинг:

```html
<div class="search-results">
  <div class="book-item">
    <a href="/book/prestuplenie-i-nakazanie/">
      <img src="/uploads/covers/prestuplenie.jpg" alt="Преступление и наказание">
      <span class="book-title">Преступление и наказание</span>
      <span class="book-author">Фёдор Достоевский</span>
      <span class="book-reader">Иван Петров</span>
    </a>
  </div>
  <!-- ещё результаты -->
</div>
```

Из каждого результата извлекается:
- `slug` из `href="/book/<slug>/"` (часть URL между `/book/` и `/`)
- `title`, `author` для сверки с запрошенной книгой

**Алгоритм матчинга:**
1. Привести title и author к lowercase, убрать пунктуацию.
2. Сравнить с результатами поиска. If совпало — взять slug.
3. Если не совпало → has_audio = 0 (ложное срабатывание на другую книгу).

#### Извлечение чтецов и MP3

**GET** `https://knigavuhe.org/book/<slug>/`

HTML-ответ, парсинг:

```html
<div class="book-page">
  <h1>Преступление и наказание</h1>
  <div class="book-meta">
    <span class="author">Фёдор Достоевский</span>
  </div>

  <!-- Список чтецов (может быть несколько) -->
  <div class="readers-list">
    <div class="reader-item" data-reader-id="123">
      <span class="reader-name">Иван Петров</span>
      <span class="reader-duration">14:23:00</span>
    </div>
    <div class="reader-item" data-reader-id="456">
      <span class="reader-name">Елена Соколова</span>
      <span class="reader-duration">15:01:00</span>
    </div>
  </div>

  <!-- MP3 треки в data-атрибутах (скрыты в JS) -->
  <script>
    var audioFiles = {
      "123": [
        {"url": "/uploads/audio/prestuplenie/01.mp3", "title": "Глава 1", "duration": "45:12"},
        {"url": "/uploads/audio/prestuplenie/02.mp3", "title": "Глава 2", "duration": "38:45"},
        ...
      ],
      "456": [
        {"url": "/uploads/audio/prestuplenie_es/01.mp3", "title": "Глава 1", "duration": "47:30"},
        ...
      ]
    };
  </script>
</div>
```

**Что извлекается:**
- Для каждого `.reader-item`:
  - `name`: текст `.reader-name`
  - `duration_raw`: текст `.reader-duration` (HH:MM:SS)
  - `duration_sec`: парсинг в секунды
  - `reader_id`: `data-reader-id`
- Из JavaScript-переменной `audioFiles`:
  - для каждого `reader_id` → массив треков с `url`, `title`, `duration`
  - URL: дополняется до `https://knigavuhe.org<url>`

#### Формат возврата (API для Coder)

```kotlin
data class NarratorInfo(
    val name: String,
    val durationSeconds: Long,
    val tracks: List<TrackInfo>
)

data class TrackInfo(
    val title: String,
    val url: String,        // полный URL MP3
    val durationSeconds: Long
)

// Возврат
fun fetchNarrators(bookId: Long, title: String, author: String): List<NarratorInfo>
```

#### Кэш чтецов (in-memory)

```kotlin
// В ViewModel или синглтоне
object NarratorCache {
    private val cache = ConcurrentHashMap<Long, List<NarratorInfo>>()  // thread-safe

    suspend fun get(bookId: Long, title: String, author: String): List<NarratorInfo> {
        cache[bookId]?.let { return it }
        val narrators = KnigavuheParser.fetchNarrators(bookId, title, author)
        cache[bookId] = narrators
        return narrators
    }

    fun clear() { cache.clear() }
}
```

#### ExoPlayer: защита от Hotlinking (Knigavuhe)

Серверы Knigavuhe блокируют стриминг MP3 без правильных заголовков (403 / 406).

```kotlin
val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
    .setDefaultRequestProperties(mapOf("Referer" to "https://knigavuhe.org/"))

val player = ExoPlayer.Builder(context)
    .setMediaSourceFactory(
        DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)
    )
    .build()
```

Без этого плеер получит 403 Forbidden.

### 14.3. Подготовка shipped DB (топ-5000)

**Скрипт** (отдельный, не часть Android-проекта):
- Язык: Python или Kotlin JVM
- Обход flibusta OPDS: жанр за жанром, сортировка по рейтингу
- Выбор топ-5000 книг (по rating, без голосов — пропускать)
- Для каждой — проверка knigavuhe (has_audio)
- Запись в SQLite, VACUUM, сжатие
- Результат: `voxli_seed.db` → `app/src/main/assets/databases/`

**⚠️ Критично: схема seed.db должна в точности совпадать со схемой Room.**
Room при `createFromAsset()` сверяет хэш схемы с `room_master_table`. Если таблица `books_fts`
создана вручную без учёта shadow-таблиц Room (`books_fts_data`, `books_fts_idx`,
`books_fts_docsize`, `books_fts_config`), Room выбросит:
```
IllegalStateException: Pre-packaged database has an invalid schema.
```
**Решение**: Сначала собрать проект и дать Room сгенерировать DDL (файл
`app/build/generated/ksp/debug/resources/schemas/.../1.json`). Скрипт генерации seed.db
должен использовать **точно те же** CREATE TABLE / CREATE INDEX / CREATE TRIGGER, что в
этом JSON. После чего `VACUUM` и копирование в assets.

**ℹ️ Чтобы Room сгенерировал JSON схемы**, в `app/build.gradle.kts` нужно добавить:
```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```
Без этого аргумента папка `schemas` будет пуста, и скрипт не сможет сверить DDL.

Room при первом запуске копирует `assets/databases/voxli_seed.db` → `databases/voxli.db`.
Далее — инкрементальные обновления через OPDS new/since.

### 14.4. Инкрементальное обновление БД (для Coder)

```kotlin
// WorkManager Worker
class CatalogUpdateWorker : CoroutineWorker() {
    override suspend fun doWork(): Result {
        // 1. OPDS /opds/new/0/new?offset=0, 100, 200...
        //    Проверять по id: если книги нет в локальной БД → INSERT
        //                        если есть → UPDATE (rating, has_fb2, has_epub)
        //    Новинок ~770 в неделю. За раз — 100, на всё — 7-8 запросов.
        // 2. Раз в неделю:
        //    Для книг с has_audio=0 (или где has_audio не проверялся давно)
        //    → Knigavuhe match → UPDATE has_audio
        return Result.success()
    }
}
```


---

## 15. Сетевой слой — устойчивость к блокировкам

### 15.1. Зеркала Flibusta

При старте приложения проверяется доступность списка зеркал. Используется первое доступное.

| Зеркало | Тип |
|---------|-----|
| `http://flibusta.is` | Основной домен |
| `http://flibusta.site` | Альтернативный домен |
| `http://flibusta.net` | Запасной |

При недоступности текущего зеркала — автоматическое переключение на следующее из списка (с сохранением выбора в DataStore).

**Cleartext (HTTP):** Android 9+ блокирует HTTP. Добавить разрешение в `res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">flibusta.is</domain>
        <domain includeSubdomains="true">flibusta.site</domain>
        <domain includeSubdomains="true">flibusta.net</domain>
        <domain includeSubdomains="true">knigavuhe.org</domain>
    </domain-config>
</network-security-config>
```

**Смена зеркал:** домены блокируются каждый месяц. Хардкод в APK требует пересборки.
Решение: активный список зеркал получать из удалённого fallback (raw-файл GitHub Gist или JSON на надёжном хостинге).
При каждом запуске — HEAD-запрос к fallback-источнику. Если ответ 200 → обновить список зеркал в DataStore.

### 15.2. DNS-over-HTTPS (DoH)

OkHttp имеет официальную поддержку `DnsOverHttps`. Ktor не используется — все HTTP-запросы
через OkHttp напрямую:

```kotlin
val dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
}
```

```kotlin
val appCache = Cache(File(context.cacheDir, "http_cache"), 10L * 1024L * 1024L)
val bootstrapClient = OkHttpClient.Builder().cache(appCache).build()

val dns = DnsOverHttps.Builder()
    .client(bootstrapClient)
    .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
    .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
    .build()

val okHttpClient = OkHttpClient.Builder()
    .dns(dns)
    .build()

// (Ktor не используется — OkHttp напрямую)
// Для OPDS-запросов: okHttpClient.newCall(request).execute().body?.string()
```

Зависимость: добавить `okhttp-dnsoverhttps` в build.gradle. Это обходит блокировки DNS на уровне провайдера без настройки VPN/прокси.

### 15.3. User-Agent и заголовки

```kotlin
install(HttpHeaders) {
    defaultRequest {
        header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        header("Accept", "text/html,application/xml,application/atom+xml,*/*")
    }
}
```

### 15.4. Прокси (опционально, post-MVP)

- Поддержка HTTP/SOCKS5-прокси через настройки приложения.
- Интеграция с Orbot (Tor) через `SOCKS5 127.0.0.1:9050` — для регионов с блокировкой по IP.
- Определяется автоматически по таймаутам запросов.