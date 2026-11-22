package com.lou.lumi.ui

import android.graphics.Color
import com.lou.lumi.pet.Emotion
import com.lou.lumi.pet.Mood

/**
 * The six colours of one look: body light → mid → deep, two accents that drift inside the body
 * (the „living gradient“), and the glow around it. Moods and feelings each get one; the pet's own
 * [hue] shift is applied on top so every Lumi has its own colouring.
 */
class Palette(
    val light: Int, val mid: Int, val deep: Int,
    val accentA: Int, val accentB: Int, val glow: Int,
) {
    fun toArray() = intArrayOf(light, mid, deep, accentA, accentB, glow)

    companion object {
        fun of(vararg hex: String) = hex.map { Color.parseColor(it) }.let { Palette(it[0], it[1], it[2], it[3], it[4], it[5]) }

        val CONTENT = of("#D4ECFF", "#86A9FF", "#5546D6", "#5FE3D6", "#F2A2FF", "#7C9BFF")
        val HAPPY = of("#FFE6CC", "#FF9E8C", "#EE4F8C", "#FFD166", "#C58EFF", "#FF8FA8")
        val EXCITED = of("#FFF3BF", "#FF8FB3", "#E33F8B", "#6EF2FF", "#FFC24D", "#FF7AC6")
        val LOVE = of("#FFE3F2", "#FF9CCB", "#E24E8E", "#FFC6E3", "#FF6FAA", "#FF80BA")
        val SLEEPY = of("#BFC5FF", "#7079DE", "#2D2D8A", "#8E7CFF", "#4A6BFF", "#5E6BFF")
        val HUNGRY = of("#FFE9BD", "#FFAE66", "#E2603C", "#FFD47D", "#FF8A5B", "#FFB067")
        val SAD = of("#D6E0EC", "#94A6C3", "#4B5B7E", "#A9B8E8", "#8AA0C8", "#8FA6CC")
        val GRUMPY = of("#E6DCF3", "#AA9BCB", "#5D517E", "#C6B8E8", "#8E86B0", "#A398C8")
        val CURIOUS = of("#DAFFF6", "#7EE6D4", "#2B8DB6", "#B6F27C", "#7CC8FF", "#5FE0D0")
        val SURPRISED = of("#F6F2FF", "#BAA9FF", "#6C4ADF", "#8FF0FF", "#FFB3E6", "#B49CFF")
        val THOUGHTFUL = of("#E4E2FF", "#A99DFF", "#5A3ED4", "#7FD4FF", "#D7A9FF", "#9C8CFF")
        val SILLY = of("#FFF7B5", "#FFB86B", "#FF5DA0", "#8CFFB2", "#7FD6FF", "#FFB86B")
        val EGG = of("#FFFFFF", "#ECE7FF", "#B9B0E6", "#BDF4FF", "#FFD3F1", "#D9D2FF")
        /** Mixed in while Lumi listens / thinks, like the colour shift of a voice assistant orb. */
        val LISTEN = of("#E6FDFF", "#8EEBFF", "#2C8FD8", "#9DF3FF", "#7AA8FF", "#7FEFFF")
        val THINK = of("#EEE6FF", "#B79CFF", "#5B35CF", "#7FD4FF", "#F0A8FF", "#A98CFF")

        fun forMood(m: Mood): Palette = when (m) {
            Mood.HAPPY -> HAPPY
            Mood.CONTENT -> CONTENT
            Mood.HUNGRY -> HUNGRY
            Mood.SLEEPY, Mood.ASLEEP -> SLEEPY
            Mood.BORED -> GRUMPY
            Mood.LONELY -> SAD
        }

        fun forEmotion(e: Emotion): Palette? = when (e) {
            Emotion.NEUTRAL -> null
            Emotion.JOY -> HAPPY
            Emotion.EXCITED -> EXCITED
            Emotion.LOVE -> LOVE
            Emotion.CURIOUS -> CURIOUS
            Emotion.TIRED -> SLEEPY
            Emotion.HUNGRY -> HUNGRY
            Emotion.SAD -> SAD
            Emotion.SURPRISED -> SURPRISED
            Emotion.SILLY -> SILLY
            Emotion.THOUGHTFUL -> THOUGHTFUL
            Emotion.GRUMPY -> GRUMPY
        }

        /** Rotate the hue of every colour by [deg] (the pet's personal colour). */
        fun shifted(src: IntArray, deg: Float, out: IntArray = IntArray(src.size)): IntArray {
            if (deg == 0f) { src.copyInto(out); return out }
            val hsv = FloatArray(3)
            for (i in src.indices) {
                Color.colorToHSV(src[i], hsv)
                hsv[0] = (hsv[0] + deg + 360f) % 360f
                out[i] = Color.HSVToColor(Color.alpha(src[i]), hsv)
            }
            return out
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
            )
        }

        fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
    }
}
