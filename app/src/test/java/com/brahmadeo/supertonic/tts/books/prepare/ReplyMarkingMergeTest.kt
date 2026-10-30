package com.brahmadeo.supertonic.tts.books.prepare

import com.brahmadeo.supertonic.tts.books.prepare.RoleMerge
import com.brahmadeo.supertonic.tts.llm.VoiceRole
import com.brahmadeo.supertonic.tts.llm.VoiceRoleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Разметка реплик: решение модели и локальные правила складываются так, что ни одна реплика не остаётся
 * за автором, а narration — автором, и текст не теряет ни одного символа. */
class ReplyMarkingMergeTest {
    private val text = "— Ты уверена? — спросил он."

    @Test fun modelAuthorIsReplacedByLocalSpeech() {
        val model = listOf(VoiceRoleText(text, VoiceRole.AUTHOR))
        val local = listOf(
            VoiceRoleText("— Ты уверена?", VoiceRole.MALE, "ivan"),
            VoiceRoleText(" — спросил он.", VoiceRole.AUTHOR))
        val merged = RoleMerge.merge(model, local)!!
        assertEquals(text, merged.joinToString("") { it.text })
        assertEquals(VoiceRole.MALE, merged.first().role)
        assertEquals("ivan", merged.first().character)
        assertEquals(VoiceRole.AUTHOR, merged.last().role)
    }

    @Test fun modelSpeechWinsOverLocalGuess() {
        val model = listOf(
            VoiceRoleText("— Ты уверена?", VoiceRole.FEMALE, "anna"),
            VoiceRoleText(" — спросил он.", VoiceRole.AUTHOR))
        val local = listOf(
            VoiceRoleText("— Ты уверена?", VoiceRole.MALE, "ivan"),
            VoiceRoleText(" — спросил он.", VoiceRole.AUTHOR))
        val merged = RoleMerge.merge(model, local)!!
        assertEquals(VoiceRole.FEMALE, merged.first().role)
        assertEquals("anna", merged.first().character)
        assertEquals(text, merged.joinToString("") { it.text })
    }

    @Test fun authorStaysAuthorWhenBothAgree() {
        val model = listOf(VoiceRoleText(text, VoiceRole.AUTHOR))
        val merged = RoleMerge.merge(model, model)!!
        assertEquals(VoiceRole.AUTHOR, merged.single().role)
        assertEquals(text, merged.single().text)
    }

    @Test fun differentTextsNeverMerge() {
        val model = listOf(VoiceRoleText(text, VoiceRole.AUTHOR))
        val other = listOf(VoiceRoleText("совсем другой текст", VoiceRole.MALE))
        assertEquals(model, RoleMerge.merge(model, other))
        assertNull(RoleMerge.merge(null, null))
        assertEquals(model, RoleMerge.merge(model, null))
    }

    /** Границы кусков идут ровно по смене голоса: иначе индекс реплик сдвинется на слово. */
    @Test fun spansFollowVoiceChanges() {
        val full = "Он молчал. — Да. — Нет!"
        val model = listOf(VoiceRoleText(full, VoiceRole.AUTHOR))
        val local = listOf(
            VoiceRoleText("Он молчал. ", VoiceRole.AUTHOR),
            VoiceRoleText("— Да.", VoiceRole.FEMALE, "anna"),
            VoiceRoleText(" ", VoiceRole.AUTHOR),
            VoiceRoleText("— Нет!", VoiceRole.MALE, "ivan"))
        val merged = RoleMerge.merge(model, local)!!
        assertEquals(full, merged.joinToString("") { it.text })
        assertEquals(listOf(VoiceRole.AUTHOR, VoiceRole.FEMALE, VoiceRole.AUTHOR, VoiceRole.MALE), merged.map { it.role })
        assertEquals(listOf("anna", "ivan"), merged.filter { it.character != null }.map { it.character })
    }
}
