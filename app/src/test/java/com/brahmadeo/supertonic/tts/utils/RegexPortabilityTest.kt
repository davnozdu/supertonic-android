package com.brahmadeo.supertonic.tts.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Android compiles regular expressions with ICU, which rejects a `{` or `}` that is not a quantifier (`\{\d}}`);
 * the JVM running the tests accepts it. Book preparation crashed on the phone for that reason (beta8–23). */
class RegexPortabilityTest {
    private fun braces(pattern: String): Boolean {
        var i = 0; var inClass = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 2 < pattern.length && pattern[i + 1] in "pPxN" && pattern[i + 2] == '{' -> { i = pattern.indexOf('}', i) + 1; continue }
                c == '\\' -> { i += 2; continue }
                c == '[' -> inClass++
                c == ']' && inClass > 0 -> inClass--
                c == '{' && inClass == 0 -> {
                    val q = Regex("\\{\\d+(,\\d*)?\\}").find(pattern, i)?.takeIf { it.range.first == i } ?: return false
                    i = q.range.last + 1; continue
                }
                c == '}' && inClass == 0 -> return false
            }
            i++
        }
        return true
    }

    @Test fun everyRegexLiteralCompilesOnAndroid() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val literal = Regex("Regex\\(\"((?:[^\"\\\\]|\\\\.)*)\"")
        val bad = root.walk().filter { it.extension == "kt" }.flatMap { f ->
            literal.findAll(f.readText()).map { it.groupValues[1].replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\") }
                .filter { !braces(it) }.map { "${f.name}: $it" }
        }.toList()
        assertEquals(emptyList<String>(), bad)
    }
}
