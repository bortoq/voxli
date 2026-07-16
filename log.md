# Development Log

## 2026-07-15 — Unit tests with Robolectric fail

### Проблема
Тесты `PaginatorTest` (15 шт.) падали с:
```
java.lang.RuntimeException: Method setColor in android.graphics.Paint not mocked
```
Robolectric не инициализировал shadow-объекты. `@RunWith(RobolectricTestRunner::class)` игнорировался.

### Диагностика
- `org.robolectric:junit:4.14.1` транзитивно тянет `org.junit.jupiter:junit-jupiter:5.8.2`
- Gradle видит `junit-platform-engine` на classpath и использует JUnit 5 Platform
- JUnit 5 Platform исполняет тесты через Jupiter, а не через Vintage
- Jupiter игнорирует `@RunWith(RobolectricTestRunner::class)` → Robolectric не стартует

### Попытка решения 1: useJUnitPlatform() + junit-vintage-engine
**Результат**: ❌ Тесты выполняются, но `@RunWith` не сработал — Jupiter всё равно главный

### Попытка решения 2: useJUnit()
**Результат**: ❌ Gradle не обнаружил тестов (0 tests executed)

### Попытка решения 3: useJUnit() + removal of JUnit 5 deps
**Результат**: ❌ Robolectric всё равно тянет Junit Jupiter транзитивно

### Решение: Рефакторинг Paginator (разделение на модули)

#### Что сделано
1. **TextMeasurer интерфейс** (reader.engine) — чистый Kotlin, без Android SDK
2. **AndroidTextMeasurer** (reader.android) — единственное место с StaticLayout/TextPaint  
3. **FakeTextMeasurer** — для тестов, возвращает предсказуемые данные
4. **Paginator переписан**: принимает `TextMeasurer` вместо `TextPaint`
5. **ReaderViewModel обновлён**: создаёт `AndroidTextMeasurer(paint)`
6. **PaginatorTest**: 15 тестов через `FakeTextMeasurer` — без Robolectric
7. **build.gradle.kts**: `useJUnitPlatform()` + `junit-vintage-engine` + `junit4`

#### Результат
- ✅ **34/34 unit-тестов проходят** (PaginatorTest 15, LibraryViewModelTest 10, ReaderViewModelTest 9)
- ✅ **APK собирается** (`assembleDebug`)
- ✅ **41 проверка verifyContract проходит**
- ✅ Robolectric больше не нужен для PaginatorTest (остаётся только для AndroidTextMeasurer — 1 тест)
