# Встроенная читалка · этап 1 (читалка без озвучки) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** В MyTTS появляется своя читалка: импорт EPUB / FB2 / FB2.ZIP / MOBI / AZW3 / CBZ, библиотека, чтение с листанием, оглавлением, прогрессом, местом чтения и настройками вида — на движке foliate-js в WebView.

**Architecture:** foliate-js (MIT, закреплённый коммит) лежит в `assets/reader/foliate/`; наша страница `reader.html` + `reader-bridge.js` создаёт `<foliate-view>`. WebView грузит всё с `https://appassets.androidplatform.net` через `WebViewAssetLoader` (ресурсы и файл книги), сеть запрещена. JS ↔ Kotlin: `WebViewCompat.addWebMessageListener` (только наш origin) и `evaluateJavascript`. Книги и место чтения — SQLite `files/reader.db`, файлы — `files/library/<sha256>.<ext>`. Интерфейс — Compose.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Android WebView + `androidx.webkit:webkit:1.12.1`, foliate-js `78914aef4466eb960965702401634c2cb348e9b1`, org.json, SQLite. Тесты: JVM (Gradle `testDebugUnitTest` на CI, локально раннер из `scratchpad/kt`), JS — `node --test` (Node 25).

**Spec:** `docs/superpowers/specs/2026-10-12-built-in-reader-design.md` (этап 1 + раздел «Загрузка и безопасность», «Библиотека», «Экран чтения»).

## Global Constraints

- Пакет `com.davnozdu.supertonic.tts.fork`, namespace кода `com.brahmadeo.supertonic.tts`; новые классы — `com.brahmadeo.supertonic.tts.reader`.
- Интерфейс на русском; скриншоты при проверке не делать — только XML-дамп интерфейса и логи.
- APK собирается только на GitHub (CI / Release); локально — JVM- и JS-тесты.
- foliate-js — только файлы из списка задачи 1, без `pdf.js`/`vendor/pdfjs` (PDF не в первой версии); `LICENSE` и коммит рядом.
- WebView: JavaScript только для `appassets.androidplatform.net`; любой другой адрес — ответ 403; `allowFileAccess=false`, `allowContentAccess=false`.
- Импорт: файл ≤ 100 МБ; формат определяется по содержимому, не по расширению.
- Moon+ / Readest и движок MyTTS не меняются.
- Версия: следующая бета `5.0.4-beta16`, `versionCode` +1 (сейчас 190).

**Вне этапа 1** (следующие планы): озвучка в читалке и связь с `.mytts-book` (этап 2), поиск по книге, обложки, общий экран «Книги» для читаемых и подготовленных книг, книги со звуком (этап 3).

## Review Focus

- FB2 в кодировке windows-1251 и FB2.ZIP — открываются с правильным текстом (foliate-js сам читает `encoding` из XML-заголовка; FB2.ZIP требует имя `.fb2.zip`).
- Книга, сохранённое место которой (CFI) больше не разрешается (файл заменён другим изданием), открывается с начала текста, а не с ошибкой.
- Файл, который не является книгой (PDF, ZIP с документами, картинка), отклоняется при импорте понятным сообщением и не попадает в библиотеку.
- Повторный импорт того же файла не создаёт дубликат и не сбрасывает место чтения.
- Ссылка внутри книги на внешний сайт не открывается в WebView (запрос блокируется), а сноска внутри книги работает.

---

## File Structure

```
tools/reader/vendor_foliate.sh                     — копирует foliate-js нужного коммита в assets
tools/reader/reader-core.test.mjs                  — node-тесты чистой JS-логики
app/src/main/assets/reader/foliate/…               — foliate-js (вендор) + LICENSE + COMMIT
app/src/main/assets/reader/reader.html             — страница читалки
app/src/main/assets/reader/reader-core.js          — чистые функции (оглавление, метаданные, CSS вида, сообщения)
app/src/main/assets/reader/reader-bridge.js        — связь <foliate-view> ↔ Kotlin
app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderFormat.kt      — определение формата по содержимому
app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderAppearance.kt  — настройки вида (JSON)
app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderStore.kt       — reader.db: книги, место, вид
app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderImport.kt      — копирование, sha256, формат
app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderWeb.kt         — WebView, AssetLoader, мост
app/src/main/java/com/brahmadeo/supertonic/tts/ReaderActivity.kt           — экран чтения
app/src/main/java/com/brahmadeo/supertonic/tts/ReaderLibraryActivity.kt    — библиотека + «Открыть с помощью»
app/src/test/java/com/brahmadeo/supertonic/tts/reader/ReaderFormatTest.kt
app/src/test/java/com/brahmadeo/supertonic/tts/reader/ReaderAppearanceTest.kt
```
Изменяются: `app/build.gradle` (webkit, версия), `AndroidManifest.xml`, `ui/MainScreen.kt`, `MainActivity.kt`, `.github/release-notes.md`, spec (хранение в `reader.db`).

Локальный запуск JVM-тестов (без Gradle): `SP=…/scratchpad/kt; $SP/run.sh "<main.kt> <test.kt>" "<TestClass>"` из `app/src` (раннер компилирует Kotlin 2.0.21 + JUnit-заглушки; org.json — добавить `json-20240303.jar` в `-cp`, как в полном наборе).

---

### Task 1: foliate-js в assets

**Files:**
- Create: `tools/reader/vendor_foliate.sh`
- Create: `app/src/main/assets/reader/foliate/*` (результат скрипта)

**Interfaces:**
- Produces: модуль `assets/reader/foliate/view.js` (экспорт `makeBook`, элемент `foliate-view`), `COMMIT`, `LICENSE`.

- [ ] **Step 1: Скрипт вендоринга**

```bash
#!/bin/bash
# Copies foliate-js (MIT, https://github.com/johnfactotum/foliate-js) at a pinned commit into the app assets.
# PDF (pdf.js) is left out. Usage: tools/reader/vendor_foliate.sh
set -euo pipefail
COMMIT=78914aef4466eb960965702401634c2cb348e9b1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/app/src/main/assets/reader/foliate"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
git -C "$TMP" init -q && git -C "$TMP" fetch -q --depth 1 https://github.com/johnfactotum/foliate-js "$COMMIT" && git -C "$TMP" checkout -q FETCH_HEAD
rm -rf "$OUT"; mkdir -p "$OUT/vendor"
for f in LICENSE view.js paginator.js fixed-layout.js epub.js epubcfi.js fb2.js mobi.js comic-book.js overlayer.js \
         progress.js search.js text-walker.js tts.js footnotes.js uri-template.js; do cp "$TMP/$f" "$OUT/$f"; done
cp "$TMP/vendor/zip.js" "$TMP/vendor/fflate.js" "$OUT/vendor/"
echo "$COMMIT" > "$OUT/COMMIT"
echo "foliate-js $COMMIT -> $OUT ($(du -sh "$OUT" | cut -f1))"
```

