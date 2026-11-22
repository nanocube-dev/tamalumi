package com.lou.lumi.pet

import android.content.Context
import android.util.Log
import com.lou.lumi.brain.Understanding
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The one place that owns the pet: current state as a flow (UI and brain both read it), every
 * change goes through [apply] (thread-safe – the voice threads feed and rename too), reactions go
 * out as events for the animation, and everything is persisted to SharedPreferences.
 */
class PetKeeper(context: Context) {

    private val prefs = context.getSharedPreferences("pet", Context.MODE_PRIVATE)
    private val lock = Any()

    val state: MutableStateFlow<PetState>
    /** One-shot reactions for the face (ATE, PURR, HATCHED …). */
    val reactions = MutableSharedFlow<Reaction>(extraBufferCapacity = 32)
    /** Care actions that were *said* („Hier, iss was“) – the UI plays them like a button press. */
    val spokenActions = MutableSharedFlow<Understanding.Action>(extraBufferCapacity = 8)

    private var lastSave = 0L

    init {
        val now = System.currentTimeMillis()
        val stored = PetState.fromMap(prefs.all.mapValues { it.value.toString() }, now)
        state = MutableStateFlow(stored ?: PetEngine.newEgg(now))
        if (stored == null) save(force = true)
        Log.i(TAG, "pet loaded: ${state.value.name}, egg=${state.value.isEgg}, memories=${state.value.memories.size}")
    }

    /** Run one rule from [PetEngine] on the up-to-date state. */
    fun apply(rule: (PetState) -> Outcome): Outcome = synchronized(lock) {
        val now = System.currentTimeMillis()
        val ticked = PetEngine.tick(state.value, now)
        val out = rule(ticked.state)
        val before = state.value
        state.value = out.state
        if (ticked.reaction != Reaction.NONE) reactions.tryEmit(ticked.reaction)
        if (out.reaction != Reaction.NONE) reactions.tryEmit(out.reaction)
        // what must never get lost (a name, a memory, sleeping, hatching) is written right away
        val important = out.reaction != Reaction.NONE || ticked.reaction != Reaction.NONE ||
            before.memories != out.state.memories || before.name != out.state.name ||
            before.userName != out.state.userName || before.asleep != out.state.asleep ||
            before.hatchedAt != out.state.hatchedAt || before.growth != out.state.growth
        save(force = important)
        out
    }

    fun change(f: (PetState) -> PetState) = apply { Outcome(f(it)) }

    /** Called about once a second from the UI. */
    fun tick() = apply { Outcome(it) }

    fun markSeen() = change { it.copy(lastSeenAt = System.currentTimeMillis()) }

    fun newEgg() = synchronized(lock) {
        val keepGrowth = state.value.growth
        state.value = PetEngine.newEgg(System.currentTimeMillis()).copy(growth = keepGrowth)
        save(force = true)
    }

    fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 15_000L) return
        lastSave = now
        val e = prefs.edit().clear()
        state.value.toMap().forEach { (k, v) -> e.putString(k, v) }
        e.apply()
    }

    private companion object { const val TAG = "Lumi.PET" }
}
