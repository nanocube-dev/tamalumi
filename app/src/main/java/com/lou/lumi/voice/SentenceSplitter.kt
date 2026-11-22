package com.lou.lumi.voice

/**
 * Splits the LLM token stream into speakable chunks so TTS can start while the
 * model is still generating (the "streaming" part of the demo).
 */
class SentenceSplitter(
    private val minChars: Int = 14,
    private val maxChars: Int = 180,
    private val emit: (String) -> Unit,
) {
    private val buf = StringBuilder()

    fun feed(token: String) {
        buf.append(token)
        while (true) {
            val cut = findCut(buf) ?: break
            val chunk = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (chunk.isNotBlank()) emit(chunk)
        }
    }

    fun flush(): String? {
        val s = buf.toString().trim()
        buf.setLength(0)
        return s.takeIf { it.isNotBlank() }
    }

    private fun findCut(s: CharSequence): Int? {
        for (i in s.indices) {
            val ch = s[i]
            val isEnd = ch == '.' || ch == '!' || ch == '?' || ch == '\n' || ch == ';' || ch == ':'
            if (!isEnd) continue
            if (i + 1 < minChars) continue
            // keep decimals / abbreviations like "3.5" or "z. B." together
            if (ch == '.') {
                val prev = s.getOrNull(i - 1)
                val next = s.getOrNull(i + 1)
                if (prev != null && next != null && prev.isDigit() && next.isDigit()) continue
                if (next != null && next.isLowerCase() && i + 1 < minChars * 2 && s.length < maxChars / 2) continue
            }
            return i + 1
        }
        if (buf.length >= maxChars) {
            val sp = buf.lastIndexOf(" ", maxChars - 1)
            if (sp > 0) return sp + 1
            return maxChars
        }
        return null
    }
}