- [ ] **Step 2: Запустить и проверить, что `view.js` не тянет исключённые файлы**

Run: `chmod +x tools/reader/vendor_foliate.sh && tools/reader/vendor_foliate.sh && grep -n "import(" app/src/main/assets/reader/foliate/view.js`
Expected: строка с размером ≈ 0,4–0,6 МБ; динамические импорты только `./epub.js`, `./comic-book.js`, `./fb2.js`, `./pdf.js`, `./mobi.js`, `./vendor/fflate.js`, `./vendor/zip.js`, `./tts.js`, `./search.js`, `./footnotes.js` и т.п. `./pdf.js` отсутствует намеренно: PDF вызовет `UnsupportedTypeError` только для PDF, который мы и так отклоняем при импорте (задача 2).

- [ ] **Step 3: Commit**

```bash
git add tools/reader/vendor_foliate.sh app/src/main/assets/reader/foliate
git commit -m "reader: vendor foliate-js 78914aef (MIT) without pdf.js"
```

---

### Task 2: Формат книги по содержимому

**Files:**
- Create: `app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderFormat.kt`
- Test: `app/src/test/java/com/brahmadeo/supertonic/tts/reader/ReaderFormatTest.kt`

**Interfaces:**
- Produces: `enum class ReaderFormat(val ext: String) { EPUB("epub"), FB2("fb2"), FBZ("fb2.zip"), MOBI("mobi"), AZW3("azw3"), CBZ("cbz") }`; `ReaderFormat.detect(file: java.io.File, name: String): ReaderFormat?`. Расширение `ext` используется в URL книги — по нему foliate-js различает FB2/FB2.ZIP/CBZ.

- [ ] **Step 1: Тест**

```kotlin
package com.brahmadeo.supertonic.tts.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReaderFormatTest {
    private fun zip(vararg entries: Pair<String, String>): File = File.createTempFile("book", ".bin").apply {
        deleteOnExit()
        ZipOutputStream(outputStream()).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }
    private fun bytes(b: ByteArray): File = File.createTempFile("book", ".bin").apply { deleteOnExit(); writeBytes(b) }

    @Test fun recognisesBooksByContentNotName() {
        assertEquals(ReaderFormat.EPUB, ReaderFormat.detect(zip("mimetype" to "application/epub+zip", "META-INF/container.xml" to "<x/>"), "a.zip"))
        assertEquals(ReaderFormat.EPUB, ReaderFormat.detect(zip("META-INF/container.xml" to "<x/>"), "book"))
        assertEquals(ReaderFormat.FBZ, ReaderFormat.detect(zip("Идиот.fb2" to "<FictionBook/>"), "idiot.zip"))
        assertEquals(ReaderFormat.CBZ, ReaderFormat.detect(zip("001.jpg" to "x", "002.png" to "y"), "c.zip"))
        assertEquals(ReaderFormat.FB2, ReaderFormat.detect(bytes("<?xml version=\"1.0\" encoding=\"windows-1251\"?>\n<FictionBook xmlns=\"x\">".toByteArray()), "x.txt"))
        assertEquals(ReaderFormat.FB2, ReaderFormat.detect(bytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<FictionBook>".toByteArray(Charsets.UTF_16LE)), "b.fb2"))
        val mobi = ByteArray(80).also { "BOOKMOBI".toByteArray().copyInto(it, 60) }
        assertEquals(ReaderFormat.MOBI, ReaderFormat.detect(bytes(mobi), "x.mobi"))
        assertEquals(ReaderFormat.AZW3, ReaderFormat.detect(bytes(mobi), "x.AZW3"))
    }

    @Test fun rejectsWhatIsNotABook() {
        assertNull(ReaderFormat.detect(bytes("%PDF-1.7".toByteArray()), "a.epub"))
        assertNull(ReaderFormat.detect(zip("word/document.xml" to "<x/>"), "a.epub"))
        assertNull(ReaderFormat.detect(bytes(ByteArray(0)), "a.fb2"))
        assertNull(ReaderFormat.detect(bytes("plain text".toByteArray()), "a.fb2"))
    }
}
```

- [ ] **Step 2: Убедиться, что тест не компилируется** (нет `ReaderFormat`)

Run (из `app/src`): `$SP/run.sh "$PWD/test/java/com/brahmadeo/supertonic/tts/reader/ReaderFormatTest.kt" "com.brahmadeo.supertonic.tts.reader.ReaderFormatTest"`
Expected: `unresolved reference 'ReaderFormat'`.

- [ ] **Step 3: Реализация**

```kotlin
package com.brahmadeo.supertonic.tts.reader

import java.io.File
import java.util.zip.ZipFile

/** Book formats of the built-in reader. [ext] goes into the book URL: foliate-js tells FB2 / FB2.ZIP / CBZ apart
 * by the file name, everything else by content. Detection here is by content only (names lie). */
enum class ReaderFormat(val ext: String) {
    EPUB("epub"), FB2("fb2"), FBZ("fb2.zip"), MOBI("mobi"), AZW3("azw3"), CBZ("cbz");

    companion object {
        private val images = Regex("(?i).+\\.(jpe?g|png|webp|gif|avif)$")

        /** Up to [limit] bytes (InputStream.readNBytes is API 33; minSdk is 24). */
        private fun read(input: java.io.InputStream, limit: Int): ByteArray {
            val out = ByteArray(limit); var n = 0
            while (n < limit) { val r = input.read(out, n, limit - n); if (r < 0) break; n += r }
            return out.copyOf(n)
        }

        fun detect(file: File, name: String): ReaderFormat? {
            if (file.length() < 4) return null
            val head = file.inputStream().use { read(it, 4096) }
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4) return zip(file)
            if (head.size >= 68 && String(head, 60, 8, Charsets.ISO_8859_1) == "BOOKMOBI")
                return if (name.lowercase().endsWith(".azw3")) AZW3 else MOBI
            val text = when {
                head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> String(head, Charsets.UTF_16LE)
                head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> String(head, Charsets.UTF_16BE)
                else -> String(head, Charsets.ISO_8859_1)
            }
            return if ("<FictionBook" in text) FB2 else null
        }

        private fun zip(file: File): ReaderFormat? = runCatching {
            ZipFile(file).use { z ->
                val names = z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
                val mimetype = z.getEntry("mimetype")?.let { e -> z.getInputStream(e).use { String(read(it, 64)).trim() } }
                when {
                    mimetype == "application/epub+zip" || "META-INF/container.xml" in names -> EPUB
                    names.any { it.lowercase().endsWith(".fb2") } -> FBZ
                    names.isNotEmpty() && names.all { images.matches(it) || it.endsWith(".xml") } && names.any { images.matches(it) } -> CBZ
                    else -> null
                }
            }
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Тест проходит**

Run: `$SP/run.sh "$PWD/main/java/com/brahmadeo/supertonic/tts/reader/ReaderFormat.kt $PWD/test/java/com/brahmadeo/supertonic/tts/reader/ReaderFormatTest.kt" "com.brahmadeo.supertonic.tts.reader.ReaderFormatTest"`
Expected: `passed=2 failed=0`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderFormat.kt app/src/test/java/com/brahmadeo/supertonic/tts/reader/ReaderFormatTest.kt
git commit -m "reader: detect book format by content"
```

