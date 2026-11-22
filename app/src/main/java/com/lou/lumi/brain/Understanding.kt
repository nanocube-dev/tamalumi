package com.lou.lumi.brain

import com.lou.lumi.pet.Food

/**
 * What a sentence from the human *does*, besides being answered: care actions said out loud
 * („Hier, iss was“), facts worth remembering („Ich heiße Lou“) and renaming („Ich nenne dich
 * Funkel“). Rule-based on purpose: it has to work instantly and identically every time, and the
 * 2-B model is better at chatting than at reliable structured output.
 */
object Understanding {

    enum class Action { FEED_BERRY, FEED_COOKIE, PLAY, SLEEP, WAKE, PET }

    data class Result(
        val action: Action? = null,
        val facts: List<String> = emptyList(),
        val userName: String? = null,
        val petName: String? = null,
        val forget: Boolean = false,
    )

    fun analyse(text: String): Result {
        val t = " " + text.lowercase().replace(Regex("[.,!?;:„“\"]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        return Result(
            action = action(t),
            facts = facts(text),
            userName = userName(text),
            petName = petName(text),
            forget = Regex(" vergiss (alles|was du über mich weißt|meine daten) ").containsMatchIn(t),
        )
    }

    fun foodFor(a: Action?): Food? = when (a) {
        Action.FEED_BERRY -> Food.BERRY
        Action.FEED_COOKIE -> Food.COOKIE
        else -> null
    }

    private fun action(t: String): Action? = when {
        has(t, " keks", " kekse", " süßigkeit", " naschen", " schokolade", " kuchen", " cookie") &&
            !has(t, " kein keks", " keine kekse") -> Action.FEED_COOKIE
        has(t, " iss ", " essen ", " füttere", " fütter ", " futter", " happa", " mampf", " beere", " apfel",
            " hast du hunger", " bist du hungrig", " snack", " eat ") -> Action.FEED_BERRY
        PLAY.containsMatchIn(t) -> Action.PLAY
        has(t, " gute nacht", " schlaf gut", " schlaf jetzt", " geh schlafen", " ab ins bett", " ins bett",
            " schlafenszeit", " good night") -> Action.SLEEP
        has(t, " aufwachen", " wach auf", " guten morgen", " aufstehen", " wake up") -> Action.WAKE
        has(t, " streichel", " knuddel", " kuschel", " drück dich", " umarm") -> Action.PET
        else -> null
    }

    private fun has(t: String, vararg keys: String) = keys.any { t.contains(it) }

    /** „Lass uns (zusammen) spielen“, „Hast du Lust zu spielen?“, „Spielen wir was?“ … */
    private val PLAY = Regex(
        """ (lass|lasst) uns( \S+){0,3} spielen| (wollen|sollen|können) wir( \S+){0,3} spielen| (willst|magst) du( \S+){0,3} spielen""" +
            """| spielen wir| spiel mit mir| (lust|bock)( \S+){0,4} spiel| glühwürmchen| let's play| lets play"""
    )

    private val NAME_WORD = "([A-ZÄÖÜ][a-zäöüß]+(?:-[A-ZÄÖÜ][a-zäöüß]+)?)"

    /** Words that follow „ich bin …“ but are not names. */
    private val NOT_NAMES = setOf(
        "Müde", "Hungrig", "Traurig", "Froh", "Hier", "Da", "Wieder", "Zuhause", "Fertig", "Ich", "Nicht",
        "Ein", "Eine", "Der", "Die", "Das", "So", "Sehr", "Gerade", "Jetzt", "Auch", "Noch", "Krank", "Okay",
        "Bitte", "Doch", "Mal", "Einfach", "Ab", "Ja", "Nein", "Du", "Dich", "Mich", "Nun", "Also",
    )

    fun userName(text: String): String? {
        // Only explicit phrasings: „Ich bin Lehrer“ must not turn the human into „Lehrer“.
        val p = Regex("\\b(?:ich heiße|ich heisse|mein name ist|nenn mich|my name is|call me)\\s+$NAME_WORD", RegexOption.IGNORE_CASE)
        val m = p.find(text) ?: return null
        val n = m.groupValues[1].replaceFirstChar { it.uppercaseChar() }
        return n.takeIf { it !in NOT_NAMES && it.length in 2..20 }
    }

    fun petName(text: String): String? {
        val p = Regex(
            "\\b(?:ich nenne dich|ich taufe dich|du heißt|du heisst|dein name ist|dein neuer name ist)" +
                "(?:\\s+(?:ab jetzt|ab sofort|ab heute|jetzt|einfach|auf den namen))?\\s+$NAME_WORD",
            RegexOption.IGNORE_CASE,
        )
        val m = p.find(text) ?: return null
        val n = m.groupValues[1].replaceFirstChar { it.uppercaseChar() }
        return n.takeIf { it.length in 2..16 && it !in NOT_NAMES }
    }

    /** „Ich mag Pizza.“ → „Mag: Pizza“. Short, label-first facts keep the prompt small. */
    fun facts(text: String): List<String> {
        val out = ArrayList<String>()
        val clean = text.trim().trimEnd('.', '!', '?')
        fun grab(re: String, label: String, maxWords: Int = 6) {
            val m = Regex(re, RegexOption.IGNORE_CASE).find(clean) ?: return
            val v = m.groupValues.last().trim().trimEnd('.', '!', '?', ',')
            val words = v.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty() || words.size > maxWords) return
            out += "$label: ${words.joinToString(" ")}"
        }
        userName(text)?.let { out += "Name: $it" }
        grab("\\bmerk dir(?:,)?\\s+(?:dass\\s+)?(.+)$", "Merken", 14)
        // „Meine Lieblingsfarbe ist blau“ → „Lieblingsfarbe: blau“ (the category is the useful part)
        Regex("\\bmein(?:e)? lieblings(\\w+) (?:ist|sind) (.+)$", RegexOption.IGNORE_CASE).find(clean)?.let { m ->
            val v = m.groupValues[2].trim()
            if (v.split(' ').size <= 6) out += "Lieblings${m.groupValues[1].lowercase()}: $v"
        }
        grab("\\bich (?:mag|liebe) (?!dich\\b|es\\b|das\\b)(.+)$", "Mag")
        grab("\\bich (?:hasse|mag keine?n?|mag kein) (.+)$", "Mag nicht")
        grab("\\bich (?:wohne|lebe) in (.+)$", "Wohnort", 4)
        grab("\\bich arbeite als (.+)$", "Beruf", 4)
        grab("\\bich bin (\\d{1,3}) jahre alt\\b", "Alter", 1)
        grab("\\bich habe am (\\d{1,2}\\.\\s?\\w+) geburtstag\\b", "Geburtstag", 3)
        Regex(
            "\\bich habe (?:einen|eine|ein) (hund|katze|kater|hamster|pferd|kaninchen|vogel|wellensittich|freund|freundin|mann|frau|sohn|tochter|bruder|schwester)(?:,? (?:der|die|das|er|sie|es) (?:heißt|heisst) (\\w+))?",
            RegexOption.IGNORE_CASE,
        ).find(clean)?.let { m ->
            val who = m.groupValues[1].replaceFirstChar { it.uppercaseChar() }
            val name = m.groupValues[2]
            out += if (name.isBlank()) "Hat: $who" else "Hat: $who namens ${name.replaceFirstChar { it.uppercaseChar() }}"
        }
        return out.distinct()
    }
}
