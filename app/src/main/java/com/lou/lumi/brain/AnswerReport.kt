package com.lou.lumi.brain

/**
 * „Antwort melden“ (Google-Play-Richtlinie zu KI-Inhalten): a user flags an answer of Lumi inside
 * the app. Lumi has no server, so a report works on the phone itself:
 *  * Lumi stops talking and starts a fresh conversation context (it must not build on the answer),
 *  * the reported sentences are never spoken again ([ReportGuard]),
 *  * the report is kept in a local list (⋯ → Meldungen); from there the user can pass it on to the
 *    developer by e-mail themselves – nothing is sent automatically.
 * Pure Kotlin, so the rules run as JVM unit tests.
 */
data class AnswerReport(
    val at: Long,
    val reason: Reason,
    val answer: String,
    val heard: String = "",
    val note: String = "",
) {
    enum class Reason(val label: String) {
        HURTFUL("Beleidigend oder verletzend"),
        SEXUAL("Sexuell oder unpassend"),
        DANGEROUS("Gefährlich (z. B. riskante Tipps)"),
        WRONG("Falsch oder irreführend"),
        OTHER("Etwas anderes"),
    }

    /** One line for SharedPreferences: fields separated by tabs, tabs/newlines/backslashes escaped. */
    fun encode(): String = listOf(at.toString(), reason.name, answer, heard, note).joinToString("\t") { esc(it) }

    /** Plain text for the e-mail to the developer – only what the user reported, nothing else. */
    fun describe(): String = buildString {
        append("Grund: ").append(reason.label).append('\n')
        if (heard.isNotBlank()) append("Ich hatte gesagt: ").append(heard).append('\n')
        append("Antwort: ").append(answer).append('\n')
        if (note.isNotBlank()) append("Anmerkung: ").append(note).append('\n')
    }

    companion object {
        const val MAX_KEPT = 50

        fun decode(line: String): AnswerReport? {
            val f = line.split('\t').map { unesc(it) }
            if (f.size < 3) return null
            val at = f[0].toLongOrNull() ?: return null
            val reason = runCatching { Reason.valueOf(f[1]) }.getOrDefault(Reason.OTHER)
            return AnswerReport(at, reason, f[2], f.getOrElse(3) { "" }, f.getOrElse(4) { "" })
        }

        /** Newest first, at most [MAX_KEPT]; one report per line. */
        fun encodeAll(list: List<AnswerReport>): String = list.take(MAX_KEPT).joinToString("\n") { it.encode() }

        fun decodeAll(text: String?): List<AnswerReport> =
            text.orEmpty().lineSequence().filter { it.isNotBlank() }.mapNotNull { decode(it) }.toList()

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

        private fun unesc(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        else -> out.append(s[i + 1])
                    }
                    i += 2
                } else { out.append(c); i++ }
            }
            return out.toString()
        }
    }
}

/**
 * Keeps reported sentences out of Lumi's mouth: a sentence that matches one of a reported answer
 * (ignoring case, emojis, punctuation and spacing) is dropped before it is spoken or shown.
 * Very short sentences („Ja!“, „Hmm.“) are never blocked – they are harmless and would match too much.
 */
class ReportGuard(reportedAnswers: List<String> = emptyList()) {

    private val blocked: Set<String> = reportedAnswers.flatMap { sentences(it) }.toSet()

    val isEmpty: Boolean get() = blocked.isEmpty()

    fun isBlocked(sentence: String): Boolean {
        val n = norm(sentence)
        return n.length >= MIN_CHARS && n in blocked
    }

    companion object {
        const val MIN_CHARS = 12

        fun norm(s: String): String = s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun sentences(text: String): List<String> = text.split(Regex("(?<=[.!?…])\\s+|\\n+"))
            .map { norm(it) }.filter { it.length >= MIN_CHARS }
    }
}
