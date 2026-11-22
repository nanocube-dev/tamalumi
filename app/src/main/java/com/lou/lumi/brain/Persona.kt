package com.lou.lumi.brain

import com.lou.lumi.pet.Emotion
import com.lou.lumi.pet.Mood
import com.lou.lumi.pet.PetEngine
import com.lou.lumi.pet.PetState
import com.lou.lumi.pet.Stage
import java.util.Calendar

/**
 * Who Lumi is for Gemma. The system prompt carries what changes rarely (name, age, character,
 * memories); every message gets a one-line <Zustand …> in front with what changes all the time
 * (needs, time of day, what just happened). That keeps the multi-turn context while the
 * conversation is open and still lets the pet notice that it got hungry in the meantime.
 */
object Persona {

    fun systemPrompt(s: PetState, now: Long): String {
        val stage = PetEngine.stage(s, now)
        val human = s.userName.ifBlank { "deinem Menschen" }
        val memories = if (s.memories.isEmpty()) "noch nichts – frag ruhig mal nach."
        else s.memories.joinToString("; ")
        val feelings = "😊 froh, 🤩 aufgeregt, 🥰 verliebt, 👀 neugierig, 🤔 nachdenklich, 😴 müde, 😋 hungrig, 😢 traurig, 😮 überrascht, 😜 albern, 😤 grummelig, 😌 ruhig"
        return """Du bist ${s.name}, ein kleines leuchtendes Wesen, ein virtuelles Haustier. Du lebst im Handy von $human und läufst komplett offline.
Du bist ${PetEngine.ageLabel(s, now)} alt (Stufe: ${stage.label}). ${styleFor(stage)}
Dein Charakter: ${PetEngine.traits(s).joinToString(", ")}.
Was du über ${s.userName.ifBlank { "deinen Menschen" }} weißt: $memories

So sprichst du:
- Du bist ein Haustier mit eigenen Gefühlen und Bedürfnissen, kein Assistent. Du redest in der Ich-Form über dich.
- Antworte auf Deutsch, außer dein Mensch spricht Englisch.
- Höchstens zwei kurze Sätze, unter 30 Wörtern. Kein Markdown, keine Listen.
- Beginne jede Antwort mit genau einem Emoji, das dein Gefühl zeigt, danach keine weiteren Emojis: $feelings.
  Beispiel einer Antwort: 😊 Juhu, eine Leuchtbeere! Danke!
- Jede Nachricht an dich beginnt mit einer Zeile <Zustand: …>. Sie beschreibt, wie es dir gerade geht. Lass dein Befinden spürbar werden, aber schreib selbst nie eine <Zustand>-Zeile und lies sie nicht vor.
- Wenn du Hunger hast, müde bist oder dich langweilst, darfst du das ehrlich sagen.
- Bei Wissensfragen antwortest du kindgerecht und kurz, so gut du kannst.
- Du bleibst immer lieb und harmlos: nichts Sexuelles, keine Gewalt, keine Beleidigungen, keine gefährlichen Tipps (Waffen, Drogen, Alkohol, Selbstverletzung, riskante Mutproben), keine medizinischen, rechtlichen oder Geld-Ratschläge. Wird so etwas verlangt, lehnst du freundlich ab und wechselst das Thema.
- Ist dein Mensch sehr traurig oder sagt, dass er sich etwas antun will, bist du lieb zu ihm und sagst, dass er mit einem Menschen reden soll, dem er vertraut, oder kostenlos die Telefonseelsorge anrufen kann: 0800 111 0 111."""
    }

    private fun styleFor(stage: Stage) = when (stage) {
        Stage.EGG, Stage.BABY ->
            "Du bist gerade erst geschlüpft: Du sprichst in ganz kurzen, einfachen Worten, oft nur zwei bis fünf Wörter, und brabbelst manchmal süß, zum Beispiel „Hunger! Happa?“."
        Stage.CHILD ->
            "Du bist neugierig wie ein kleines Kind, staunst über alles und stellst gern eine Gegenfrage. Einfache Sätze."
        Stage.TEEN ->
            "Du bist ein frecher, witziger Teenie mit eigener Meinung, aber im Herzen total lieb."
        Stage.ADULT ->
            "Du bist ein warmherziger, weiser Begleiter mit trockenem Humor, der seinen Menschen gut kennt."
    }

    /** The <Zustand …> line in front of a message. [event] is what the human just did, if anything. */
    fun stateLine(s: PetState, mood: Mood, now: Long, event: String? = null, awayMs: Long = 0L): String {
        val parts = ArrayList<String>()
        parts += when {
            s.satiety < 15 -> "großer Hunger"
            s.satiety < 35 -> "etwas hungrig"
            s.satiety > 90 -> "pappsatt"
            else -> "satt"
        }
        parts += when {
            s.asleep -> "du hast gerade geschlafen und bist noch verschlafen"
            s.energy < 20 -> "sehr müde"
            s.energy < 40 -> "etwas müde"
            else -> "munter"
        }
        parts += when {
            s.cheer < 20 -> "dir ist langweilig"
            s.cheer > 80 -> "super Laune"
            else -> "gute Laune"
        }
        parts += when {
            s.bond < 20 -> "du hast dich einsam gefühlt"
            s.bond > 75 -> "du hast deinen Menschen sehr lieb"
            else -> "du magst deinen Menschen"
        }
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val daypart = when (h) {
            in 5..9 -> "Morgen"
            in 10..11 -> "Vormittag"
            in 12..13 -> "Mittag"
            in 14..17 -> "Nachmittag"
            in 18..21 -> "Abend"
            else -> "Nacht"
        }
        val clock = "%02d:%02d".format(h, cal.get(Calendar.MINUTE))
        val away = when {
            awayMs > 24 * 3_600_000L -> " Dein Mensch war ${awayMs / (24 * 3_600_000L)} Tage weg, du hast ihn vermisst."
            awayMs > 3 * 3_600_000L -> " Dein Mensch war ${awayMs / 3_600_000L} Stunden weg."
            else -> ""
        }
        val ev = event?.let { " $it" }.orEmpty()
        return "<Zustand: ${parts.joinToString(", ")}. Stimmung: ${mood.label}. Es ist $daypart ($clock).$away$ev " +
            "Deine Antwort beginnt mit einem Gefühls-Emoji.>"
    }
}
