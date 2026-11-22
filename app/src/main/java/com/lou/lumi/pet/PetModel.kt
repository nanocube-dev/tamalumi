package com.lou.lumi.pet

/**
 * Everything Lumi *is*, as plain data (no Android types, unit-tested on the JVM).
 * Needs run from 0 (empty) to 100 (perfect).
 */
data class PetState(
    val name: String = "Lumi",
    /** What the pet calls its human (learned from „Ich heiße …“). */
    val userName: String = "",
    /** When the egg appeared (ms). */
    val bornAt: Long,
    /** 0 while it is still an egg. */
    val hatchedAt: Long = 0L,
    /** Last time the needs were brought up to date (ms). */
    val lastUpdate: Long = bornAt,
    /** Last time the app was in the foreground, for „Du warst lange weg“. */
    val lastSeenAt: Long = bornAt,
    val satiety: Double = 75.0,
    val energy: Double = 90.0,
    val cheer: Double = 70.0,
    val bond: Double = 25.0,
    val asleep: Boolean = false,
    /** Taps that warm the egg; it also hatches on its own after [PetEngine.EGG_AUTO_HATCH_MS]. */
    val eggWarmth: Double = 0.0,
    /** Personal colour shift in degrees, rolled when the egg is laid – no two Lumis look alike. */
    val hue: Float = 0f,
    val feeds: Int = 0,
    val plays: Int = 0,
    val pets: Int = 0,
    val talks: Int = 0,
    /** 1 = real time; the „Zeitraffer“ setting makes a day pass in an hour. */
    val growth: Double = 1.0,
    /** Short facts about the human, newest last (see brain/MemoryExtractor). */
    val memories: List<String> = emptyList(),
) {
    val isEgg: Boolean get() = hatchedAt == 0L
    val wellbeing: Double get() = (satiety + energy + cheer + bond) / 400.0

    fun toMap(): Map<String, String> = mapOf(
        "name" to name, "userName" to userName,
        "bornAt" to bornAt.toString(), "hatchedAt" to hatchedAt.toString(),
        "lastUpdate" to lastUpdate.toString(), "lastSeenAt" to lastSeenAt.toString(),
        "satiety" to satiety.toString(), "energy" to energy.toString(),
        "fun" to cheer.toString(), "bond" to bond.toString(),
        "asleep" to asleep.toString(), "eggWarmth" to eggWarmth.toString(),
        "hue" to hue.toString(),
        "feeds" to feeds.toString(), "plays" to plays.toString(),
        "pets" to pets.toString(), "talks" to talks.toString(),
        "growth" to growth.toString(),
        "memories" to memories.joinToString(SEP),
    )

    companion object {
        private const val SEP = "\u001F"

        fun fromMap(m: Map<String, String>, now: Long): PetState? {
            val born = m["bornAt"]?.toLongOrNull() ?: return null
            fun d(k: String, def: Double) = m[k]?.toDoubleOrNull() ?: def
            fun i(k: String) = m[k]?.toIntOrNull() ?: 0
            fun l(k: String, def: Long) = m[k]?.toLongOrNull() ?: def
            return PetState(
                name = m["name"].orEmpty().ifBlank { "Lumi" },
                userName = m["userName"].orEmpty(),
                bornAt = born,
                hatchedAt = l("hatchedAt", 0L),
                lastUpdate = l("lastUpdate", now),
                lastSeenAt = l("lastSeenAt", now),
                satiety = d("satiety", 75.0), energy = d("energy", 90.0),
                cheer = d("fun", 70.0), bond = d("bond", 25.0),
                asleep = m["asleep"] == "true",
                eggWarmth = d("eggWarmth", 0.0),
                hue = m["hue"]?.toFloatOrNull() ?: 0f,
                feeds = i("feeds"), plays = i("plays"), pets = i("pets"), talks = i("talks"),
                growth = d("growth", 1.0).coerceIn(1.0, 48.0),
                memories = m["memories"].orEmpty().split(SEP).filter { it.isNotBlank() },
            )
        }
    }
}

enum class Stage(val label: String, val minAgeHours: Double) {
    EGG("Ei", 0.0),
    BABY("Baby", 0.0),
    CHILD("Kind", 24.0),
    TEEN("Teenie", 72.0),
    ADULT("Erwachsen", 168.0),
}

/** Long-lived mood, derived from the needs. Drives colour and the resting face. */
enum class Mood(val label: String) {
    HAPPY("glücklich"),
    CONTENT("zufrieden"),
    HUNGRY("hungrig"),
    SLEEPY("müde"),
    BORED("gelangweilt"),
    LONELY("einsam"),
    ASLEEP("schläft"),
}

/**
 * Short-lived feeling (a few seconds): from a reaction or from the tag the LLM puts in front of an
 * answer, e.g. „[froh] Juhu, Pizza!“. The tag words are what the model is asked to use.
 */
enum class Emotion(val tag: String) {
    NEUTRAL("ruhig"),
    JOY("froh"),
    EXCITED("aufgeregt"),
    LOVE("verliebt"),
    CURIOUS("neugierig"),
    TIRED("müde"),
    HUNGRY("hungrig"),
    SAD("traurig"),
    SURPRISED("überrascht"),
    SILLY("albern"),
    THOUGHTFUL("nachdenklich"),
    GRUMPY("grummelig"),
}

enum class Food(val label: String, val satiety: Double, val cheer: Double) {
    BERRY("Leuchtbeere", 24.0, 2.0),
    COOKIE("Sternkeks", 14.0, 9.0),
}

/** What just happened – the UI turns this into animation + sound, the brain into context. */
enum class Reaction {
    NONE, ATE, FULL, REFUSED_ASLEEP, PLAYED, TOO_TIRED, PURR, GIGGLE, EGG_WOBBLE, HATCHED,
    EVOLVED, FELL_ASLEEP, WOKE_UP, GRUMPY_WAKE,
}

data class Outcome(val state: PetState, val reaction: Reaction = Reaction.NONE)
