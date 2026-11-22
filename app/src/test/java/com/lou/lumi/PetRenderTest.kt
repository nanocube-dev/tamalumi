package com.lou.lumi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.TextView
import com.lou.lumi.brain.BrainState
import com.lou.lumi.pet.Emotion
import com.lou.lumi.pet.Food
import com.lou.lumi.pet.Mood
import com.lou.lumi.pet.PetEngine
import com.lou.lumi.pet.Reaction
import com.lou.lumi.pet.Stage
import com.lou.lumi.ui.MainActivity
import com.lou.lumi.ui.PetView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Draws Lumi in its main looks to PNGs (build/renders) with Robolectric's native graphics – the
 * same Skia code the phone uses for software rendering. Not a pixel-diff test: it proves that
 * every state renders without crashing and gives a contact sheet to look at.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class PetRenderTest {

    private val outDir = File(System.getProperty("lumi.renderDir") ?: "build/renders").apply { mkdirs() }
    private val w = 1080
    private val h = 2340

    private fun view(setup: PetView.() -> Unit): PetView {
        val v = PetView(RuntimeEnvironment.getApplication())
        v.testMode = true
        v.hourOverride = 20
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        v.setup()
        return v
    }

    private fun shot(v: PetView): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    private fun save(b: Bitmap, name: String) {
        FileOutputStream(File(outDir, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun contactSheet() {
        val looks = linkedMapOf<String, PetView.() -> Unit>(
            "ei" to { stage = Stage.EGG; eggProgress = 0.6f; simulate(1.2f) },
            "baby" to { stage = Stage.BABY; mood = Mood.CONTENT; simulate(1.5f) },
            "froh" to { stage = Stage.CHILD; mood = Mood.HAPPY; simulate(0.3f); flash(Emotion.JOY, 10f); simulate(1.4f) },
            "hoert_zu" to { stage = Stage.CHILD; brain = BrainState.LISTENING; micLevel = 0.12f; simulate(1.5f) },
            "denkt" to { stage = Stage.TEEN; brain = BrainState.THINKING; simulate(1.5f) },
            "spricht" to { stage = Stage.TEEN; brain = BrainState.SPEAKING; speakLevel = 0.5f; simulate(1.4f) },
            "verliebt" to { stage = Stage.ADULT; simulate(0.2f); react(Reaction.PURR); simulate(0.9f) },
            "traurig" to { stage = Stage.CHILD; mood = Mood.LONELY; simulate(1.5f) },
            "hungrig" to { stage = Stage.BABY; mood = Mood.HUNGRY; simulate(0.6f); feed(Food.BERRY); simulate(0.55f) },
            "grummelig" to { stage = Stage.TEEN; mood = Mood.BORED; simulate(1.5f) },
            "schlaeft" to { stage = Stage.CHILD; asleep = true; mood = Mood.ASLEEP; simulate(3.5f) },
            "spiel" to { stage = Stage.CHILD; startGame(15f); simulate(2.6f) },
        )
        val shots = looks.map { (name, setup) -> name to shot(view(setup)).also { save(it, it.let { _ -> "pet_$name" }) } }

        // contact sheet: 4 × 3, labelled
        val cols = 4
        val scale = 0.25f
        val cw = (w * scale).toInt(); val ch = (h * scale).toInt()
        val sheet = Bitmap.createBitmap(cw * cols, ch * ((shots.size + cols - 1) / cols), Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.BLACK)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f }
        shots.forEachIndexed { i, (name, b) ->
            val x = (i % cols) * cw.toFloat(); val y = (i / cols) * ch.toFloat()
            c.save(); c.translate(x, y); c.scale(scale, scale); c.drawBitmap(b, 0f, 0f, null); c.restore()
            c.drawText(name, x + 14f, y + 34f, label)
        }
        save(sheet, "contact_sheet")

        // sanity: the pet must actually be drawn (bright pixels around the middle)
        val baby = shots.first { it.first == "baby" }.second
        val center = baby.getPixel(w / 2, (h * 0.40f).toInt())
        assertTrue("pet body is visible", Color.red(center) + Color.green(center) + Color.blue(center) > 200)
    }

    @Test fun wholeScreen() {
        Lumi.init(RuntimeEnvironment.getApplication())
        val now = System.currentTimeMillis()
        Lumi.keeper.change {
            PetEngine.tapEgg(it.copy(eggWarmth = 99.0), now - 30 * 3_600_000L).state
                .copy(satiety = 62.0, energy = 81.0, cheer = 88.0, bond = 47.0, lastUpdate = now, lastSeenAt = now)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = controller.get()
        val root = act.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val pet = act.findViewById<PetView>(R.id.pet)
        pet.testMode = true
        pet.hourOverride = 20
        pet.stage = Stage.CHILD
        pet.mood = Mood.HAPPY
        pet.simulate(1.5f)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b))
        save(b, "screen")

        // an answer from Lumi with the report button under it (Play policy on AI content)
        act.findViewById<TextView>(R.id.caption).apply { text = "Juhu, du bist wieder da! Spielen wir was?"; alpha = 1f }
        act.findViewById<TextView>(R.id.reportChip).apply { visibility = View.VISIBLE; alpha = 1f }
        val b2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(b2))
        save(b2, "screen_answer")
        controller.pause().stop()
    }
}