---

### Task 3: Настройки вида

**Files:**
- Create: `app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderAppearance.kt`
- Test: `app/src/test/java/com/brahmadeo/supertonic/tts/reader/ReaderAppearanceTest.kt`

**Interfaces:**
- Produces: `data class ReaderAppearance(fontSize: Int = 100, lineHeight: Float = 1.5f, margin: Int = 32, theme: String = "light", flow: String = "paginated")`; `toJson(): String`; `ReaderAppearance.parse(json: String?): ReaderAppearance` (неизвестное/битое → значения по умолчанию, всё зажато в допустимые пределы); `THEMES = listOf("light","sepia","dark")`; `background(): Long` (ARGB для Compose/WebView).
- JSON-ключи совпадают с `appearanceCss` в `reader-core.js` (задача 4): `fontSize`, `lineHeight`, `margin`, `theme`, `flow`.

- [ ] **Step 1: Тест**

```kotlin
package com.brahmadeo.supertonic.tts.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderAppearanceTest {
    @Test fun roundTripsAndClampsBrokenValues() {
        val a = ReaderAppearance(fontSize = 140, lineHeight = 1.8f, margin = 16, theme = "dark", flow = "scrolled")
        assertEquals(a, ReaderAppearance.parse(a.toJson()))
        assertEquals(ReaderAppearance(), ReaderAppearance.parse(null))
        assertEquals(ReaderAppearance(), ReaderAppearance.parse("not json"))
        val clamped = ReaderAppearance.parse("""{"fontSize":900,"lineHeight":0.2,"margin":-5,"theme":"neon","flow":"x"}""")
        assertEquals(ReaderAppearance(fontSize = 250, lineHeight = 1.0f, margin = 0, theme = "light", flow = "paginated"), clamped)
        assertEquals(0xFF121212, ReaderAppearance(theme = "dark").background())
    }
}
```

- [ ] **Step 2: Не компилируется** — Run: `$SP/run.sh "$PWD/test/java/com/brahmadeo/supertonic/tts/reader/ReaderAppearanceTest.kt" "com.brahmadeo.supertonic.tts.reader.ReaderAppearanceTest"` → `unresolved reference 'ReaderAppearance'`.

- [ ] **Step 3: Реализация**

```kotlin
package com.brahmadeo.supertonic.tts.reader

import org.json.JSONObject

/** Look of the reading page, per book. The JSON keys are read by appearanceCss in assets/reader/reader-core.js. */
data class ReaderAppearance(
    val fontSize: Int = 100,          // % of the book's own size
    val lineHeight: Float = 1.5f,
    val margin: Int = 32,             // px between text and screen edge
    val theme: String = "light",      // light | sepia | dark
    val flow: String = "paginated",   // paginated | scrolled
) {
    fun toJson(): String = JSONObject().put("fontSize", fontSize).put("lineHeight", lineHeight.toDouble())
        .put("margin", margin).put("theme", theme).put("flow", flow).toString()

    fun background(): Long = when (theme) { "dark" -> 0xFF121212; "sepia" -> 0xFFF4ECD8; else -> 0xFFFFFFFF }

    companion object {
        val THEMES = listOf("light", "sepia", "dark")

        fun parse(json: String?): ReaderAppearance {
            val o = runCatching { JSONObject(json ?: return ReaderAppearance()) }.getOrNull() ?: return ReaderAppearance()
            val d = ReaderAppearance()
            return ReaderAppearance(
                fontSize = o.optInt("fontSize", d.fontSize).coerceIn(50, 250),
                lineHeight = o.optDouble("lineHeight", d.lineHeight.toDouble()).toFloat().coerceIn(1.0f, 2.5f),
                margin = o.optInt("margin", d.margin).coerceIn(0, 120),
                theme = o.optString("theme", d.theme).takeIf { it in THEMES } ?: d.theme,
                flow = o.optString("flow", d.flow).takeIf { it == "paginated" || it == "scrolled" } ?: d.flow,
            )
        }
    }
}
```

- [ ] **Step 4: Тест проходит** — Run (с org.json в classpath, как в полном наборе): `$SP/run.sh "$PWD/main/java/com/brahmadeo/supertonic/tts/reader/ReaderAppearance.kt $PWD/test/java/com/brahmadeo/supertonic/tts/reader/ReaderAppearanceTest.kt" "com.brahmadeo.supertonic.tts.reader.ReaderAppearanceTest"` → `passed=1 failed=0`.

- [ ] **Step 5: Commit** — `git add` оба файла; `git commit -m "reader: per-book appearance settings"`.

---

### Task 4: Страница читалки (JS)

**Files:**
- Create: `app/src/main/assets/reader/reader-core.js`
- Create: `app/src/main/assets/reader/reader-bridge.js`
- Create: `app/src/main/assets/reader/reader.html`
- Test: `tools/reader/reader-core.test.mjs`

**Interfaces:**
- Consumes: `assets/reader/foliate/view.js` (задача 1): `<foliate-view>`, `view.open(url)`, `view.init({lastLocation, showTextStart})`, `view.goTo`, `view.goToFraction`, `view.goLeft/goRight`, событие `relocate`, `view.renderer.setStyles(css)`, атрибуты рендера `flow`, `margin`.
- Produces (JS → Kotlin): `MyTTS.postMessage(JSON.stringify({type, value}))`, типы: `ready` (`""`), `book` (`{title, author, toc:[{label, href, depth}]}`), `relocate` (`{cfi, fraction, section, page, pages}`), `tap` (`"menu"`), `error` (строка).
- Produces (Kotlin → JS): `reader.open(url, cfi, appearanceJson)`, `reader.goTo(target)`, `reader.goToFraction(f)`, `reader.prev()`, `reader.next()`, `reader.setAppearance(json)`.

