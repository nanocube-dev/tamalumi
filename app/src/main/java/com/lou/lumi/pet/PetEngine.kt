package com.lou.lumi.pet

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The rules of the pet. Pure functions over [PetState] (no clock, no Android): every call gets
 * `now`, so the time the app was closed is simulated exactly like time it was open.
 */
object PetEngine {

    const val HOUR = 3_600_000.0
    /** An untouched egg hatches by itself after this long (tapping it makes it faster). */
    const val EGG_AUTO_HATCH_MS = 3 * 60_000L
    const val EGG_TAPS_TO_HATCH = 12
    /** Longer absences are capped, so a forgotten Lumi is sad but not „game over“. */
    private const val MAX_CATCH_UP_MS = 14L * 24 * 3_600_000
    private const val STEP_MS = 5 * 60_000L

    /** Per hour, awake. Full → empty: satiety ~10 h, energy ~16 h, fun ~8 h, bond ~3 days. */
    private const val SATIETY_PER_H = -10.0
    private const val ENERGY_PER_H = -6.0
    private const val FUN_PER_H = -12.0
    private const val BOND_PER_H = -1.4
    /** Per hour, asleep. */
    private const val SLEEP_SATIETY_PER_H = -3.5
    private const val SLEEP_ENERGY_PER_H = 22.0
    private const val SLEEP_FUN_PER_H = -1.5
    private const val SLEEP_BOND_PER_H = -0.4

    fun newEgg(now: Long, random: Random = Random.Default): PetState =
        PetState(bornAt = now, lastUpdate = now, lastSeenAt = now, hue = random.nextInt(-35, 36).toFloat())

    // ------------------------------------------------------------------ time

    fun stage(s: PetState, now: Long): Stage {
        if (s.isEgg) return Stage.EGG
        val h = ageHours(s, now)
        return Stage.entries.last { it != Stage.EGG && h >= it.minAgeHours }
    }

    /** Age since hatching in *pet* hours (the Zeitraffer multiplies). */
    fun ageHours(s: PetState, now: Long): Double =
        if (s.isEgg) 0.0 else max(0L, now - s.hatchedAt) / HOUR * s.growth

    fun ageLabel(s: PetState, now: Long): String {
        if (s.isEgg) return "noch im Ei"
        val h = ageHours(s, now)
        return when {
            h < 1.0 -> "${(h * 60).toInt().coerceAtLeast(1)} Min."
            h < 48.0 -> "${h.toInt()} Std."
            else -> "${(h / 24).toInt()} Tage"
        }
    }

    /**
     * Bring the needs up to [now]. Simulated in 5-minute steps so that falling asleep from
     * exhaustion and waking up rested happen at the right moment, even across a long absence.
     */
    fun tick(s: PetState, now: Long): Outcome {
        if (now <= s.lastUpdate) return Outcome(s)
        if (s.isEgg) {
            val st = s.copy(lastUpdate = now)
            return if (now - s.bornAt >= EGG_AUTO_HATCH_MS) hatch(st, now) else Outcome(st)
        }
        val stageBefore = stage(s, s.lastUpdate)
        var t = max(s.lastUpdate, now - MAX_CATCH_UP_MS)
        var cur = s
        var reaction = Reaction.NONE
        while (t < now) {
            val dt = min(STEP_MS, now - t)
            val h = dt / HOUR
            val hunger = hungerFactor(stage(cur, t))
            cur = if (cur.asleep) {
                cur.copy(
                    satiety = clamp(cur.satiety + SLEEP_SATIETY_PER_H * h * hunger),
                    energy = clamp(cur.energy + SLEEP_ENERGY_PER_H * h),
                    cheer = clamp(cur.cheer + SLEEP_FUN_PER_H * h),
                    bond = clamp(cur.bond + SLEEP_BOND_PER_H * h),
                )
            } else {
                // A hungry pet tires faster; a lonely one gets bored faster.
                val tire = if (cur.satiety < 20) 1.5 else 1.0
                val bore = if (cur.bond < 20) 1.3 else 1.0
                cur.copy(
                    satiety = clamp(cur.satiety + SATIETY_PER_H * h * hunger),
                    energy = clamp(cur.energy + ENERGY_PER_H * h * tire),
                    cheer = clamp(cur.cheer + FUN_PER_H * h * bore),
                    bond = clamp(cur.bond + BOND_PER_H * h),
                )
            }
            t += dt
            if (!cur.asleep && cur.energy < 8.0) {
                cur = cur.copy(asleep = true); reaction = Reaction.FELL_ASLEEP
            } else if (cur.asleep && cur.energy >= 99.5) {
                cur = cur.copy(asleep = false); reaction = Reaction.WOKE_UP
            }
        }
        cur = cur.copy(lastUpdate = now)
        if (stage(cur, now) != stageBefore) reaction = Reaction.EVOLVED
        return Outcome(cur, reaction)
    }

