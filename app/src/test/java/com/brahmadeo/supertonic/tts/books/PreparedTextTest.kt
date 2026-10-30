package com.brahmadeo.supertonic.tts.books

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Готовый текст книги: нарезка предложений, отпечатки, отказ при расхождениях, раздел «prepared» файла книги.
 * Всё это — то, от чего зависит чтение подготовленной книги без нейросети, поэтому проверяется отдельно. */
class PreparedTextTest {
    private val para = "Князь Мышкин вошёл в переднюю. Он сказал это тихо. — Ты готов? — спросила Анна."
    private val marked = "Кня́зь Мы́шкин вошёл в пере́днюю. Он сказа́л э́то ти́хо. — Ты гото́в? — спроси́ла А́нна."

    /** Сборка из частей возвращает исходный текст байт в байт: иначе абзац склеится с другими пробелами. */
    @Test fun piecesRebuildTheOriginalText() {
        for (text in listOf(para, "Одно предложение.", "Без точки", "Строка\nвторая строка. Конец!",
                            "Тире — и ещё тире… Потом многоточие... И всё?", "  Пробелы по краям.  ")) {
            val pieces = PreparedText.pieces(text)
            assertEquals("части «$text»", text, pieces.joinToString("") { it.text + it.separator })
        }
    }

    @Test fun emptyTextGivesNoPieces() {
        assertTrue(PreparedText.pieces("").isEmpty())
    }

    /** Ключ не зависит от регистра, ё и уже расставленных ударений: книга могла прийти в любом виде. */
    @Test fun keyIgnoresCaseYoAndStress() {
        assertEquals(PreparedText.key("Князь Мышкин"), PreparedText.key("князь мышкин"))
        assertEquals(PreparedText.key("все"), PreparedText.key("всё"))
        assertEquals(PreparedText.key("Кня́зь Мы́шкин"), PreparedText.key("Князь Мышкин"))
    }

    @Test fun paragraphIsAssembledFromPreparedSentences() {
        val store = PreparedText.pairs(para, marked).toMap()
        assertEquals(4, store.size)
        assertEquals(marked, PreparedText.prepare(store, para))
    }

    /** Разное число предложений: сохраняется весь абзац одной записью, лишь бы слова совпали. */
    @Test fun differentSentenceCountKeepsWholeParagraph() {
        val store = PreparedText.pairs("Он вошёл. Она молчала.", "Он вошёл, она́ молча́ла.").toMap()
        assertEquals(1, store.size)
        assertEquals("Он вошёл, она́ молча́ла.", PreparedText.prepare(store, "Он вошёл. Она молчала."))
    }

    /** Чужие или потерянные слова не принимаются никогда: лучше прочитать обычным путём, чем подменить текст. */
    @Test fun changedWordsAreNeverAccepted() {
        assertNull(PreparedText.pair("Князь Мышкин", "Кня́зь Идио́т"))
        assertNull(PreparedText.pair("Князь Мышкин", ""))
        assertNull(PreparedText.pair("", "Кня́зь Мы́шкин"))
        val store = PreparedText.pairs(para, marked).toMap()
        assertNull("неполный набор", PreparedText.prepare(store, para + " Добавленное предложение."))
        assertNull("пустой набор", PreparedText.prepare(emptyMap(), para))
        assertNull("пустой текст", PreparedText.prepare(store, ""))
    }

    /** Абзац, в котором одно предложение из книги, а другое новое, целиком идёт обычным путём (без склейки). */
    @Test fun partiallyKnownParagraphFallsBackWhole() {
        val store = PreparedText.pairs(para, marked).toMap()
        val mixed = "Князь Мышкин вошёл в переднюю. Совсем новое предложение, которого в книге нет."
        assertNull(PreparedText.prepare(store, mixed))
    }

    /** Неразрывный пробел и мягкий перенос неразрывны для читалки: граница предложения и слово не должны ломаться. */
    @Test fun unusualSpacesAndInvisibleCharacters() {
        val text = "Первое предложение.\u00A0Второе предложение."
        assertEquals(listOf("Первое предложение.", "Второе предложение."), PreparedText.pieces(text).map { it.text })
        assertEquals(text, PreparedText.pieces(text).joinToString("") { it.text + it.separator })
        assertEquals(PreparedText.key("молоко"), PreparedText.key("мо\u00ADлоко"))
        assertEquals(PreparedText.key("молоко"), PreparedText.key("мо\u200Bлоко"))
    }

    /** Текст, в котором каждое предложение уже с ударениями, читается без изменений. */
    @Test fun alreadyMarkedSourceIsUnchanged() {
        val store = PreparedText.pairs(marked, marked).toMap()
        assertEquals(marked, PreparedText.prepare(store, marked))
    }

    @Test fun shortRepliesAreFound() {
        val store = PreparedText.pairs("— Да. — Нет.", "— Да. — Нет.").toMap()
        assertEquals("— Да. — Нет.", PreparedText.prepare(store, "— Да. — Нет."))
    }

    /** Ключи пишутся беззнаковыми: отрицательный хеш обязан прочитаться обратно. */
    @Test fun jsonRoundTripKeepsEverySentence() {
        val store = PreparedText.pairs(para, marked).toMap()
        val back = PreparedText.parse(PreparedText.toJson(store))
        assertEquals(store.size, back.size)
        assertEquals(marked, PreparedText.prepare(back, para))
    }

    @Test fun foreignAlgorithmAndEmptySectionAreIgnored() {
        assertTrue(PreparedText.parse(null).isEmpty())
        assertTrue(PreparedText.parse(JSONObject().put("algorithm", "чужой").put("texts", JSONObject())).isEmpty())
        assertTrue(PreparedText.parse(JSONObject().put("algorithm", PreparedText.ALGORITHM)).isEmpty())
        assertTrue(PreparedText.parse(JSONObject()).isEmpty())
    }
}