- [ ] **Step 1: node-тест чистых функций**

```js
// node --test tools/reader/
import test from 'node:test'
import assert from 'node:assert/strict'
import { flattenToc, metaText, authorText, appearanceCss, relocateMessage, tapZone } from '../../app/src/main/assets/reader/reader-core.js'

test('toc is flattened with depth', () => {
  const toc = [{ label: ' Часть 1 ', href: 'a.xhtml', subitems: [{ label: 'I', href: 'a.xhtml#1' }] }, { label: 'Часть 2', href: 'b.xhtml' }]
  assert.deepEqual(flattenToc(toc), [
    { label: 'Часть 1', href: 'a.xhtml', depth: 0 }, { label: 'I', href: 'a.xhtml#1', depth: 1 }, { label: 'Часть 2', href: 'b.xhtml', depth: 0 }])
  assert.deepEqual(flattenToc(undefined), [])
})

test('metadata in any shape becomes text', () => {
  assert.equal(metaText('Идиот'), 'Идиот')
  assert.equal(metaText({ ru: 'Идиот', en: 'The Idiot' }), 'Идиот')
  assert.equal(metaText({ en: 'The Idiot' }), 'The Idiot')
  assert.equal(metaText(undefined), '')
  assert.equal(authorText([{ name: 'Ф. М. Достоевский' }, 'Другой']), 'Ф. М. Достоевский, Другой')
  assert.equal(authorText({ name: { ru: 'Горький' } }), 'Горький')
})

test('appearance css follows the settings', () => {
  const css = appearanceCss({ fontSize: 140, lineHeight: 1.8, theme: 'dark' })
  assert.match(css, /font-size: 140%/)
  assert.match(css, /line-height: 1\.8/)
  assert.match(css, /#121212/)
  assert.match(appearanceCss({}), /#ffffff/)
})

test('relocate message is small and complete', () => {
  assert.deepEqual(relocateMessage({ cfi: 'epubcfi(/6/4)', fraction: 0.25, tocItem: { label: 'Глава 1' }, location: { current: 3, total: 120 } }),
    { cfi: 'epubcfi(/6/4)', fraction: 0.25, section: 'Глава 1', page: 3, pages: 120 })
  assert.deepEqual(relocateMessage({}), { cfi: '', fraction: 0, section: '', page: 0, pages: 0 })
})

test('tap zones: edges turn pages, centre opens the menu', () => {
  assert.equal(tapZone(10, 1000), 'prev')
  assert.equal(tapZone(500, 1000), 'menu')
  assert.equal(tapZone(990, 1000), 'next')
})
```

- [ ] **Step 2: Тест падает** — Run: `node --test tools/reader/` → `Cannot find module …/reader-core.js`.

- [ ] **Step 3: `reader-core.js`**

```js
// Pure helpers of the MyTTS reader page (no DOM; tested with node --test tools/reader/).
export const THEMES = {
  light: { bg: '#ffffff', fg: '#1b1b1b', link: '#2a5db0' },
  sepia: { bg: '#f4ecd8', fg: '#3b2f1e', link: '#6b4b1f' },
  dark: { bg: '#121212', fg: '#e3e3e3', link: '#8ab4f8' },
}

export const flattenToc = (items, depth = 0, out = []) => {
  for (const item of items ?? []) {
    out.push({ label: String(item.label ?? '').trim(), href: item.href ?? '', depth })
    if (item.subitems?.length) flattenToc(item.subitems, depth + 1, out)
  }
  return out
}

// foliate-js metadata values: a string or a language map ({ru: …, en: …}).
export const metaText = x => {
  if (!x) return ''
  if (typeof x === 'string') return x
  return x.ru ?? Object.values(x)[0] ?? ''
}

export const authorText = a => (Array.isArray(a) ? a : [a])
  .map(x => typeof x === 'string' ? x : metaText(x?.name)).filter(Boolean).join(', ')

export const appearanceCss = ({ fontSize = 100, lineHeight = 1.5, theme = 'light' } = {}) => {
  const c = THEMES[theme] ?? THEMES.light
  return `html { color: ${c.fg} !important; background: ${c.bg} !important; font-size: ${fontSize}% !important; }
p, li, blockquote, dd, div { line-height: ${lineHeight} !important; }
a:any-link { color: ${c.link} !important; }
img, svg { max-width: 100% !important; }`
}

export const relocateMessage = d => ({
  cfi: d?.cfi ?? '', fraction: d?.fraction ?? 0, section: d?.tocItem?.label ?? '',
  page: d?.location?.current ?? 0, pages: d?.location?.total ?? 0,
})

export const tapZone = (x, width) => x < width * 0.3 ? 'prev' : x > width * 0.7 ? 'next' : 'menu'
```

- [ ] **Step 4: Тест проходит** — Run: `node --test tools/reader/` → `pass 5`.

- [ ] **Step 5: `reader-bridge.js` и `reader.html`**

```js
// Connects <foliate-view> to the app: Kotlin calls window.reader.*, the page answers through MyTTS.postMessage.
import './foliate/view.js'
import { THEMES, flattenToc, metaText, authorText, appearanceCss, relocateMessage, tapZone } from './reader-core.js'

const send = (type, value = '') => globalThis.MyTTS?.postMessage(JSON.stringify({ type, value }))
let view = null
let appearance = {}

const apply = () => {
  document.body.style.background = (THEMES[appearance.theme] ?? THEMES.light).bg
  if (!view?.renderer) return
  view.renderer.setAttribute('flow', appearance.flow === 'scrolled' ? 'scrolled' : 'paginated')
  view.renderer.setAttribute('margin', `${appearance.margin ?? 32}px`)
  view.renderer.setAttribute('gap', '6%')
  view.renderer.setStyles?.(appearanceCss(appearance))
}

// Taps inside a section document: edges turn pages, the centre toggles the app's panels.
const onTap = e => {
  if (e.target?.closest?.('a[href]')) return
  if (!e.view?.getSelection()?.isCollapsed) return
  const zone = tapZone(e.screenX, globalThis.innerWidth)
  if (zone === 'prev') view.goLeft()
  else if (zone === 'next') view.goRight()
  else send('tap', 'menu')
}

globalThis.reader = {
  async open(url, cfi, appearanceJson) {
    try {
      appearance = JSON.parse(appearanceJson || '{}')
      view = document.createElement('foliate-view')
      document.body.append(view)
      view.addEventListener('relocate', e => send('relocate', relocateMessage(e.detail)))
      view.addEventListener('load', e => e.detail.doc.addEventListener('click', onTap))
      // Links to other sites never open inside the reader.
      view.addEventListener('external-link', e => e.preventDefault())
      await view.open(url)
      apply()
      const { book } = view
      send('book', { title: metaText(book.metadata?.title), author: authorText(book.metadata?.author), toc: flattenToc(book.toc) })
      // A stored place that no longer resolves (another edition) opens the start of the text.
      await view.init({ lastLocation: cfi || null, showTextStart: true }).catch(() => view.init({ showTextStart: true }))
    } catch (e) { send('error', String(e?.message ?? e)) }
  },
  goTo: target => view?.goTo(target).catch(e => send('error', String(e?.message ?? e))),
  goToFraction: f => view?.goToFraction(f),
  prev: () => view?.goLeft(),
  next: () => view?.goRight(),
  setAppearance(json) { appearance = JSON.parse(json || '{}'); apply() },
}

addEventListener('error', e => send('error', String(e.message)))
addEventListener('unhandledrejection', e => send('error', String(e.reason?.message ?? e.reason)))
send('ready')
```

