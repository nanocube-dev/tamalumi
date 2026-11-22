package com.lou.lumi.brain

import com.lou.lumi.pet.Emotion

/**
 * Gemma is asked to open every answer with a feeling emoji – „😊 Juhu!“ (on the S23 the 2-B model
 * ignored „[froh]“-style tags but uses emojis willingly); bracket tags and an echoed <Zustand …>
 * line are understood as well. This filter
 * sits on the token stream: it holds the first few characters back until it knows whether a tag
 * is coming, reports the emotion once, and passes only speakable text on. Tags that show up later
 * in the answer (models do that) are removed as well; they change the face mid-sentence.
 */
class EmotionTagFilter(
    private val onEmotion: (Emotion) -> Unit,
    private val onText: (String) -> Unit,
) {
    private val head = StringBuilder()
    private var headDone = false
    private val pending = StringBuilder()
    private var emitted = false

    private var carry = ""

    /**
     * Last stage: feeling emojis (what Gemma uses most reliably, see [EMOJI]) are reported and cut
     * out – Piper cannot say them and the caption stays clean – then text goes out without the
     * whitespace that followed a leading tag.
     */
    private fun emit(t: String) {
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val n = Character.charCount(cp)
            if (isEmoji(cp)) EMOJI[cp]?.let(onEmotion) else sb.appendCodePoint(cp)
            i += n
        }
        val x = if (emitted) sb.toString() else sb.toString().trimStart()
        if (x.isEmpty()) return
        emitted = true
        onText(x)
    }

    fun feed(raw: String) {
        // an emoji may arrive split between two tokens – never cut a surrogate pair
        var token = carry + raw
        carry = ""
        if (token.isNotEmpty() && token.last().isHighSurrogate()) { carry = token.takeLast(1); token = token.dropLast(1) }
        if (token.isEmpty()) return
        if (!headDone) {
            head.append(token)
            val t = head.toString()
            val trimmed = t.trimStart()
            if (trimmed.isEmpty()) return
            val open = trimmed[0]
            val close = CLOSERS[open]
            if (close == null) {
                headDone = true
                passOn(t)
                return
            }
            val end = trimmed.indexOf(close, 1)
            if (end < 0) {
                if (trimmed.length > limit(open)) {     // not a tag after all
                    headDone = true
                    passOn(t)
                }
                return
            }
            headDone = true
            parse(trimmed.substring(1, end))?.let(onEmotion)
            passOn(trimmed.substring(end + 1).trimStart(' ', ':', '-', '\n'))
            return
        }
        passOn(token)
    }

    /** End of the answer: whatever is still held back is text. */
    fun flush() {
        carry = ""
        if (!headDone) {
            headDone = true
            val t = head.toString()
            val tr = t.trimStart()
            val close = tr.firstOrNull()?.let { CLOSERS[it] }
            if (close == null || tr.indexOf(close, 1) >= 0) passOn(t)    // an unclosed opening tag is dropped
        }
        if (pending.isNotEmpty()) {
            val rest = pending.toString()
            pending.setLength(0)
            // an unterminated „[…“ / „<…“ at the very end is a broken tag, not speech
            val cut = INLINE.keys.map { o -> rest.lastIndexOf(o).takeIf { it >= 0 && rest.indexOf(INLINE.getValue(o), it) < 0 } ?: -1 }
                .filter { it >= 0 }.minOrNull()
            emit(if (cut != null) rest.substring(0, cut) else rest)
        }
    }

    /** Later tags – [froh] or an echoed <Zustand: …> – are stripped, but still steer the face. */
    private fun passOn(text: String) {
        if (text.isEmpty()) return
        pending.append(text)
        val s = pending.toString()
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val closeCh = INLINE[c]
            if (closeCh != null) {
                val close = s.indexOf(closeCh, i + 1)
                if (close < 0) {
                    if (s.length - i <= limit(c)) {              // might still close: keep it back
                        pending.setLength(0); pending.append(s.substring(i))
                        if (out.isNotEmpty()) emit(out.toString())
                        return
                    }
                    out.append(c); i++; continue
                }
                if (close - i <= limit(c)) {
                    parse(s.substring(i + 1, close))?.let(onEmotion)
                    i = close + 1
                    continue
                }
            }
            out.append(c); i++
        }
        pending.setLength(0)
        if (out.isNotEmpty()) emit(out.toString())
    }

    companion object {
        private const val MAX_TAG = 24
        /** Gemma sometimes echoes the whole <Zustand: …> line it was given – that can be long. */
        private const val MAX_STATE_ECHO = 260
        private val CLOSERS = mapOf('[' to ']', '(' to ')', '*' to '*', '<' to '>')
        private val INLINE = mapOf('[' to ']', '<' to '>')
        private fun limit(open: Char) = if (open == '<') MAX_STATE_ECHO else MAX_TAG

        /** Tag word → emotion; tolerant to case, umlaut spellings and English answers. */
        fun parse(raw: String): Emotion? {
            val words = raw.lowercase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .split(Regex("[^a-z]+")).filter { it.isNotEmpty() && it !in LABELS }
            return words.firstNotNullOfOrNull { word(it) }
        }

        /**
         * When Gemma forgets the tag (the 2-B model does, especially in baby talk): a feeling from
         * the first sentence itself. Rough on purpose – it only has to pick a fitting face.
         */
        fun guess(sentence: String): Emotion? {
            val t = " " + sentence.lowercase() + " "
            return when {
                Regex("hunger|hungrig|happa|futter").containsMatchIn(t) -> Emotion.HUNGRY
                Regex("müde|gähn|schlaf|nacht").containsMatchIn(t) -> Emotion.TIRED
                Regex("lieb dich|hab dich lieb|kuschel|knuddel|herz").containsMatchIn(t) -> Emotion.LOVE
                Regex("traurig|schade|einsam|vermiss|weinen|allein").containsMatchIn(t) -> Emotion.SAD
                Regex("""oh!|wow|was\?!|wirklich\?""").containsMatchIn(t) -> Emotion.SURPRISED
                Regex("hihi|haha|kicher|lustig|quatsch").containsMatchIn(t) -> Emotion.SILLY
                Regex("hmpf|nein!|will nicht|blöd|doof").containsMatchIn(t) -> Emotion.GRUMPY
                Regex("juhu|yay|super|toll|cool|freu|danke|lecker|mjam|mampf|hallo|hi |!").containsMatchIn(t) -> Emotion.JOY
                t.trimEnd().endsWith("?") -> Emotion.CURIOUS
                else -> null
            }
        }

        private fun isEmoji(cp: Int) = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D

        /** The feeling emojis the persona asks for, plus their close relatives. */
        val EMOJI: Map<Int, Emotion> = buildMap {
            fun put(e: Emotion, vararg cps: Int) = cps.forEach { put(it, e) }
            put(Emotion.JOY, 0x1F60A, 0x1F642, 0x1F600, 0x1F603, 0x1F604, 0x1F601, 0x263A, 0x2600, 0x2728)
            put(Emotion.EXCITED, 0x1F929, 0x1F973, 0x1F606, 0x1F389, 0x1F31F)
            put(Emotion.LOVE, 0x1F970, 0x1F60D, 0x2764, 0x1F495, 0x1F618, 0x1F496, 0x1F497, 0x1F49E, 0x1F917)
            put(Emotion.CURIOUS, 0x1F440, 0x1F9D0)
            put(Emotion.THOUGHTFUL, 0x1F914)
            put(Emotion.TIRED, 0x1F634, 0x1F971, 0x1F62A, 0x1F4A4)
            put(Emotion.HUNGRY, 0x1F60B, 0x1F924)
            put(Emotion.SAD, 0x1F622, 0x1F62D, 0x1F97A, 0x1F614, 0x2639, 0x1F641, 0x1F61E)
            put(Emotion.SURPRISED, 0x1F62E, 0x1F632, 0x1F62F, 0x1F633, 0x1F92F)
            put(Emotion.SILLY, 0x1F61C, 0x1F61D, 0x1F92A, 0x1F61B, 0x1F602, 0x1F92D)
            put(Emotion.GRUMPY, 0x1F624, 0x1F620, 0x1F612, 0x1F611, 0x1F644)
            put(Emotion.NEUTRAL, 0x1F60C, 0x1F607)
        }

        private val LABELS = setOf("zustand", "stimmung", "gefuehl", "emotion", "mood", "feeling")

        private fun word(word: String): Emotion? {
            return when {
                word.startsWith("froh") || word.startsWith("gluecklich") || word.startsWith("happy") ||
                    word.startsWith("freud") || word.startsWith("lach") -> Emotion.JOY
                word.startsWith("aufgeregt") || word.startsWith("begeistert") || word.startsWith("excited") -> Emotion.EXCITED
                word.startsWith("verliebt") || word.startsWith("liebe") || word.startsWith("love") ||
                    word.startsWith("kusch") || word.startsWith("zaertlich") -> Emotion.LOVE
                word.startsWith("neugier") || word.startsWith("curious") || word.startsWith("interess") -> Emotion.CURIOUS
                word.startsWith("muede") || word.startsWith("schlaefrig") || word.startsWith("tired") || word.startsWith("sleepy") -> Emotion.TIRED
                word.startsWith("hungrig") || word.startsWith("hunger") || word.startsWith("hungry") -> Emotion.HUNGRY
                word.startsWith("traurig") || word.startsWith("sad") || word.startsWith("einsam") || word.startsWith("lonely") -> Emotion.SAD
                word.startsWith("ueberrascht") || word.startsWith("erstaunt") || word.startsWith("surprised") || word.startsWith("staun") -> Emotion.SURPRISED
                word.startsWith("albern") || word.startsWith("frech") || word.startsWith("silly") || word.startsWith("verspielt") -> Emotion.SILLY
                word.startsWith("nachdenk") || word.startsWith("thoughtful") || word.startsWith("grueb") -> Emotion.THOUGHTFUL
                word.startsWith("grummel") || word.startsWith("genervt") || word.startsWith("beleidigt") ||
                    word.startsWith("grumpy") || word.startsWith("trotzig") || word.startsWith("wuetend") -> Emotion.GRUMPY
                word.startsWith("ruhig") || word.startsWith("zufrieden") || word.startsWith("calm") || word.startsWith("neutral") -> Emotion.NEUTRAL
                else -> null
            }
        }
    }
}