    private fun hungerFactor(stage: Stage) = when (stage) {
        Stage.BABY -> 1.3
        Stage.CHILD -> 1.1
        Stage.TEEN -> 1.0
        else -> 0.85
    }

    // ------------------------------------------------------------------ actions

    fun tapEgg(s: PetState, now: Long): Outcome {
        if (!s.isEgg) return Outcome(s)
        val warm = s.copy(eggWarmth = s.eggWarmth + 1.0)
        return if (warm.eggWarmth >= EGG_TAPS_TO_HATCH) hatch(warm, now) else Outcome(warm, Reaction.EGG_WOBBLE)
    }

    private fun hatch(s: PetState, now: Long) = Outcome(
        s.copy(hatchedAt = now, lastUpdate = now, satiety = 70.0, energy = 85.0, cheer = 75.0, bond = 35.0),
        Reaction.HATCHED,
    )

    fun feed(s: PetState, food: Food): Outcome = when {
        s.isEgg -> Outcome(s)
        s.asleep -> Outcome(s, Reaction.REFUSED_ASLEEP)
        s.satiety >= 95.0 -> Outcome(s.copy(cheer = clamp(s.cheer - 3)), Reaction.FULL)
        else -> Outcome(
            s.copy(
                satiety = clamp(s.satiety + food.satiety),
                cheer = clamp(s.cheer + food.cheer),
                bond = clamp(s.bond + 1.0),
                feeds = s.feeds + 1,
            ),
            Reaction.ATE,
        )
    }

    /** Can a round of „Glühwürmchen fangen“ start? */
    fun canPlay(s: PetState): Reaction = when {
        s.isEgg -> Reaction.NONE
        s.asleep -> Reaction.REFUSED_ASLEEP
        s.energy < 15.0 -> Reaction.TOO_TIRED
        else -> Reaction.PLAYED
    }

    /** End of the mini game: [caught] fireflies. */
    fun played(s: PetState, caught: Int): Outcome {
        if (s.isEgg || s.asleep) return Outcome(s)
        val n = caught.coerceIn(0, 12)
        return Outcome(
            s.copy(
                cheer = clamp(s.cheer + 8.0 + 4.0 * n),
                energy = clamp(s.energy - 3.0 - 1.2 * n),
                satiety = clamp(s.satiety - 0.8 * n),
                bond = clamp(s.bond + 3.0),
                plays = s.plays + 1,
            ),
            Reaction.PLAYED,
        )
    }

    /** One stroke over the body. The UI rate-limits this to one credit every couple of seconds. */
    fun pet(s: PetState): Outcome = when {
        s.isEgg -> tapEgg(s, s.lastUpdate)
        s.asleep -> Outcome(s.copy(bond = clamp(s.bond + 0.5)), Reaction.NONE)
        else -> Outcome(s.copy(bond = clamp(s.bond + 3.0), cheer = clamp(s.cheer + 1.5), pets = s.pets + 1), Reaction.PURR)
    }

    fun poke(s: PetState): Outcome = when {
        s.isEgg -> Outcome(s)
        s.asleep -> wake(s)
        else -> Outcome(s.copy(cheer = clamp(s.cheer + 0.8), bond = clamp(s.bond + 0.3)), Reaction.GIGGLE)
    }

