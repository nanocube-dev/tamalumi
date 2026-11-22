package com.lou.lumi

import com.lou.lumi.brain.EmotionTagFilter
import com.lou.lumi.brain.Persona
import com.lou.lumi.brain.Understanding
import com.lou.lumi.pet.Emotion
import com.lou.lumi.pet.Mood
import com.lou.lumi.pet.PetEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTextTest {

    private fun run(vararg tokens: String): Pair<List<Emotion>, String> {
        val emotions = ArrayList<Emotion>()
        val text = StringBuilder()
        val f = EmotionTagFilter({ emotions += it }, { text.append(it) })
        tokens.forEach { f.feed(it) }
        f.flush()
        return emotions to text.toString()
    }

    @Test fun leadingTagIsSplitOffEvenAcrossTokens() {
        val (e, t) = run("[fr", "oh", "] Ju", "hu, ", "Pizza!")
        assertEquals(listOf(Emotion.JOY), e)
        assertEquals("Juhu, Pizza!", t)
    }

    @Test fun noTagPassesThrough() {
        val (e, t) = run("Hallo ", "du!")
        assertTrue(e.isEmpty())
        assertEquals("Hallo du!", t)
    }

    @Test fun laterTagsAreRemovedAndStillCount() {
        val (e, t) = run("[neugierig] Was ist das? ", "[überr", "ascht] Oh!")
        assertEquals(listOf(Emotion.CURIOUS, Emotion.SURPRISED), e)
        assertEquals("Was ist das? Oh!", t.replace("  ", " "))
    }

    @Test fun brokenTagAtTheEndIsNotSpoken() {
        val (_, t) = run("[müde] Gute Nacht. [scha")
        assertEquals("Gute Nacht. ", t)
    }

    @Test fun bracketTextThatIsNoTagSurvives() {
        val (e, t) = run("Ich habe [das ist ein sehr langer Einschub ohne Ende und mehr] gesagt")
        assertTrue(e.isEmpty())
        assertTrue(t.contains("gesagt"))
    }

    @Test fun echoedStateLineIsNotShownButStillCounts() {
        // seen on the S23: Gemma copied the format of the state line it was given
        val (e, t) = run("<Zust", "and: froh>", " Hallo Welt.", " Ich leuchte.")
        assertEquals(listOf(Emotion.JOY), e)
        assertEquals("Hallo Welt. Ich leuchte.", t)
        val (e2, t2) = run("<Zustand: satt, munter, gute Laune. Stimmung: zufrieden. Es ist Nacht (23:17).> [müde] Gähn.")
        assertEquals(listOf(Emotion.NEUTRAL, Emotion.TIRED), e2)
        assertEquals("Gähn.", t2)
        val (_, t3) = run("Ja! <Zustand: froh> Juhu")
        assertEquals("Ja!  Juhu", t3)
        val (_, t4) = run("Gute Nacht <Zust")
        assertEquals("Gute Nacht ", t4)
    }

    @Test fun feelingEmojisSteerTheFaceAndAreCutOut() {
        val (e, t) = run("😋", " Lecker", "! Danke 🥰")      // 😋 … 🥰
        assertEquals(listOf(Emotion.HUNGRY, Emotion.LOVE), e)
        assertEquals("Lecker! Danke ", t)
        // a surrogate pair split across two tokens (😴)
        val (e2, t2) = run("\uD83D", "\uDE34 Gute Nacht.")
        assertEquals(listOf(Emotion.TIRED), e2)
        assertEquals("Gute Nacht.", t2)
    }

    @Test fun feelingIsGuessedWhenTheTagIsMissing() {
        assertEquals(Emotion.JOY, EmotionTagFilter.guess("Pizza! Gut."))
        assertEquals(Emotion.JOY, EmotionTagFilter.guess("Lecker! Süß."))
        assertEquals(Emotion.HUNGRY, EmotionTagFilter.guess("Hunger! Happa?"))
        assertEquals(Emotion.CURIOUS, EmotionTagFilter.guess("Wie heißen wir?"))
        assertEquals(Emotion.TIRED, EmotionTagFilter.guess("Gute Nacht, ich bin so müde"))
        assertNull(EmotionTagFilter.guess("Der Himmel ist blau."))
    }

    @Test fun tagWordsAreTolerant() {
        assertEquals(Emotion.SURPRISED, EmotionTagFilter.parse("Ueberrascht"))
        assertEquals(Emotion.TIRED, EmotionTagFilter.parse("müde"))
        assertEquals(Emotion.LOVE, EmotionTagFilter.parse("verliebt"))
        assertEquals(Emotion.JOY, EmotionTagFilter.parse("happy"))
        assertEquals(Emotion.GRUMPY, EmotionTagFilter.parse("grummelig"))
        assertNull(EmotionTagFilter.parse("Tisch"))
    }

    @Test fun spokenCareActions() {
        assertEquals(Understanding.Action.FEED_BERRY, Understanding.analyse("Hier, iss was!").action)
        assertEquals(Understanding.Action.FEED_COOKIE, Understanding.analyse("Willst du einen Keks?").action)
        assertEquals(Understanding.Action.PLAY, Understanding.analyse("Lass uns spielen!").action)
        assertEquals(Understanding.Action.PLAY, Understanding.analyse("Umi, lass uns zusammen spielen.").action)   // as Whisper heard it on the S23
        assertEquals(Understanding.Action.PLAY, Understanding.analyse("Hast du Lust zu spielen?").action)
        assertEquals(Understanding.Action.PLAY, Understanding.analyse("Lumi, hast du Lust mit mir zu spielen?").action)
        assertEquals(Understanding.Action.PLAY, Understanding.analyse("Wollen wir was spielen?").action)
        assertNull(Understanding.analyse("Ich spiele gern Fußball.").action)
        assertEquals(Understanding.Action.SLEEP, Understanding.analyse("Gute Nacht, Lumi.").action)
        assertEquals(Understanding.Action.PET, Understanding.analyse("Komm, ich knuddel dich.").action)
        assertNull(Understanding.analyse("Wie spät ist es?").action)
    }

    @Test fun namesAndFacts() {
        val a = Understanding.analyse("Hallo, ich heiße Lou und ich mag Pizza.")
        assertEquals("Lou", a.userName)
        assertTrue(a.facts.contains("Name: Lou"))
        assertNull(Understanding.analyse("Ich bin Lehrer").userName)
        assertEquals(listOf("Lieblingsfarbe: blau"), Understanding.facts("Meine Lieblingsfarbe ist blau"))
        assertEquals(listOf("Hat: Hund namens Bello"), Understanding.facts("Ich habe einen Hund, der heißt Bello"))
        assertEquals(listOf("Merken: ich morgen Zahnarzt habe"), Understanding.facts("Merk dir, dass ich morgen Zahnarzt habe"))
        assertTrue(Understanding.facts("Ich mag dich").isEmpty())
        assertEquals("Funkel", Understanding.analyse("Ich nenne dich ab jetzt Funkel").petName)
        assertEquals("Funkel", Understanding.analyse("Dein Name ist Funkel!").petName)
        assertNull(Understanding.analyse("Wie ist dein Name?").petName)
        assertTrue(Understanding.analyse("Vergiss alles, bitte").forget)
    }

    @Test fun personaCarriesNameStageAndMemories() {
        val t0 = 1_700_000_000_000L
        val s = PetEngine.tapEgg(PetEngine.newEgg(t0).copy(eggWarmth = 99.0), t0).state
            .copy(name = "Funkel", userName = "Lou", memories = listOf("Mag: Pizza"))
        val p = Persona.systemPrompt(s, t0 + 60_000)
        assertTrue(p.contains("Funkel"))
        assertTrue(p.contains("Lou"))
        assertTrue(p.contains("Mag: Pizza"))
        assertTrue(p.contains("😊 froh"))       // 😊 froh
        assertTrue("baby speaks in short words", p.contains("geschlüpft"))
        val line = Persona.stateLine(s.copy(satiety = 10.0), Mood.HUNGRY, t0, "Dein Mensch gibt dir gerade eine Leuchtbeere.")
        assertTrue(line.startsWith("<Zustand:"))
        assertTrue(line.contains("großer Hunger"))
        assertTrue(line.contains("Leuchtbeere"))
        assertFalse(line.contains("\n"))
    }
}
