package com.brahmadeo.supertonic.tts.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/** Parity with silero-stress 1.5 (Python homosolver): marked context and phrase decisions, reference values produced
 * by the Python package on the same sentences. Runs from the app module directory (Gradle unit tests do). */
class SileroHomoContextTest {
    private val table = SileroHomoContext.load(GZIPInputStream(File("src/main/assets/silero_phrases.tsv.gz").inputStream()).bufferedReader().lineSequence())
    private val homo = SileroHomoContext { table[it] }

    private fun check(text: String, start: Int, end: Int, word: String, marked: String, phrase: String?) {
        val m = homo.marked(text, start, end, word)
        assertEquals(marked, m)
        assertEquals(phrase, if (homo.hasPhrases(word)) homo.phrase(word, m) else null)
    }

    @Test fun matchesPythonReference() {
        val a = "В лесу шустрые белки прыгали, а в яичнице белки остались жидкими."
        check(a, 2, 6, "лесу", "В [HOMO] лесу [/HOMO] шустрые белки прыгали, а в яичнице белки остались жидкими.", "лес+у")
        check(a, 15, 20, "белки", "В лесу шустрые [HOMO] белки [/HOMO] прыгали, а в яичнице белки остались жидкими.", "б+елки")
        check(a, 42, 47, "белки", "В лесу шустрые белки прыгали, а в яичнице [HOMO] белки [/HOMO] остались жидкими.", null)
        val b = "У самого глаза чуть не вылезли из орбит!!  Он -- молчал..."
        check(b, 2, 8, "самого", "У [HOMO] самого [/HOMO] глаза чуть не вылезли из орбит! он - молчал.", "самог+о")
        check(b, 9, 14, "глаза", "У самого [HOMO] глаза [/HOMO] чуть не вылезли из орбит! он - молчал.", "глаз+а")
        val c = "Он вошёл в замок, а на двери висел ржавый замок."
        check(c, 11, 16, "замок", "Он вошёл в [HOMO] замок [/HOMO] , а на двери висел ржавый замок.", null)
        check(c, 23, 28, "двери", "Он вошёл в замок, а на [HOMO] двери [/HOMO] висел ржавый замок.", "двер+и")
        check(c, 42, 47, "замок", "Он вошёл в замок, а на двери висел ржавый [HOMO] замок [/HOMO] .", null)
        val d = "Я уже знаю, что дорога сюда стала уже."
        check(d, 2, 5, "уже", "Я [HOMO] уже [/HOMO] знаю, что дорога сюда стала уже.", null)
        check(d, 34, 37, "уже", "Я уже знаю, что дорога сюда стала [HOMO] уже [/HOMO] .", null)
        val e = "На кухне закончилась мука, а ожидание было мукой."
        check(e, 21, 25, "мука", "На кухне закончилась [HOMO] мука [/HOMO] , а ожидание было мукой.", null)
    }
}