    /** One spoken (or typed) exchange: being talked to is what bonds a Lumi most. */
    fun talked(s: PetState): PetState =
        if (s.isEgg) s else s.copy(
            bond = clamp(s.bond + 2.5),
            cheer = clamp(s.cheer + 1.5),
            energy = clamp(s.energy - 0.4),
            talks = s.talks + 1,
        )

    fun sleep(s: PetState): Outcome =
        if (s.isEgg || s.asleep) Outcome(s) else Outcome(s.copy(asleep = true), Reaction.FELL_ASLEEP)

    fun wake(s: PetState): Outcome = when {
        !s.asleep -> Outcome(s)
        s.energy < 35.0 -> Outcome(s.copy(asleep = false, cheer = clamp(s.cheer - 4.0)), Reaction.GRUMPY_WAKE)
        else -> Outcome(s.copy(asleep = false), Reaction.WOKE_UP)
    }

    // ------------------------------------------------------------------ derived

    fun mood(s: PetState): Mood = when {
        s.asleep -> Mood.ASLEEP
        s.isEgg -> Mood.CONTENT
        s.satiety < 25.0 -> Mood.HUNGRY
        s.energy < 22.0 -> Mood.SLEEPY
        s.bond < 18.0 -> Mood.LONELY
        s.cheer < 25.0 -> Mood.BORED
        s.wellbeing >= 0.72 -> Mood.HAPPY
        else -> Mood.CONTENT
    }

    /** The face Lumi makes when nothing special is going on. */
    fun restingEmotion(mood: Mood): Emotion = when (mood) {
        Mood.HAPPY -> Emotion.JOY
        Mood.CONTENT -> Emotion.NEUTRAL
        Mood.HUNGRY -> Emotion.HUNGRY
        Mood.SLEEPY, Mood.ASLEEP -> Emotion.TIRED
        Mood.BORED -> Emotion.GRUMPY
        Mood.LONELY -> Emotion.SAD
    }

    /** Dominant care habit, shown in the info sheet and fed into the persona. */
    fun traits(s: PetState): List<String> {
        val total = s.feeds + s.plays + s.pets + s.talks
        if (total < 6) return listOf("neugierig")
        val ranked = listOf(
            "verspielt" to s.plays * 1.6,
            "verschmust" to s.pets * 1.0,
            "gesprächig" to s.talks * 1.1,
            "verfressen" to s.feeds * 0.8,
        ).sortedByDescending { it.second }
        val top = ranked.filter { it.second >= ranked.first().second * 0.6 }.take(2).map { it.first }
        return top + if (s.bond > 70) listOf("anhänglich") else emptyList()
    }

    /** When will something need attention (for the reminder notification)? null = all fine for > 12 h. */
    fun nextNeedAt(s: PetState, now: Long, threshold: Double = 22.0): Pair<String, Long>? {
        if (s.isEgg) return null
        var cur = s.copy(lastUpdate = now)
        var t = now
        val end = now + 12 * 3_600_000L
        while (t < end) {
            t += 15 * 60_000L
            cur = tick(cur, t).state
            when {
                cur.satiety < threshold -> return "hat Hunger" to t
                !cur.asleep && cur.cheer < threshold -> return "langweilt sich" to t
                cur.bond < threshold -> return "vermisst dich" to t
            }
        }
        return null
    }

    fun rememberAll(s: PetState, facts: List<String>, max: Int = 16): PetState {
        if (facts.isEmpty()) return s
        val merged = (s.memories.filter { old -> facts.none { sameTopic(it, old) } } + facts).takeLast(max)
        return s.copy(memories = merged)
    }

    /** „Mag: Pizza“ replaces nothing, but „Name: Lou“ replaces an older „Name: …“. */
    private fun sameTopic(a: String, b: String): Boolean {
        val ka = a.substringBefore(':', "").trim()
        val kb = b.substringBefore(':', "").trim()
        if (a.equals(b, true)) return true
        return ka.isNotEmpty() && ka == kb && ka in SINGLE_VALUED
    }

    private val SINGLE_VALUED = setOf("Name", "Wohnort", "Beruf", "Alter", "Geburtstag")

    private fun clamp(v: Double) = v.coerceIn(0.0, 100.0)
}