```html
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'self' blob: data:; style-src 'self' 'unsafe-inline' blob:; script-src 'self'; img-src 'self' blob: data:; font-src 'self' blob: data:; connect-src 'self' blob:">
<style>
html, body { margin: 0; height: 100%; overflow: hidden; }
foliate-view { display: block; width: 100%; height: 100%; }
</style>
<script type="module" src="reader-bridge.js"></script>
</head>
<body></body>
</html>
```

- [ ] **Step 6: Commit** — `git add app/src/main/assets/reader/reader-core.js app/src/main/assets/reader/reader-bridge.js app/src/main/assets/reader/reader.html tools/reader/reader-core.test.mjs`; `git commit -m "reader: page and JS bridge over foliate-view"`.

---

### Task 5: Хранилище и импорт

**Files:**
- Create: `app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderStore.kt`
- Create: `app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderImport.kt`
- Modify: `docs/superpowers/specs/2026-10-12-built-in-reader-design.md` (раздел «Библиотека»: `files/reader.db` вместо таблицы в `books.db` — отдельная база не трогает версию схемы `.mytts-book`; книги пользователя в `files`, а не `no_backup`)

**Interfaces:**
- Consumes: `ReaderFormat.detect` (задача 2), `ReaderAppearance` (задача 3).
- Produces:
  - `data class ReaderBook(val id: Long, val sha: String, val format: ReaderFormat, val title: String, val author: String, val cfi: String, val fraction: Double, val opened: Long, val appearance: ReaderAppearance)`
  - `object ReaderStore { fun list(ctx): List<ReaderBook>; fun book(ctx, id: Long): ReaderBook?; fun file(ctx, book: ReaderBook): File; fun add(ctx, sha: String, format: ReaderFormat, title: String): Long /* existing id for the same sha */; fun saveMeta(ctx, id, title: String, author: String); fun saveProgress(ctx, id, cfi: String, fraction: Double); fun saveAppearance(ctx, id, a: ReaderAppearance); fun delete(ctx, id) }`
  - `object ReaderImport { class NotABook : Exception(); fun import(ctx, uri: Uri): Long /* throws NotABook, IOException */ }`
  - URL книги для WebView: `"https://appassets.androidplatform.net/book/${book.id}/book.${book.format.ext}"`.

- [ ] **Step 1: `ReaderStore.kt`**

```kotlin
package com.brahmadeo.supertonic.tts.reader

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class ReaderBook(val id: Long, val sha: String, val format: ReaderFormat, val title: String, val author: String,
                      val cfi: String, val fraction: Double, val opened: Long, val appearance: ReaderAppearance)

/** Books of the built-in reader: files/reader.db (place, look) and files/library/<sha256>.<ext>. The user's own
 * files, so in files/ (backed up with the app), not no_backup. Same file twice = one book, place kept. */
object ReaderStore {
    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, File(ctx.filesDir, "reader.db").path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) = db.execSQL(
            "CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT, sha TEXT UNIQUE NOT NULL, format TEXT NOT NULL, " +
            "title TEXT NOT NULL, author TEXT NOT NULL DEFAULT '', cfi TEXT NOT NULL DEFAULT '', fraction REAL NOT NULL DEFAULT 0, " +
            "added INTEGER NOT NULL, opened INTEGER NOT NULL DEFAULT 0, appearance TEXT)")
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    }
    @Volatile private var helper: Helper? = null
    private fun db(ctx: Context) = (helper ?: synchronized(this) { helper ?: Helper(ctx.applicationContext).also { helper = it } }).writableDatabase

    fun library(ctx: Context) = File(ctx.filesDir, "library").apply { mkdirs() }
    fun file(ctx: Context, book: ReaderBook) = File(library(ctx), "${book.sha}.${book.format.ext}")

    private fun row(c: Cursor) = ReaderBook(c.getLong(0), c.getString(1), ReaderFormat.valueOf(c.getString(2)), c.getString(3),
        c.getString(4), c.getString(5), c.getDouble(6), c.getLong(7), ReaderAppearance.parse(c.getString(8)))
    private const val COLUMNS = "id, sha, format, title, author, cfi, fraction, opened, appearance"

    fun list(ctx: Context): List<ReaderBook> = db(ctx).rawQuery("SELECT $COLUMNS FROM books ORDER BY opened DESC, added DESC", null)
        .use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
    fun book(ctx: Context, id: Long): ReaderBook? = db(ctx).rawQuery("SELECT $COLUMNS FROM books WHERE id=?", arrayOf(id.toString()))
        .use { if (it.moveToFirst()) row(it) else null }

    @Synchronized fun add(ctx: Context, sha: String, format: ReaderFormat, title: String): Long {
        db(ctx).rawQuery("SELECT id FROM books WHERE sha=?", arrayOf(sha)).use { if (it.moveToFirst()) return it.getLong(0) }
        return db(ctx).insertOrThrow("books", null, ContentValues().apply {
            put("sha", sha); put("format", format.name); put("title", title); put("added", System.currentTimeMillis())
        })
    }
    fun saveMeta(ctx: Context, id: Long, title: String, author: String) {
        if (title.isBlank()) return
        db(ctx).update("books", ContentValues().apply { put("title", title); put("author", author) }, "id=?", arrayOf(id.toString()))
    }
    fun saveProgress(ctx: Context, id: Long, cfi: String, fraction: Double) {
        db(ctx).update("books", ContentValues().apply { put("cfi", cfi); put("fraction", fraction); put("opened", System.currentTimeMillis()) },
            "id=?", arrayOf(id.toString()))
    }
    fun saveAppearance(ctx: Context, id: Long, a: ReaderAppearance) {
        db(ctx).update("books", ContentValues().apply { put("appearance", a.toJson()) }, "id=?", arrayOf(id.toString()))
    }
    fun delete(ctx: Context, id: Long) {
        val book = book(ctx, id) ?: return
        db(ctx).delete("books", "id=?", arrayOf(id.toString()))
        file(ctx, book).delete()
    }
}
```

