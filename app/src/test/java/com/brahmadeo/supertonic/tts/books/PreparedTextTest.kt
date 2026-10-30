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

    /** Разные знаки — разные предложения, даже когда буквы совпадают. */
    @Test fun keySeparatesDifferentPunctuation() {
        assertTrue(PreparedText.key("Анна!") != PreparedText.key("Анна»"))
        assertTrue(PreparedText.key("Да.") != PreparedText.key("Да?"))
        assertEquals("тире реплики не в счёт", PreparedText.key("— Да."), PreparedText.key("Да."))
    }

    @Test fun paragraphIsAssembledFromPreparedSentences() {
        val store = PreparedText.pairs(para, marked).toMap()
        assertEquals(4, store.size)
        assertEquals(marked, PreparedText.prepare(store, para))
    }

    /** Абзац, записанный целиком: находится, пока авторские знаки на месте. */
    @Test fun wholeParagraphEntryIsFoundWhenPunctuationKept() {
        val source = "Он вошёл. Она молчала."
        val marked = "Он вошёл. Она́ молча́ла."
        val store = mapOf(PreparedText.key(source) to marked)
        assertEquals(marked, PreparedText.prepare(store, source))
    }

    /** Модель склеила два предложения и заменила авторскую точку запятой — это подмена пунктуации, не годится. */
    @Test fun mergedSentencesAreRejected() {
        val source = "Он вошёл. Она молчала."
        val store = mapOf(PreparedText.key(source) to "Он вошёл, она́ молча́ла.")
        assertNull(store.toString(), PreparedText.prepare(store, source))
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

    /** Авторские знаки — часть текста: точка, запятая, тире, многоточие не могут исчезнуть, иначе интонация и паузы
     * перестают быть авторскими. Добавленный знак не мешает. */
    @Test fun authorPunctuationIsNeverLost() {
        val source = "— Я не знаю, — сказала она. — Спроси у Ивана…"
        assertTrue(PreparedText.keepsPunctuation(source, "— Я не зна́ю, — сказа́ла она́. — Спроси́ у Ива́на…"))
        assertTrue("добавленная запятая не портит", PreparedText.keepsPunctuation(source, "— Я не зна́ю, — сказа́ла она́, — и ушла́. — Спроси́ у Ива́на…"))
        assertTrue("потерянная запятая ловится только по порядку знаков", !PreparedText.keepsPunctuation(source, "— Я не зна́ю — сказа́ла она́. — Спроси́ у Ива́на"))
        assertTrue("потерянное внутреннее тире", !PreparedText.keepsPunctuation(source, "— Я не зна́ю, сказа́ла она́. — Спроси́ у Ива́на…"))
        assertTrue("потерянное многоточие", !PreparedText.keepsPunctuation(source, "— Я не зна́ю, — сказа́ла она́. — Спроси́ у Ива́на."))
        val store = mapOf(PreparedText.key(source) to "— Я не зна́ю — сказа́ла она́. — Спроси́ у Ива́на")
        assertNull("подмена без запятых не принимается", PreparedText.prepare(store, source))
    }

    /** Читалки присылают реплику и с тире, и без него: тире в начале строки — знак реплики, а не авторская пунктуация. */
    @Test fun leadingDialogueDashMayComeAndGo() {
        assertTrue(PreparedText.keepsPunctuation("— Да.", "Да."))
        assertTrue(PreparedText.keepsPunctuation("Да.", "— Да."))
        assertTrue("запятая внутри всё равно обязательна", !PreparedText.keepsPunctuation("— Да, князь.", "Да князь."))
        val store = mapOf(PreparedText.key("— Да, князь.") to "Да, князь.")
        assertEquals("Да, князь.", PreparedText.prepare(store, "— Да, князь."))
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
