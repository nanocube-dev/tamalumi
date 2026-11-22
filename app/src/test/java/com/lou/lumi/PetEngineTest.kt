package com.lou.lumi

import com.lou.lumi.pet.Food
import com.lou.lumi.pet.Mood
import com.lou.lumi.pet.PetEngine
import com.lou.lumi.pet.PetState
import com.lou.lumi.pet.Reaction
import com.lou.lumi.pet.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PetEngineTest {

    private val t0 = 1_700_000_000_000L
    private val h = 3_600_000L

    private fun hatched(at: Long = t0) = PetEngine.tapEgg(PetEngine.newEgg(at, Random(1)).copy(eggWarmth = 99.0), at).state

    @Test fun eggHatchesAfterTapsOrByItself() {
        var s = PetEngine.newEgg(t0, Random(1))
        assertEquals(Stage.EGG, PetEngine.stage(s, t0))
        repeat(PetEngine.EGG_TAPS_TO_HATCH - 1) {
            val o = PetEngine.tapEgg(s, t0 + it)
            assertEquals(Reaction.EGG_WOBBLE, o.reaction)
            s = o.state
        }
        val last = PetEngine.tapEgg(s, t0 + 100)
        assertEquals(Reaction.HATCHED, last.reaction)
        assertEquals(Stage.BABY, PetEngine.stage(last.state, t0 + 100))

        val untouched = PetEngine.tick(PetEngine.newEgg(t0), t0 + PetEngine.EGG_AUTO_HATCH_MS + 1)
        assertEquals(Reaction.HATCHED, untouched.reaction)
        assertFalse(untouched.state.isEgg)
    }

    @Test fun needsDecayOverTimeAndFoodHelps() {
        val s = hatched()
        val later = PetEngine.tick(s, t0 + 3 * h).state
        assertTrue("satiety drops", later.satiety < s.satiety - 20)
        assertTrue("fun drops", later.cheer < s.cheer - 20)
        val fed = PetEngine.feed(later, Food.BERRY)
        assertEquals(Reaction.ATE, fed.reaction)
        assertEquals(later.satiety + Food.BERRY.satiety, fed.state.satiety, 0.001)
        assertEquals(1, fed.state.feeds)
    }

    @Test fun aFullPetRefusesAndASleepingOneToo() {
        val full = hatched().copy(satiety = 97.0)
        assertEquals(Reaction.FULL, PetEngine.feed(full, Food.COOKIE).reaction)
        val asleep = hatched().copy(asleep = true)
        assertEquals(Reaction.REFUSED_ASLEEP, PetEngine.feed(asleep, Food.BERRY).reaction)
        assertEquals(Reaction.REFUSED_ASLEEP, PetEngine.canPlay(asleep))
        assertEquals(Reaction.TOO_TIRED, PetEngine.canPlay(hatched().copy(energy = 10.0)))
    }

    @Test fun exhaustedPetFallsAsleepAndWakesRested() {
        val tired = hatched().copy(energy = 9.0)
        val o = PetEngine.tick(tired, t0 + h)
        assertTrue(o.state.asleep)
        // sleeping refills energy; after enough hours it gets up by itself
        val morning = PetEngine.tick(o.state, t0 + 5 * h)
        assertFalse(morning.state.asleep)
        assertTrue(morning.state.energy > 95)
    }

    @Test fun longAbsenceIsCappedAndNothingGoesBelowZero() {
        val s = hatched()
        val o = PetEngine.tick(s, t0 + 60L * 24 * h)
        listOf(o.state.satiety, o.state.energy, o.state.cheer, o.state.bond).forEach { assertTrue(it in 0.0..100.0) }
        assertEquals(t0 + 60L * 24 * h, o.state.lastUpdate)
    }

    @Test fun stagesFollowAgeAndTimeLapse() {
        val s = hatched()
        assertEquals(Stage.BABY, PetEngine.stage(s, t0 + 23 * h))
        assertEquals(Stage.CHILD, PetEngine.stage(s, t0 + 25 * h))
        assertEquals(Stage.TEEN, PetEngine.stage(s, t0 + 73 * h))
        assertEquals(Stage.ADULT, PetEngine.stage(s, t0 + 170 * h))
        val fast = s.copy(growth = 24.0)
        assertEquals(Stage.CHILD, PetEngine.stage(fast, t0 + h + 60_000))
        val o = PetEngine.tick(s.copy(lastUpdate = t0 + 23 * h), t0 + 25 * h)
        assertEquals(Reaction.EVOLVED, o.reaction)
    }

    @Test fun moodFollowsTheWeakestNeed() {
        val s = hatched()
        assertEquals(Mood.HUNGRY, PetEngine.mood(s.copy(satiety = 10.0)))
        assertEquals(Mood.SLEEPY, PetEngine.mood(s.copy(energy = 15.0)))
        assertEquals(Mood.LONELY, PetEngine.mood(s.copy(bond = 10.0)))
        assertEquals(Mood.BORED, PetEngine.mood(s.copy(cheer = 10.0, bond = 50.0)))
        assertEquals(Mood.HAPPY, PetEngine.mood(s.copy(satiety = 90.0, energy = 90.0, cheer = 90.0, bond = 80.0)))
        assertEquals(Mood.ASLEEP, PetEngine.mood(s.copy(asleep = true)))
    }

    @Test fun wakingATiredPetMakesItGrumpy() {
        val s = hatched().copy(asleep = true, energy = 20.0)
        assertEquals(Reaction.GRUMPY_WAKE, PetEngine.wake(s).reaction)
        assertEquals(Reaction.WOKE_UP, PetEngine.wake(s.copy(energy = 80.0)).reaction)
    }

    @Test fun reminderPredictsHunger() {
        val s = hatched().copy(satiety = 40.0, cheer = 90.0, bond = 90.0, energy = 90.0)
        val next = PetEngine.nextNeedAt(s, t0)
        assertNotNull(next)
        assertEquals("hat Hunger", next!!.first)
        assertTrue(next.second in (t0 + h)..(t0 + 3 * h))
        assertNull(PetEngine.nextNeedAt(PetEngine.newEgg(t0), t0))
    }

    @Test fun memoriesReplaceSingleValuedTopics() {
        var s = hatched()
        s = PetEngine.rememberAll(s, listOf("Name: Lou", "Mag: Pizza"))
        s = PetEngine.rememberAll(s, listOf("Name: Louis", "Mag: Katzen"))
        assertEquals(listOf("Mag: Pizza", "Name: Louis", "Mag: Katzen"), s.memories)
    }

    @Test fun stateSurvivesSerialisation() {
        val s = hatched().copy(name = "Funkel", userName = "Lou", memories = listOf("Mag: Pizza", "Hat: Hund namens Bello"), talks = 5, hue = 12f)
        val back = PetState.fromMap(s.toMap(), t0)
        assertEquals(s, back)
    }

    @Test fun traitsReflectCare() {
        val s = hatched().copy(plays = 20, pets = 2, talks = 3, feeds = 4)
        assertEquals("verspielt", PetEngine.traits(s).first())
    }
}