- [ ] **Step 2: `ReaderImport.kt`**

```kotlin
package com.brahmadeo.supertonic.tts.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Copies a book from a content Uri into the library: ≤ 100 MB, format by content, sha256 name. */
object ReaderImport {
    class NotABook : Exception("Это не книга: поддерживаются EPUB, FB2, FB2.ZIP, MOBI, AZW3 и CBZ")
    private const val MAX_BYTES = 100L * 1024 * 1024

    fun import(ctx: Context, uri: Uri): Long {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "book"
        val temp = File.createTempFile("import-", ".part", ctx.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            (ctx.contentResolver.openInputStream(uri) ?: throw IOException("Файл не открывается")).use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024); var total = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; if (total > MAX_BYTES) throw IOException("Файл больше 100 МБ")
                        digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    }
                }
            }
            val format = ReaderFormat.detect(temp, name) ?: throw NotABook()
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(ReaderStore.library(ctx), "$sha.${format.ext}")
            if (!target.exists() && !temp.renameTo(target)) temp.copyTo(target)
            val title = name.substringBeforeLast('.').removeSuffix(".fb2").ifBlank { "Книга" }
            return ReaderStore.add(ctx, sha, format, title)
        } finally { temp.delete() }
    }
}
```

- [ ] **Step 3: Поправить spec** — в разделе «Библиотека» заменить «Таблица `reader_books` в существующей `books.db`» на «Отдельная база `files/reader.db` (не меняет схему `books.db` подготовленных книг); файлы — `files/library/<sha256>.<ext>`».

- [ ] **Step 4: Проверка компиляции** — полностью проверяется сборкой CI (задача 8); локально: `grep -n "ReaderFormat\|ReaderAppearance" app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderStore.kt` — имена совпадают с задачами 2–3.

- [ ] **Step 5: Commit** — `git add` оба файла и spec; `git commit -m "reader: library storage and import"`.

---

### Task 6: WebView-хост и экран чтения

**Files:**
- Create: `app/src/main/java/com/brahmadeo/supertonic/tts/reader/ReaderWeb.kt`
- Create: `app/src/main/java/com/brahmadeo/supertonic/tts/ReaderActivity.kt`
- Modify: `app/build.gradle` (зависимость `implementation 'androidx.webkit:webkit:1.12.1'` рядом с `androidx.core`)
- Modify: `app/src/main/AndroidManifest.xml` (activity)

**Interfaces:**
- Consumes: `ReaderStore`, `ReaderBook`, `ReaderAppearance`, JS-API задачи 4.
- Produces: `ReaderWeb.create(ctx: Context, book: ReaderBook, onMessage: (type: String, value: String) -> Unit): WebView`; `ReaderWeb.call(web: WebView, js: String)`; `ReaderActivity.EXTRA_BOOK = "book_id"` (Long).

- [ ] **Step 1: `ReaderWeb.kt`**

```kotlin
package com.brahmadeo.supertonic.tts.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/** The reading page: foliate-js from assets, the book file from the library, nothing from the network. */
object ReaderWeb {
    const val HOST = "appassets.androidplatform.net"
    private const val ORIGIN = "https://$HOST"
    const val PAGE = "$ORIGIN/reader/reader.html"
    fun bookUrl(book: ReaderBook) = "$ORIGIN/book/${book.id}/book.${book.format.ext}"

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context, book: ReaderBook, onMessage: (String, String) -> Unit): WebView {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/reader/", WebViewAssetLoader.AssetsPathHandler(ctx))
            .addPathHandler("/book/") { path ->
                // "<id>/book.<ext>": only the book this screen opened.
                if (path.substringBefore('/') != book.id.toString()) null
                else WebResourceResponse("application/octet-stream", null, FileInputStream(ReaderStore.file(ctx, book)))
            }.build()
        return WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            setBackgroundColor(book.appearance.background().toInt())
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (request.url.host != HOST) blocked() else loader.shouldInterceptRequest(request.url) ?: blocked()
                // The page never navigates away; links inside the book are handled by foliate-js.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.toString() != PAGE
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean { Log.i("ReaderJS", "${m.message()} @${m.lineNumber()}"); return true }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(this, "MyTTS", setOf(ORIGIN)) { _, message, _, _, _ ->
                    val o = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                    val type = o.optString("type").takeIf { it in setOf("ready", "book", "relocate", "tap", "error") } ?: return@addWebMessageListener
                    val value = o.opt("value")?.toString().orEmpty()
                    if (value.length <= 200_000) onMessage(type, value)
                }
            } else Log.w("Reader", "WebView without WebMessageListener: reader bridge unavailable")
            loadUrl(PAGE)
        }
    }

    fun call(web: WebView, js: String) = web.evaluateJavascript(js, null)
    fun quote(s: String) = JSONObject.quote(s)
}
```

- [ ] **Step 2: `ReaderActivity.kt`**

```kotlin
package com.brahmadeo.supertonic.tts

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.brahmadeo.supertonic.tts.reader.ReaderAppearance
import com.brahmadeo.supertonic.tts.reader.ReaderStore
import com.brahmadeo.supertonic.tts.reader.ReaderWeb
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import org.json.JSONObject
import kotlin.math.roundToInt

/** Reading screen: the foliate-js page plus Compose panels (title, contents, progress, look). */
class ReaderActivity : ComponentActivity() {
    companion object { const val EXTRA_BOOK = "book_id" }
    private var web: WebView? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val book = ReaderStore.book(this, intent.getLongExtra(EXTRA_BOOK, -1)) ?: run { finish(); return }
        setContent {
            SupertonicTheme {
                var title by remember { mutableStateOf(book.title) }
                var toc by remember { mutableStateOf(listOf<Triple<String, String, Int>>()) }
                var section by remember { mutableStateOf("") }
                var fraction by remember { mutableFloatStateOf(book.fraction.toFloat()) }
                var dragging by remember { mutableStateOf<Float?>(null) }
                var panels by remember { mutableStateOf(false) }
                var showToc by remember { mutableStateOf(false) }
                var showLook by remember { mutableStateOf(false) }
                var look by remember { mutableStateOf(book.appearance) }
                var error by remember { mutableStateOf("") }
                var loading by remember { mutableStateOf(true) }
                fun js(code: String) { web?.let { ReaderWeb.call(it, code) } }
                fun onMessage(type: String, value: String) = runOnUiThread {
                    when (type) {
                        "ready" -> js("reader.open(${ReaderWeb.quote(ReaderWeb.bookUrl(book))}, ${ReaderWeb.quote(book.cfi)}, ${ReaderWeb.quote(look.toJson())})")
                        "book" -> runCatching {
                            val o = JSONObject(value); loading = false
                            title = o.optString("title").ifBlank { book.title }
                            ReaderStore.saveMeta(this, book.id, o.optString("title"), o.optString("author"))
                            val items = o.optJSONArray("toc")
                            toc = (0 until (items?.length() ?: 0)).map { i -> items!!.getJSONObject(i).let { Triple(it.optString("label"), it.optString("href"), it.optInt("depth")) } }
                        }
                        "relocate" -> runCatching {
                            val o = JSONObject(value); loading = false
                            fraction = o.optDouble("fraction", 0.0).toFloat(); section = o.optString("section")
                            ReaderStore.saveProgress(this, book.id, o.optString("cfi"), fraction.toDouble())
                        }
                        "tap" -> panels = !panels
                        "error" -> { loading = false; error = value; android.util.Log.w("Reader", "Page error: $value") }
                    }
                }
                Box(Modifier.fillMaxSize().background(Color(look.background()))) {
                    AndroidView(factory = { ctx -> ReaderWeb.create(ctx, book, ::onMessage).also { web = it } },
                        modifier = Modifier.fillMaxSize().safeDrawingPadding())
                    if (loading) CircularProgressIndicator(Modifier.align(androidx.compose.ui.Alignment.Center))
                    if (error.isNotEmpty()) Text("Не удалось открыть книгу: $error", Modifier.align(androidx.compose.ui.Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.error)
                    if (panels) {
                        TopAppBar(title = { Text(title, maxLines = 1) }, modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                            navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                            actions = {
                                IconButton(onClick = { showToc = true }) { Icon(Icons.AutoMirrored.Filled.List, "Оглавление") }
                                IconButton(onClick = { showLook = true }) { Icon(Icons.Default.TextFields, "Вид") }
                            })
                        Surface(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(), tonalElevation = 3.dp) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("${section.ifBlank { title }} · ${((dragging ?: fraction) * 100).roundToInt()} %", maxLines = 1)
                                Slider(value = dragging ?: fraction, onValueChange = { dragging = it },
                                    onValueChangeFinished = { dragging?.let { js("reader.goToFraction($it)") }; dragging = null })
                            }
                        }
                    }
                }
                if (showToc) ModalBottomSheet(onDismissRequest = { showToc = false }) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                        itemsIndexed(toc) { _, (label, href, depth) ->
                            Text(label, Modifier.fillMaxWidth().clickable { js("reader.goTo(${ReaderWeb.quote(href)})"); showToc = false; panels = false }
                                .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 12.dp, bottom = 12.dp))
                        }
                    }
                }
                if (showLook) ModalBottomSheet(onDismissRequest = { showLook = false }) {
                    fun update(a: ReaderAppearance) { look = a; ReaderStore.saveAppearance(this@ReaderActivity, book.id, a); js("reader.setAppearance(${ReaderWeb.quote(a.toJson())})") }
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Размер шрифта: ${look.fontSize} %")
                        Slider(look.fontSize.toFloat(), { update(look.copy(fontSize = it.roundToInt())) }, valueRange = 50f..250f, steps = 19)
                        Text("Межстрочный интервал: ${"%.1f".format(look.lineHeight)}")
                        Slider(look.lineHeight, { update(look.copy(lineHeight = (it * 10).roundToInt() / 10f)) }, valueRange = 1f..2.5f, steps = 14)
                        Text("Поля: ${look.margin} px")
                        Slider(look.margin.toFloat(), { update(look.copy(margin = it.roundToInt())) }, valueRange = 0f..120f, steps = 11)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("light" to "Светлая", "sepia" to "Сепия", "dark" to "Тёмная").forEach { (id, label) ->
                                FilterChip(look.theme == id, { update(look.copy(theme = id)) }, { Text(label) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(look.flow == "paginated", { update(look.copy(flow = "paginated")) }, { Text("Страницы") })
                            FilterChip(look.flow == "scrolled", { update(look.copy(flow = "scrolled")) }, { Text("Прокрутка") })
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
```

- [ ] **Step 3: Gradle и манифест**

`app/build.gradle`, после `implementation 'androidx.core:core-ktx:1.18.0'`:
```gradle
    implementation 'androidx.webkit:webkit:1.12.1'
```
`AndroidManifest.xml`, рядом с `.MultilingualActivity`:
```xml
        <activity android:name=".ReaderActivity" android:exported="false" android:label="Читалка"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden" />
```

- [ ] **Step 4: Commit** — `git add` файлы задачи; `git commit -m "reader: WebView host and reading screen"`.

---

### Task 7: Библиотека, пункт меню, «Открыть с помощью»

**Files:**
- Create: `app/src/main/java/com/brahmadeo/supertonic/tts/ReaderLibraryActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `ui/MainScreen.kt`, `MainActivity.kt`

**Interfaces:**
- Consumes: `ReaderStore.list/delete`, `ReaderImport.import`, `ReaderActivity.EXTRA_BOOK`.
- Produces: пункт меню «Читалка» (первым), обработка `ACTION_VIEW` и `ACTION_SEND` (`EXTRA_STREAM`) с файлами книг.

- [ ] **Step 1: `ReaderLibraryActivity.kt`**

```kotlin
package com.brahmadeo.supertonic.tts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brahmadeo.supertonic.tts.reader.ReaderBook
import com.brahmadeo.supertonic.tts.reader.ReaderImport
import com.brahmadeo.supertonic.tts.reader.ReaderStore
import com.brahmadeo.supertonic.tts.ui.theme.SupertonicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Books of the built-in reader. Also receives «Открыть с помощью» / «Поделиться» with a book file. */
class ReaderLibraryActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming.value = uriOf(intent)
        setContent {
            SupertonicTheme {
                var books by remember { mutableStateOf(ReaderStore.list(this)) }
                var message by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var deleting by remember { mutableStateOf<ReaderBook?>(null) }
                val scope = rememberCoroutineScope()
                fun open(id: Long) = startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK, id))
                fun import(uri: Uri) {
                    busy = true; message = "Добавление книги…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { ReaderImport.import(this@ReaderLibraryActivity, uri) } }
                        busy = false; books = ReaderStore.list(this@ReaderLibraryActivity)
                        result.onSuccess { message = ""; open(it) }.onFailure { message = it.message ?: "Не удалось добавить книгу" }
                    }
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) import(uri) }
                LaunchedEffect(incoming.value) { incoming.value?.let { incoming.value = null; import(it) } }
                LaunchedEffect(Unit) { books = ReaderStore.list(this@ReaderLibraryActivity) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Читалка", style = MaterialTheme.typography.headlineMedium)
                        Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Добавить книгу") }
                        Text("EPUB, FB2, FB2.ZIP, MOBI, AZW3, CBZ. Книга копируется в приложение.", style = MaterialTheme.typography.bodySmall)
                        if (message.isNotEmpty()) Text(message, color = if (busy) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                        if (books.isEmpty() && !busy) Text("Книг пока нет.")
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(books, key = { it.id }) { book ->
                                Card(Modifier.fillMaxWidth().clickable { open(book.id) }) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                            if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                            Text("${book.format.ext.uppercase()} · прочитано ${(book.fraction * 100).roundToInt()} %", style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = { deleting = book }) { Text("Удалить") }
                                    }
                                }
                            }
                        }
                    }
                }
                deleting?.let { book ->
                    AlertDialog(onDismissRequest = { deleting = null },
                        title = { Text("Удалить книгу?") }, text = { Text("«${book.title}» и место чтения будут удалены из приложения. Исходный файл не трогается.") },
                        confirmButton = { TextButton(onClick = { ReaderStore.delete(this, book.id); books = ReaderStore.list(this); deleting = null }) { Text("Удалить") } },
                        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); uriOf(intent)?.let { incoming.value = it } }

    @Suppress("DEPRECATION")
    private fun uriOf(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}
```

- [ ] **Step 2: Манифест** (рядом с `.ReaderActivity`)

```xml
        <activity android:name=".ReaderLibraryActivity" android:exported="true" android:label="Читалка" android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="content" />
                <data android:mimeType="application/epub+zip" />
                <data android:mimeType="application/x-fictionbook+xml" />
                <data android:mimeType="application/x-fictionbook" />
                <data android:mimeType="application/x-zip-compressed-fb2" />
                <data android:mimeType="application/x-mobipocket-ebook" />
                <data android:mimeType="application/vnd.amazon.ebook" />
                <data android:mimeType="application/vnd.comicbook+zip" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="application/epub+zip" />
                <data android:mimeType="application/x-fictionbook+xml" />
                <data android:mimeType="application/x-fictionbook" />
                <data android:mimeType="application/x-mobipocket-ebook" />
            </intent-filter>
        </activity>
```

- [ ] **Step 3: Пункт меню** — `ui/MainScreen.kt`: параметр `onReaderClick: () -> Unit = {},` рядом с `onMultilingualClick`; первым пунктом выпадающего меню:
```kotlin
                        DropdownMenuItem(
                            text = { Text("Читалка") },
                            onClick = { showMenu = false; onReaderClick() }
                        )
```
`MainActivity.kt` рядом с `onMultilingualClick = …`:
```kotlin
                        onReaderClick = { startActivity(Intent(this, ReaderLibraryActivity::class.java)) },
```

- [ ] **Step 4: Commit** — `git commit -m "reader: library screen, menu item, open-with"`.

---

### Task 8: Сборка, проверка на телефоне, выпуск

**Files:**
- Modify: `app/build.gradle` (`versionCode 191`, `versionName "5.0.4-beta16"`), `.github/release-notes.md`, `docs/stress-vote.md` не трогать; `AGENTS.md` (раздел о читалке).

- [ ] **Step 1: Все локальные тесты** — JVM: полный набор (`files2.txt` + `ReaderFormatTest`, `ReaderAppearanceTest`) → `failed=0`; JS: `node --test tools/reader/` → `fail 0`.

- [ ] **Step 2: Версия и описание** — `versionCode 191`, `versionName "5.0.4-beta16"`; в начало `.github/release-notes.md`:
```markdown
# MyTTS 5.0.4 Beta 16

Бета. Устанавливается поверх предыдущей MyTTS с сохранением моделей и настроек.

- **Своя читалка (этап 1).** Пункт меню «Читалка»: добавьте EPUB, FB2, FB2.ZIP, MOBI, AZW3 или CBZ (или откройте файл «Открыть с помощью»). Листание касанием краёв, панели — касанием середины, оглавление, прогресс, размер шрифта, интервал, поля, темы, страницы или прокрутка; место чтения запоминается. Движок — foliate-js (как в Readest). Озвучка внутри читалки — следующий этап; Moon+ и Readest работают как раньше.
```

- [ ] **Step 3: Коммит, тег, push; дождаться GitHub** — `git tag v5.0.4-beta16 && git push origin feature/model-selector v5.0.4-beta16`; ждать `Android Release` (из каталога репозитория): `until s=$(gh run view <id> --json status,conclusion -q '.status+" "+.conclusion'); [[ $s == completed* ]]; do sleep 20; done`. При ошибке компиляции — исправить, beta17.

- [ ] **Step 4: Установка и проверка на телефоне** (разблокированный, `stay_on` 7 на время, затем вернуть 0; проверить `media_session` перед установкой):
  1. `adb install -r …beta16-arm64-v8a.apk`;
  2. «Читалка» → «Добавить книгу»: `~/Downloads/fedor_mihajlovich_dostoevskij-idiot-1488962523.epub` (через `adb push` в Download и системный выбор файла), `darya_leonidovna_bobilyova-nochnoj_vzglyad-*.epub`, FB2 из `/sdcard/Download` (если есть) — книга появляется, открывается, название/автор из метаданных;
  3. логи `adb logcat -s ReaderJS:* Reader:*` — нет ошибок; XML-дамп (`uiautomator dump`) — панели, оглавление, проценты;
  4. касание правого края → проценты растут; оглавление → переход в главу; выйти и открыть снова → то же место;
  5. повторно добавить тот же файл → нет дубликата, место сохранено; добавить PDF → сообщение «Это не книга…»;
  6. Moon+ / Readest: один абзац вслух — без регрессии.

- [ ] **Step 5: Записать итог** — `AGENTS.md` и раздел «Проверка» в spec: что проверено, что нет; commit `[skip ci]`.
