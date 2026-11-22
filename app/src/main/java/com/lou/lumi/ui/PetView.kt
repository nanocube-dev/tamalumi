package com.lou.lumi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.lou.lumi.brain.BrainState
import com.lou.lumi.pet.Emotion
import com.lou.lumi.pet.Food
import com.lou.lumi.pet.Mood
import com.lou.lumi.pet.PetEngine
import com.lou.lumi.pet.Reaction
import com.lou.lumi.pet.Stage
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Lumi itself: a soft, glowing, gently deforming body with a living gradient inside (the look of
 * a modern voice-assistant orb), two expressive capsule eyes, a small mouth that follows the
 * voice, and everything around it – night sky, glow, particles, food, the firefly game.
 *
 * Pure Canvas (no images, no shaders that could fail to compile): runs on every device, costs a
 * couple of gradients and two layers per frame. All motion is springs and exponential smoothing,
 * so any state change blends instead of jumping.
 */
class PetView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onPoke() {}
        fun onStroke() {}
        fun onEggTap() {}
        fun onFoodEaten(food: Food) {}
        fun onFireflyCaught(total: Int) {}
        fun onGameEnd(caught: Int) {}
    }

    var listener: Listener? = null

    // ------------------------------------------------------------------ inputs (main thread)

    var stage: Stage = Stage.EGG
    var mood: Mood = Mood.CONTENT
    var asleep = false
    /** Personal colour shift of this pet, degrees. */
    var hue = 0f
    var brain: BrainState = BrainState.OFF
    /** 0..1 microphone RMS (only shown while listening). */
    var micLevel = 0f
    /** 0..1 peak of Lumi's own voice at the speaker – drives mouth and body pulse. */
    var speakLevel = 0f
    /** 0..1 how far the egg has been warmed. */
    var eggProgress = 0f

    /** Render tests drive time themselves ([simulate]); the view then never schedules frames. */
    var testMode = false
    /** Hour of day for the sky; null = the real clock. */
    var hourOverride: Int? = null

    private val density = resources.displayMetrics.density
    private val rnd = Random(7)

    // ------------------------------------------------------------------ animation state

    private var time = 0f                         // seconds since start
    private var lastFrame = 0L

    private class Expr {
        var open = 1f; var smile = 0f; var tilt = 0f; var lidTop = 0f; var scale = 1f
        var curve = 0.25f; var mouth = 0f; var blush = 0f; var wink = 0f
        fun set(open: Float, smile: Float, tilt: Float, lidTop: Float, scale: Float, curve: Float, mouth: Float, blush: Float, wink: Float = 0f) {
            this.open = open; this.smile = smile; this.tilt = tilt; this.lidTop = lidTop; this.scale = scale
            this.curve = curve; this.mouth = mouth; this.blush = blush; this.wink = wink
        }
    }

    private val cur = Expr()
    private val tgt = Expr()

    private var flashEmotion: Emotion? = null
    private var flashUntil = 0f

    private val pal = Palette.CONTENT.toArray()
    private val palTarget = IntArray(6)
    private val palTmp = IntArray(6)

    private var gx = 0f; private var gy = 0f                 // gaze, -1..1
    private var gazeTx = 0f; private var gazeTy = 0f
    private var nextSaccade = 0f
    private var blinkAt = 2f
    private var blink = 1f                                   // 1 = open
    private var sq = 1f; private var sqV = 0f                // squash spring
    private var hop = 0f; private var hopV = 0f              // in units of R, negative = up
    private var tiltDeg = 0f; private var tiltTarget = 0f
    private var shakeUntil = 0f
    private var yawnUntil = 0f
    private var wob = 0.012f
    private var mic = 0f; private var spk = 0f               // smoothed levels
    private var listenMix = 0f; private var thinkMix = 0f
    private var dotsAlpha = 0f; private var thinkAlpha = 0f; private var wakeAlpha = 0f
    private var eggRot = 0f; private var eggRotV = 0f
    private var flash = 0f                                   // white burst (hatch / evolve)
    private var strokeMix = 0f; private var lastStrokeAt = -10f
    private var stageScale = scaleFor(Stage.EGG)
    private var zTimer = 0f

    // touch
    private var touching = false
    private var touchX = 0f; private var touchY = 0f
    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    private var moved = 0f; private var strokeDist = 0f
    private var touchInBody = false
    private var lastStrokeCredit = 0L

    // geometry of the current frame
    private var cx = 0f; private var cy = 0f; private var r = 0f; private var ground = 0f; private var r0 = 0f
    private var sx = 1f; private var sy = 1f

    // ------------------------------------------------------------------ effects

    private class Particle(
        val type: Int, var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val max: Float, val size: Float, val color: Int, var rot: Float = 0f, val spin: Float = 0f,
    )

    private val particles = ArrayList<Particle>()

    private class FoodFlight(val food: Food, val sx: Float, val sy: Float) {
        var t = 0f
        var chewing = false
        var chew = 0f
    }

    private var food: FoodFlight? = null

    private class Firefly(var x: Float, var y: Float, var vx: Float, var vy: Float, val life: Float, val phase: Float) {
        var age = 0f
        var caught = false
    }

    private val flies = ArrayList<Firefly>()
    private var gameLeft = 0f
    private var gameTotal = 0f
    private var gameCaught = 0
    private var nextFly = 0f
    val gameRunning: Boolean get() = gameLeft > 0f

    // ------------------------------------------------------------------ paints

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val atop = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); color = Color.BLACK }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val bodyPath = Path()
    private val tmpPath = Path()
    private val rect = RectF()

    private val stars = List(46) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.62f, 0.4f + rnd.nextFloat() * 1.4f, rnd.nextFloat() * 6.28f, 0.6f + rnd.nextFloat() * 1.6f) }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Lumi"
    }

    // ------------------------------------------------------------------ public API

    /** Show a feeling for a while (an LLM tag, a reaction). */
    fun flash(e: Emotion, seconds: Float = 4f) {
        flashEmotion = e
        flashUntil = time + seconds
        when (e) {
            Emotion.SURPRISED -> { sqV += 2.5f }
            Emotion.EXCITED -> jump(0.9f)
            Emotion.LOVE -> repeat(3) { heart(0.6f) }
            else -> {}
        }
    }

    fun react(r: Reaction) {
        when (r) {
            Reaction.GIGGLE -> { flash(if (rnd.nextBoolean()) Emotion.JOY else Emotion.SILLY, 1.8f); squash(2.6f); jump(0.45f); sparkle(4) }
            Reaction.PURR -> { flash(Emotion.LOVE, 3f); heart(0.8f) }
            Reaction.FULL -> { flash(Emotion.GRUMPY, 1.8f); shakeUntil = time + 0.7f }
            Reaction.REFUSED_ASLEEP -> { squash(1.2f); zParticle(); zParticle() }
            Reaction.TOO_TIRED -> { flash(Emotion.TIRED, 3f); yawnUntil = time + 1.6f }
            Reaction.PLAYED -> { flash(Emotion.EXCITED, 3f); sparkle(10) }
            Reaction.EGG_WOBBLE -> { eggRotV += if (rnd.nextBoolean()) 140f else -140f; sparkle(3) }
            Reaction.HATCHED -> { flash = 1f; ring(); sparkle(26); flash(Emotion.SURPRISED, 1.4f); stageScale = scaleFor(Stage.BABY) * 0.6f }
            Reaction.EVOLVED -> { flash = 0.85f; ring(); sparkle(18); flash(Emotion.EXCITED, 3f) }
            Reaction.FELL_ASLEEP -> { zParticle() }
            Reaction.WOKE_UP -> { flash(Emotion.SURPRISED, 1.2f); squash(-2f); yawnUntil = time + 1.2f }
            Reaction.GRUMPY_WAKE -> { flash(Emotion.GRUMPY, 3f); yawnUntil = time + 1.4f }
            Reaction.ATE, Reaction.NONE -> {}
        }
        if (r != Reaction.NONE) haptic()
    }

    /** A snack flies in from the bottom right and gets eaten; [Listener.onFoodEaten] at the gulp. */
    fun feed(f: Food) {
        food = FoodFlight(f, width * 0.80f, height * 0.88f)
    }

    /** Start „Glühwürmchen fangen“. */
    fun startGame(seconds: Float = 15f) {
        flies.clear()
        gameCaught = 0
        gameLeft = seconds
        gameTotal = seconds
        nextFly = 0.2f
        flash(Emotion.EXCITED, 1.5f)
    }

    fun stopGame() {
        if (gameLeft > 0f) { gameLeft = 0.0001f }
    }

    /** Render tests: advance the simulation by [seconds] in 60 Hz steps. */
    fun simulate(seconds: Float) {
        var left = seconds
        while (left > 0f) {
            val dt = min(1f / 60f, left)
            step(dt)
            left -= dt
        }
    }

    // ------------------------------------------------------------------ frame

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!testMode) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
            lastFrame = now
            step(dt)
        }
        render(canvas)
        if (!testMode && windowVisibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { lastFrame = 0L; invalidate() }
    }

    private fun step(dt: Float) {
        time += dt
        updateGeometry()
        val k = { tau: Float -> 1f - exp(-dt / tau) }

        // ---- levels
        mic += ((if (brain == BrainState.LISTENING || brain == BrainState.HEARING) micLevel else 0f) - mic) * k(0.08f)
        spk += (speakLevel - spk) * k(if (speakLevel > spk) 0.03f else 0.09f)
        listenMix += ((if (brain == BrainState.LISTENING || brain == BrainState.HEARING) 1f else 0f) - listenMix) * k(0.35f)
        thinkMix += ((if (brain == BrainState.THINKING) 1f else 0f) - thinkMix) * k(0.35f)
        dotsAlpha += ((if (brain == BrainState.LISTENING || brain == BrainState.HEARING || brain == BrainState.SPEAKING) 1f else 0f) - dotsAlpha) * k(0.2f)
        thinkAlpha += ((if (brain == BrainState.THINKING || brain == BrainState.HEARING) 1f else 0f) - thinkAlpha) * k(0.2f)
        wakeAlpha += ((if (brain == BrainState.WAKING) 1f else 0f) - wakeAlpha) * k(0.3f)
        stageScale += (scaleFor(stage) - stageScale) * k(0.5f)

        // ---- feeling → face
        if (flashEmotion != null && time > flashUntil) flashEmotion = null
        val emotion = when {
            stage == Stage.EGG -> Emotion.NEUTRAL
            flashEmotion != null -> flashEmotion!!
            brain == BrainState.THINKING -> Emotion.THOUGHTFUL
            brain == BrainState.LISTENING || brain == BrainState.HEARING -> Emotion.CURIOUS
            else -> PetEngine.restingEmotion(mood)
        }
        targetsFor(emotion)
        if (asleep) tgt.set(0f, 0f, 0f, 0f, 0.98f, 0.1f, 0.05f + 0.04f * sin(time * 1.2f), 0.15f)
        if (time < yawnUntil) { tgt.mouth = 0.65f; tgt.curve = 0f; tgt.open = min(tgt.open, 0.3f) }
        strokeMix += ((if (touching && touchInBody && time - lastStrokeAt < 0.6f) 1f else 0f) - strokeMix) * k(0.15f)
        if (strokeMix > 0.05f && !asleep) {
            tgt.smile = max(tgt.smile, strokeMix * 0.95f); tgt.blush = max(tgt.blush, strokeMix * 0.9f); tgt.curve = max(tgt.curve, 0.8f * strokeMix)
        }
        val ff = food
        if (ff != null) {
            if (!ff.chewing) { tgt.mouth = max(tgt.mouth, (ff.t * 1.4f - 0.3f).coerceIn(0f, 0.85f)); tgt.curve = 0.2f; tgt.open = 1.05f }
            else { tgt.mouth = 0.08f + 0.32f * abs(sin(ff.chew * 9f)); tgt.smile = 0.7f; tgt.blush = 0.5f; tgt.curve = 0.6f }
        }
        smoothExpr(k(0.12f))

        // ---- colours
        val base = when {
            stage == Stage.EGG -> Palette.EGG
            asleep -> Palette.SLEEPY
            else -> Palette.forEmotion(emotion) ?: Palette.forMood(mood)
        }
        val raw = base.toArray()
        if (stage != Stage.EGG && !asleep) {
            val l = Palette.LISTEN.toArray(); val t = Palette.THINK.toArray()
            for (i in raw.indices) {
                raw[i] = Palette.mix(raw[i], l[i], listenMix * 0.45f)
                raw[i] = Palette.mix(raw[i], t[i], thinkMix * 0.45f)
            }
        }
        Palette.shifted(raw, if (stage == Stage.EGG) hue * 0.5f else hue, palTarget)
        val pk = k(0.5f)
        for (i in pal.indices) pal[i] = Palette.mix(pal[i], palTarget[i], pk).let { if (it == pal[i]) palTarget[i] else it }

        // ---- gaze
        val fly = flies.filter { !it.caught }.minByOrNull { hypot(it.x - cx, it.y - cy) }
        when {
            touching -> setGazeTo(touchX, touchY)
            ff != null && !ff.chewing -> setGazeTo(foodPos(ff).first, foodPos(ff).second)
            fly != null -> setGazeTo(fly.x, fly.y)
            brain == BrainState.THINKING -> { gazeTx = 0.55f; gazeTy = -0.75f }
            brain == BrainState.LISTENING || brain == BrainState.HEARING || brain == BrainState.SPEAKING -> { gazeTx = 0f; gazeTy = 0.15f }
            asleep -> { gazeTx = 0f; gazeTy = 0.2f }
            time > nextSaccade -> {
                nextSaccade = time + 1.2f + rnd.nextFloat() * 3.2f
                if (rnd.nextFloat() < 0.45f) { gazeTx = 0f; gazeTy = 0.1f }
                else { gazeTx = rnd.nextFloat() * 1.6f - 0.8f; gazeTy = rnd.nextFloat() * 1.0f - 0.55f }
            }
        }
        gx += (gazeTx - gx) * k(0.09f)
        gy += (gazeTy - gy) * k(0.09f)

        // ---- blink
        if (time >= blinkAt) {
            val p = (time - blinkAt) / 0.16f
            blink = if (p < 1f) 1f - sin(p * PI.toFloat()) else 1f
            if (p >= 1f) blinkAt = time + 2.2f + rnd.nextFloat() * 3.8f - if (rnd.nextFloat() < 0.18f) 2.0f else 0f
        }

        // ---- body physics
        val stiff = 170f; val damp = 11f
        sqV += (-(sq - 1f) * stiff - sqV * damp) * dt
        sq += sqV * dt
        sq = sq.coerceIn(0.7f, 1.3f)
        if (hop < 0f || hopV < 0f) {
            hopV += 26f * dt
            hop += hopV * dt
            if (hop >= 0f) { hop = 0f; if (hopV > 1.2f) squash(min(3.5f, hopV * 0.45f)); hopV = 0f }
        }
        tiltTarget = when {
            time < shakeUntil -> sin(time * 26f) * 9f
            emotion == Emotion.CURIOUS -> 6f
            emotion == Emotion.THOUGHTFUL -> -5f
            emotion == Emotion.SILLY -> 8f * sin(time * 3f)
            else -> 0f
        }
        tiltDeg += (tiltTarget - tiltDeg) * k(0.18f)
        val baseWob = when (emotion) {
            Emotion.EXCITED, Emotion.SILLY -> 0.03f
            Emotion.TIRED, Emotion.SAD -> 0.008f
            else -> 0.014f
        }
        wob += (baseWob + spk * 0.05f + mic * 0.12f - wob) * k(0.12f)
        // happy pets bounce now and then on their own
        if (!asleep && stage != Stage.EGG && (emotion == Emotion.EXCITED || (emotion == Emotion.JOY && mood == Mood.HAPPY)) &&
            hop == 0f && rnd.nextFloat() < dt * 0.25f) jump(0.5f)

        // egg wobble spring
        eggRotV += (-eggRot * 90f - eggRotV * 6f) * dt
        eggRot += eggRotV * dt
        if (stage == Stage.EGG && rnd.nextFloat() < dt * (0.08f + eggProgress * 0.5f)) eggRotV += (if (rnd.nextBoolean()) 1 else -1) * (40f + 60f * eggProgress)

        flash = max(0f, flash - dt * 1.3f)

        // ---- effects
        if (asleep) { zTimer -= dt; if (zTimer <= 0f) { zParticle(); zTimer = 1.7f } }
        stepParticles(dt)
        stepFood(dt)
        stepGame(dt)
    }

    private fun targetsFor(e: Emotion) {
        when (e) {
            Emotion.NEUTRAL -> tgt.set(1f, 0.08f, 0f, 0f, 1f, 0.3f, 0f, 0.12f)
            Emotion.JOY -> tgt.set(1f, 0.78f, 0f, 0f, 1f, 0.95f, 0.22f, 0.55f)
            Emotion.EXCITED -> tgt.set(1.12f, 0.25f, 0f, 0f, 1.12f, 1f, 0.42f, 0.5f)
            Emotion.LOVE -> tgt.set(1f, 0.88f, 0f, 0f, 1.04f, 0.85f, 0.12f, 0.95f)
            Emotion.CURIOUS -> tgt.set(1.08f, 0f, 0f, 0f, 1.06f, 0.15f, 0.12f, 0.15f)
            Emotion.TIRED -> tgt.set(0.42f, 0f, 0.1f, 0.35f, 0.95f, 0f, 0.04f, 0.05f)
            Emotion.HUNGRY -> tgt.set(0.95f, 0f, 0.4f, 0.08f, 1f, -0.15f, 0.16f, 0.05f)
            Emotion.SAD -> tgt.set(0.88f, 0f, 0.7f, 0.12f, 0.95f, -0.65f, 0f, 0f)
            Emotion.SURPRISED -> tgt.set(1.25f, 0f, 0f, 0f, 1.18f, 0f, 0.5f, 0.2f)
            Emotion.SILLY -> tgt.set(1f, 0.35f, 0f, 0f, 1.02f, 1f, 0.32f, 0.4f, wink = 1f)
            Emotion.THOUGHTFUL -> tgt.set(0.82f, 0.05f, -0.1f, 0.18f, 0.98f, 0.05f, 0f, 0.05f)
            Emotion.GRUMPY -> tgt.set(0.72f, 0f, -0.6f, 0.22f, 0.96f, -0.35f, 0f, 0f)
        }
    }

    private fun smoothExpr(a: Float) {
        cur.open += (tgt.open - cur.open) * a
        cur.smile += (tgt.smile - cur.smile) * a
        cur.tilt += (tgt.tilt - cur.tilt) * a
        cur.lidTop += (tgt.lidTop - cur.lidTop) * a
        cur.scale += (tgt.scale - cur.scale) * a
        cur.curve += (tgt.curve - cur.curve) * a
        cur.blush += (tgt.blush - cur.blush) * a
        cur.wink += (tgt.wink - cur.wink) * a
        // the mouth reacts faster – it has to keep up with speech
        val m = max(tgt.mouth, if (brain == BrainState.SPEAKING) (spk * 1.6f).coerceAtMost(0.75f) else 0f)
        cur.mouth += (m - cur.mouth) * min(1f, a * 2.6f)
    }

    private fun setGazeTo(x: Float, y: Float) {
        if (r <= 0f) return
        gazeTx = ((x - cx) / (r * 2.2f)).coerceIn(-1f, 1f)
        gazeTy = ((y - cy) / (r * 2.2f)).coerceIn(-1f, 1f)
    }

    private fun squash(impulse: Float) { sqV -= impulse }
    private fun jump(strength: Float) { if (hop == 0f && !asleep && stage != Stage.EGG) { hopV = -4.2f * strength; hop = -0.0001f; squash(1.4f) } }

    private fun scaleFor(s: Stage) = when (s) {
        Stage.EGG -> 0.92f
        Stage.BABY -> 0.74f
        Stage.CHILD -> 0.86f
        Stage.TEEN -> 0.95f
        Stage.ADULT -> 1.0f
    }

    // ------------------------------------------------------------------ render

    private fun render(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        updateGeometry()

        drawSky(c, w, h)
        drawShadow(c)
        drawGlow(c)

        c.save()
        val pivotY = ground
        if (stage == Stage.EGG) c.rotate(eggRot, cx, pivotY) else c.rotate(tiltDeg, cx, pivotY)
        if (stage == Stage.EGG) drawEgg(c) else { drawBody(c); drawFace(c) }
        c.restore()

        drawDots(c)
        drawFood(c)
        drawFlies(c)
        drawParticles(c)
        if (flash > 0f) {
            fill.color = Color.BLACK; fill.shader = RadialGradient(cx, cy, max(w, h) * (0.4f + (1f - flash) * 0.8f),
                intArrayOf(Palette.withAlpha(Color.WHITE, flash), Palette.withAlpha(Color.WHITE, flash * 0.5f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }
        drawGameHud(c, w)
    }

    /** Where the body is this frame – also needed by step() for particles, gaze and food. */
    private fun updateGeometry() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val unit = min(w, h * 0.6f)
        r0 = unit * 0.3f * stageScale
        val breathe = 1f + (if (asleep) 0.028f else 0.016f) * sin(time * (if (asleep) 1.1f else 1.75f))
        r = r0 * breathe * (0.94f + 0.06f * cur.scale)
        sy = sq
        sx = 1f + (1f - sq) * 0.85f
        cx = w / 2f
        ground = h * 0.43f + r0
        cy = ground - r * sy + hop * r0
    }

    private fun skyColors(): Pair<Int, Int> {
        val hour = hourOverride ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val (top, bottom) = when (hour) {
            in 6..9 -> "#1C2350" to "#07091A"
            in 10..16 -> "#111D42" to "#050815"
            in 17..21 -> "#25133F" to "#07050F"
            else -> "#0A0D26" to "#020309"
        }
        var t = Color.parseColor(top); var b = Color.parseColor(bottom)
        if (asleep) { t = Palette.mix(t, Color.BLACK, 0.45f); b = Palette.mix(b, Color.BLACK, 0.4f) }
        return t to b
    }

    private fun drawSky(c: Canvas, w: Float, h: Float) {
        val (top, bottom) = skyColors()
        fill.color = Color.BLACK; fill.shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        // ambient light of the pet's colour filling the room
        val glow = pal[5]
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx, cy, w * 1.05f,
            intArrayOf(Palette.withAlpha(glow, if (asleep) 0.14f else 0.24f), Palette.withAlpha(glow, 0.06f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        // stars, brighter at night and while asleep
        val hour = hourOverride ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = if (asleep || hour >= 21 || hour < 6) 1f else 0.35f
        for (s in stars) {
            val a = night * (0.25f + 0.55f * (0.5f + 0.5f * sin(time * s[4] + s[3])))
            fill.color = Palette.withAlpha(Color.WHITE, a * 0.8f)
            c.drawCircle(s[0] * w, s[1] * h, s[2] * density * 0.9f, fill)
        }
    }

    private fun drawShadow(c: Canvas) {
        val lift = (-hop).coerceIn(0f, 1f)
        val sw = r0 * 1.25f * (1f - lift * 0.35f) * sx
        val sh = r0 * 0.2f * (1f - lift * 0.3f)
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx, ground + r0 * 0.04f, sw,
            intArrayOf(Palette.withAlpha(Color.BLACK, 0.5f * (1f - lift * 0.5f)), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.save()
        c.scale(1f, sh / sw, cx, ground + r0 * 0.04f)
        c.drawCircle(cx, ground + r0 * 0.04f, sw, fill)
        c.restore()
        fill.shader = null
    }

    private fun drawGlow(c: Canvas) {
        val pulse = 0.55f + 0.25f * spk + 0.35f * mic + 0.25f * flash + 0.06f * sin(time * 1.3f)
        val a = (if (asleep) 0.32f else 0.5f) * pulse
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx, cy, r * 2.3f,
            intArrayOf(Palette.withAlpha(pal[5], a), Palette.withAlpha(pal[5], a * 0.35f), Color.TRANSPARENT),
            floatArrayOf(0.3f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 2.3f, fill)
        fill.shader = null
    }

    /** The soft mochi shape: wider at the bottom, gently wobbling, rippling with sound. */
    private fun buildBody(path: Path) {
        path.rewind()
        val n = 56
        val ripple = spk * 0.035f + mic * 0.06f
        var firstMx = 0f; var firstMy = 0f
        var px = 0f; var py = 0f
        val xs = FloatArray(n); val ys = FloatArray(n)
        for (i in 0 until n) {
            val th = (i.toFloat() / n) * 2f * PI.toFloat()
            val st = sin(th); val ct = cos(th)
            val wv = wob * (sin(3f * th + time * 1.3f) * 0.6f + sin(5f * th - time * 1.9f + 1.2f) * 0.3f + sin(2f * th + time * 0.7f + 2.1f) * 0.5f) +
                ripple * sin(7f * th + time * 11f)
            val rr = r * (1f + wv)
            val rx = 1f + 0.07f * max(0f, st)
            val ry = if (st > 0f) 0.93f else 1f
            xs[i] = cx + rr * ct * rx * sx
            ys[i] = cy + rr * st * ry * sy
        }
        for (i in 0 until n) {
            val nx = xs[(i + 1) % n]; val ny = ys[(i + 1) % n]
            val mx = (xs[i] + nx) / 2f; val my = (ys[i] + ny) / 2f
            if (i == 0) { path.moveTo(mx, my); firstMx = mx; firstMy = my; px = mx; py = my; continue }
            path.quadTo(xs[i], ys[i], mx, my)
            px = mx; py = my
        }
        path.quadTo(xs[0], ys[0], firstMx, firstMy)
        path.close()
    }

    private fun drawBody(c: Canvas) {
        buildBody(bodyPath)
        val left = cx - r * 1.6f; val top = cy - r * 1.6f; val right = cx + r * 1.6f; val bottom = cy + r * 1.6f
        val layer = c.saveLayer(left, top, right, bottom, null)
        // base: lit from the top left
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx - r * 0.32f, cy - r * 0.42f, r * 1.75f,
            intArrayOf(pal[0], pal[1], pal[2]), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(bodyPath, fill)
        fill.shader = null
        // living gradient: three soft colour clouds drifting inside the body
        blob(c, pal[3], 0.78f, cx + r * 0.5f * cos(time * 0.43f), cy + r * 0.38f * sin(time * 0.57f + 1f), r * 0.85f)
        blob(c, pal[4], 0.70f, cx + r * 0.46f * cos(time * 0.31f + 2.4f), cy + r * 0.44f * sin(time * 0.41f + 3.1f), r * 0.8f)
        blob(c, pal[0], 0.55f, cx + r * 0.3f * cos(time * 0.62f + 4.2f), cy - r * 0.3f + r * 0.2f * sin(time * 0.5f), r * 0.6f)
        // volume: darker towards the floor
        atop.color = Color.BLACK; atop.shader = LinearGradient(0f, cy - r * 0.1f, 0f, cy + r * 1.05f,
            Color.TRANSPARENT, Palette.withAlpha(pal[2], 0.55f), Shader.TileMode.CLAMP)
        c.drawRect(left, top, right, bottom, atop)
        // glassy rim
        atop.color = Color.BLACK; atop.shader = RadialGradient(cx, cy, r * 1.08f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Palette.withAlpha(Color.WHITE, 0.30f)),
            floatArrayOf(0f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(left, top, right, bottom, atop)
        atop.shader = null
        c.restoreToCount(layer)

        // gloss
        c.save()
        c.rotate(-28f, cx - r * 0.36f, cy - r * 0.52f)
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx - r * 0.36f, cy - r * 0.52f, r * 0.34f,
            intArrayOf(Palette.withAlpha(Color.WHITE, 0.62f), Palette.withAlpha(Color.WHITE, 0.12f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.scale(1f, 0.6f, cx - r * 0.36f, cy - r * 0.52f)
        c.drawCircle(cx - r * 0.36f, cy - r * 0.52f, r * 0.34f, fill)
        c.restore()
        fill.shader = null
        fill.color = Palette.withAlpha(Color.WHITE, 0.75f)
        c.drawCircle(cx - r * 0.5f, cy - r * 0.47f, r * 0.035f, fill)
    }

    private fun blob(c: Canvas, color: Int, alpha: Float, x: Float, y: Float, radius: Float) {
        atop.color = Color.BLACK; atop.shader = RadialGradient(x, y, radius,
            intArrayOf(Palette.withAlpha(color, alpha), Palette.withAlpha(color, alpha * 0.35f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, radius, atop)
        atop.shader = null
    }

    // ------------------------------------------------------------------ face

    private fun drawFace(c: Canvas) {
        val baby = stage == Stage.BABY
        val eyeW = r * (if (baby) 0.19f else if (stage == Stage.ADULT) 0.15f else 0.17f) * cur.scale
        val eyeH = r * (if (baby) 0.33f else if (stage == Stage.ADULT) 0.26f else 0.29f) * cur.scale
        val spacing = r * (if (baby) 0.31f else 0.30f)
        val fx = cx + gx * r * 0.13f
        val fy = cy - r * 0.08f * sy + gy * r * 0.09f
        val eyeColor = Color.parseColor("#17142A")

        // cheeks first, under the eyes
        if (cur.blush > 0.02f) {
            for (side in intArrayOf(-1, 1)) {
                val bx = fx + side * (spacing + eyeW * 0.35f)
                val by = fy + eyeH * 0.78f
                fill.color = Color.BLACK; fill.shader = RadialGradient(bx, by, eyeW * 1.15f,
                    intArrayOf(Palette.withAlpha(Color.parseColor("#FF6F9C"), 0.55f * cur.blush), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                c.save(); c.scale(1f, 0.55f, bx, by); c.drawCircle(bx, by, eyeW * 1.15f, fill); c.restore()
            }
            fill.shader = null
        }

        val layer = c.saveLayer(fx - spacing - eyeW * 2.5f, fy - eyeH * 1.8f, fx + spacing + eyeW * 2.5f, fy + eyeH * 1.8f, null)
        for (side in intArrayOf(-1, 1)) {
            val ex = fx + side * spacing + gx * r * 0.04f
            val ey = fy + gy * r * 0.035f
            val wink = if (side == 1) cur.wink else 0f
            val open = (cur.open * blink * (1f - wink)).coerceIn(0f, 1.3f)
            if (open < 0.14f) {
                // closed: a soft curve (content / asleep), or ^ for a happy wink
                stroke.color = eyeColor
                stroke.strokeWidth = eyeW * 0.3f
                tmpPath.rewind()
                if (wink > 0.5f) {
                    tmpPath.moveTo(ex - eyeW * 0.65f, ey + eyeH * 0.12f)
                    tmpPath.quadTo(ex, ey - eyeH * 0.32f, ex + eyeW * 0.65f, ey + eyeH * 0.12f)
                } else {
                    tmpPath.moveTo(ex - eyeW * 0.7f, ey)
                    tmpPath.quadTo(ex, ey + eyeH * 0.3f, ex + eyeW * 0.7f, ey)
                }
                c.drawPath(tmpPath, stroke)
                continue
            }
            val hh = eyeH * open
            val topY = ey - eyeH / 2f + (eyeH - hh) * 0.72f
            rect.set(ex - eyeW / 2f, topY, ex + eyeW / 2f, topY + hh)
            fill.color = eyeColor
            c.drawRoundRect(rect, eyeW / 2f, min(eyeW / 2f, hh / 2f), fill)
            // catch-lights
            if (open > 0.45f) {
                fill.color = Palette.withAlpha(Color.WHITE, 0.92f)
                c.drawCircle(ex + eyeW * 0.17f, topY + hh * 0.27f, eyeW * 0.17f, fill)
                fill.color = Palette.withAlpha(Color.WHITE, 0.45f)
                c.drawCircle(ex - eyeW * 0.13f, topY + hh * 0.68f, eyeW * 0.08f, fill)
            }
            // upper lid: tired / sad / grumpy
            val cover = max(cur.lidTop, abs(cur.tilt) * 0.3f)
            if (cover > 0.01f) {
                c.save()
                c.rotate(side * cur.tilt * 24f, ex, topY)
                c.drawRect(ex - eyeW * 1.4f, topY - eyeH * 1.5f, ex + eyeW * 1.4f, topY + hh * cover, erase)
                c.restore()
            }
            // lower lid pushed up by a smile → ∩-shaped happy eyes
            if (cur.smile > 0.02f) {
                val ly = topY + hh + hh * 0.62f * (1f - cur.smile)
                rect.set(ex - eyeW * 1.2f, ly - hh * 0.62f, ex + eyeW * 1.2f, ly + hh * 0.62f)
                c.drawOval(rect, erase)
            }
        }
        c.restoreToCount(layer)

        drawMouth(c, fx, fy + r * 0.27f, eyeColor)
    }

    private fun drawMouth(c: Canvas, mx: Float, my: Float, color: Int) {
        val mw = r * 0.2f * (if (stage == Stage.BABY) 0.85f else 1f)
        val curve = cur.curve
        val open = cur.mouth
        if (open < 0.05f) {
            stroke.color = color
            stroke.strokeWidth = r * 0.038f
            tmpPath.rewind()
            tmpPath.moveTo(mx - mw / 2f, my)
            tmpPath.quadTo(mx, my + curve * r * 0.11f, mx + mw / 2f, my)
            c.drawPath(tmpPath, stroke)
            return
        }
        // open mouth: flatter top, rounder bottom; small „o“ when surprised
        val ow = mw * (0.65f + 0.35f * (1f - open * 0.4f)) * (if (curve < 0.1f && open > 0.3f) 0.7f else 1f)
        val depth = r * (0.05f + open * 0.26f)
        val topCurve = curve * r * 0.05f
        tmpPath.rewind()
        tmpPath.moveTo(mx - ow / 2f, my)
        tmpPath.quadTo(mx, my + topCurve, mx + ow / 2f, my)
        tmpPath.cubicTo(mx + ow / 2f, my + depth * 0.9f, mx - ow / 2f, my + depth * 0.9f, mx - ow / 2f, my)
        tmpPath.close()
        fill.color = color
        c.drawPath(tmpPath, fill)
        if (open > 0.22f) {
            c.save()
            c.clipPath(tmpPath)
            fill.color = Color.parseColor("#FF6F91")
            rect.set(mx - ow * 0.32f, my + depth * 0.42f, mx + ow * 0.32f, my + depth * 1.05f)
            c.drawOval(rect, fill)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ egg

    private fun drawEgg(c: Canvas) {
        val ew = r * 0.8f; val eh = r * 1.05f
        val ecy = ground - eh
        tmpPath.rewind()
        val n = 160          // smooth silhouette even at 1440 px width
        for (i in 0..n) {
            val th = (i.toFloat() / n) * 2f * PI.toFloat()
            val yy = sin(th)
            val x = cx + ew * cos(th) * (1f + 0.13f * yy)
            val y = ecy + eh * yy
            if (i == 0) tmpPath.moveTo(x, y) else tmpPath.lineTo(x, y)
        }
        tmpPath.close()
        val left = cx - r * 1.4f; val top = ecy - eh * 1.3f; val right = cx + r * 1.4f; val bottom = ground + r * 0.2f
        val layer = c.saveLayer(left, top, right, bottom, null)
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx - ew * 0.3f, ecy - eh * 0.42f, eh * 1.9f,
            intArrayOf(pal[0], pal[1], pal[2]), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(tmpPath, fill)
        fill.shader = null
        // iridescent sheen sliding over the shell
        blob(c, pal[3], 0.7f, cx + ew * 0.6f * cos(time * 0.5f), ecy + eh * 0.3f * sin(time * 0.37f), ew * 0.9f)
        blob(c, pal[4], 0.6f, cx + ew * 0.55f * cos(time * 0.42f + 2.6f), ecy + eh * 0.45f * sin(time * 0.33f + 1.4f), ew * 0.85f)
        // a pattern of soft dots, unique per pet (hue)
        val seed = Random(hue.toInt() + 100)
        repeat(7) {
            val a = seed.nextFloat() * 6.28f; val d = 0.25f + seed.nextFloat() * 0.55f
            val px = cx + cos(a) * ew * d; val py = ecy + sin(a) * eh * d * 0.9f + eh * 0.1f
            blob(c, if (it % 2 == 0) pal[3] else pal[4], 0.55f, px, py, ew * (0.09f + seed.nextFloat() * 0.07f))
        }
        atop.color = Color.BLACK; atop.shader = RadialGradient(cx, ecy, eh * 1.1f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Palette.withAlpha(Color.WHITE, 0.35f)),
            floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(left, top, right, bottom, atop)
        atop.shader = null
        c.restoreToCount(layer)

        // light leaking through the cracks
        val cracks = when {
            eggProgress > 0.75f -> 3
            eggProgress > 0.45f -> 2
            eggProgress > 0.15f -> 1
            else -> 0
        }
        if (cracks > 0) {
            stroke.strokeWidth = r * 0.028f
            stroke.color = Palette.withAlpha(Color.parseColor("#FFF4C2"), 0.95f)
            val seedC = Random(3)
            for (k in 0 until cracks) {
                tmpPath.rewind()
                var x = cx + ew * (seedC.nextFloat() * 0.9f - 0.45f)
                var y = ecy - eh * (0.35f - k * 0.18f)
                tmpPath.moveTo(x, y)
                repeat(5) { j ->
                    x += ew * (0.11f + seedC.nextFloat() * 0.06f) * (if (j % 2 == 0) 1 else -1) * (if (k % 2 == 0) 1 else -1)
                    y += eh * (0.07f + seedC.nextFloat() * 0.05f)
                    tmpPath.lineTo(x, y)
                }
                c.drawPath(tmpPath, stroke)
            }
        }
        // gloss
        fill.color = Color.BLACK; fill.shader = RadialGradient(cx - ew * 0.38f, ecy - eh * 0.48f, ew * 0.38f,
            intArrayOf(Palette.withAlpha(Color.WHITE, 0.8f), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx - ew * 0.38f, ecy - eh * 0.48f, ew * 0.38f, fill)
        fill.shader = null
    }

    // ------------------------------------------------------------------ dots (listening / thinking / waking)

    private fun drawDots(c: Canvas) {
        val d = r0 * 0.07f
        if (dotsAlpha > 0.01f) {
            val y = ground + r0 * 0.42f
            val level = if (brain == BrainState.SPEAKING) spk * 1.2f else mic * 7f
            for (i in 0 until 4) {
                val x = cx + (i - 1.5f) * d * 2.4f
                val ph = 0.55f + 0.45f * sin(time * 9f + i * 1.3f)
                val hh = d * (1f + (level * ph).coerceAtMost(1.6f) * 2.2f)
                fill.color = Palette.withAlpha(Color.WHITE, 0.9f * dotsAlpha)
                rect.set(x - d / 2f, y - hh / 2f, x + d / 2f, y + hh / 2f)
                c.drawRoundRect(rect, d / 2f, d / 2f, fill)
            }
        }
        if (thinkAlpha > 0.01f) {
            val y0 = cy - r * 1.32f
            for (i in 0 until 3) {
                val bounce = abs(sin(time * 5.5f - i * 0.7f))
                fill.color = Palette.withAlpha(Color.WHITE, thinkAlpha * (0.45f + 0.5f * bounce))
                c.drawCircle(cx + r * 0.35f + (i - 1) * d * 2.1f, y0 - bounce * r * 0.07f, d * 0.55f, fill)
            }
        }
        if (wakeAlpha > 0.01f) {
            val y = ground + r0 * 0.42f
            for (i in 0 until 4) {
                val p = 0.5f + 0.5f * sin(time * 3f - i * 0.8f)
                fill.color = Palette.withAlpha(Color.WHITE, wakeAlpha * (0.2f + 0.6f * p))
                c.drawCircle(cx + (i - 1.5f) * d * 2.4f, y, d * 0.5f, fill)
            }
        }
    }

    // ------------------------------------------------------------------ particles

    private fun heart(strength: Float) {
        val n = (2 + strength * 3).toInt()
        repeat(n) {
            particles += Particle(HEART, cx + (rnd.nextFloat() - 0.5f) * r * 1.4f, cy - r * 0.6f,
                (rnd.nextFloat() - 0.5f) * r * 0.3f, -r * (0.5f + rnd.nextFloat() * 0.4f),
                0f, 1.6f + rnd.nextFloat() * 0.8f, r * (0.09f + rnd.nextFloat() * 0.07f), Color.parseColor("#FF7AB0"))
        }
    }

    private fun sparkle(n: Int) {
        repeat(n) {
            val a = rnd.nextFloat() * 6.28f
            val sp = r * (0.8f + rnd.nextFloat() * 1.6f)
            val col = if (rnd.nextBoolean()) Color.WHITE else pal[3]
            particles += Particle(SPARK, cx + cos(a) * r * 0.8f, cy + sin(a) * r * 0.8f, cos(a) * sp, sin(a) * sp,
                0f, 0.7f + rnd.nextFloat() * 0.6f, r * (0.05f + rnd.nextFloat() * 0.05f), col, rnd.nextFloat() * 6f, (rnd.nextFloat() - 0.5f) * 8f)
        }
    }

    private fun ring() {
        particles += Particle(RING, cx, cy, 0f, 0f, 0f, 1.1f, r, Color.WHITE)
    }

    private fun zParticle() {
        particles += Particle(ZED, cx + r * 0.55f, cy - r * 0.75f, r * 0.18f, -r * 0.32f, 0f, 2.6f, r * (0.16f + rnd.nextFloat() * 0.08f), Color.WHITE)
    }

    private fun crumbs(at: Pair<Float, Float>, color: Int) {
        repeat(7) {
            particles += Particle(CRUMB, at.first, at.second, (rnd.nextFloat() - 0.5f) * r * 1.2f, -r * rnd.nextFloat() * 0.8f,
                0f, 0.7f + rnd.nextFloat() * 0.4f, r * (0.02f + rnd.nextFloat() * 0.02f), color)
        }
    }

    private fun stepParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life += dt
            if (p.life >= p.max) { it.remove(); continue }
            when (p.type) {
                HEART -> { p.x += (p.vx + sin(p.life * 4f + p.size) * r * 0.15f) * dt; p.y += p.vy * dt }
                SPARK -> { p.x += p.vx * dt; p.y += p.vy * dt; p.vx *= 0.9f; p.vy *= 0.9f; p.rot += p.spin * dt }
                ZED -> { p.x += p.vx * dt + sin(p.life * 2f) * 0.3f; p.y += p.vy * dt }
                CRUMB -> { p.vy += r * 3f * dt; p.x += p.vx * dt; p.y += p.vy * dt }
            }
        }
        if (particles.size > 160) particles.subList(0, particles.size - 160).clear()
    }

    private fun drawParticles(c: Canvas) {
        for (p in particles) {
            val f = p.life / p.max
            val fade = if (f < 0.15f) f / 0.15f else 1f - (f - 0.15f) / 0.85f
            when (p.type) {
                HEART -> { fill.color = Palette.withAlpha(p.color, fade * 0.95f); heartPath(p.x, p.y, p.size * (0.8f + 0.3f * f)); c.drawPath(tmpPath, fill) }
                SPARK -> { fill.color = Palette.withAlpha(p.color, fade); starPath(p.x, p.y, p.size, p.size * 0.28f, 4, p.rot); c.drawPath(tmpPath, fill) }
                ZED -> {
                    text.color = Palette.withAlpha(p.color, fade * 0.8f)
                    text.textSize = p.size * (0.7f + 0.6f * f)
                    c.drawText("z", p.x, p.y, text)
                }
                CRUMB -> { fill.color = Palette.withAlpha(p.color, fade); c.drawCircle(p.x, p.y, p.size, fill) }
                RING -> {
                    stroke.color = Palette.withAlpha(p.color, (1f - f) * 0.7f)
                    stroke.strokeWidth = r * 0.04f * (1f - f)
                    c.drawCircle(p.x, p.y, p.size * (1f + f * 1.8f), stroke)
                }
            }
        }
    }

    private fun heartPath(x: Float, y: Float, s: Float) {
        tmpPath.rewind()
        tmpPath.moveTo(x, y + s * 0.35f)
        tmpPath.cubicTo(x - s * 1.1f, y - s * 0.35f, x - s * 0.45f, y - s * 1.05f, x, y - s * 0.45f)
        tmpPath.cubicTo(x + s * 0.45f, y - s * 1.05f, x + s * 1.1f, y - s * 0.35f, x, y + s * 0.35f)
        tmpPath.close()
    }

    private fun starPath(x: Float, y: Float, outer: Float, inner: Float, points: Int, rot: Float) {
        tmpPath.rewind()
        for (i in 0 until points * 2) {
            val a = rot + i * PI.toFloat() / points
            val rr = if (i % 2 == 0) outer else inner
            val px = x + cos(a) * rr; val py = y + sin(a) * rr
            if (i == 0) tmpPath.moveTo(px, py) else tmpPath.lineTo(px, py)
        }
        tmpPath.close()
    }

    // ------------------------------------------------------------------ food

    private fun mouthPos() = Pair(cx + gx * r * 0.13f, cy - r * 0.08f * sy + r * 0.3f)

    private fun foodPos(f: FoodFlight): Pair<Float, Float> {
        val (mx, my) = mouthPos()
        val t = f.t
        val ctrlX = (f.sx + mx) / 2f + r * 0.3f
        val ctrlY = min(f.sy, my) - r * 1.2f
        val u = 1f - t
        return Pair(u * u * f.sx + 2 * u * t * ctrlX + t * t * mx, u * u * f.sy + 2 * u * t * ctrlY + t * t * my)
    }

    private fun stepFood(dt: Float) {
        val f = food ?: return
        if (!f.chewing) {
            f.t += dt / 0.85f
            if (f.t >= 1f) {
                f.t = 1f
                f.chewing = true
                squash(1.5f)
                crumbs(mouthPos(), if (f.food == Food.BERRY) Color.parseColor("#FF5FA8") else Color.parseColor("#FFD27A"))
                listener?.onFoodEaten(f.food)
                haptic()
            }
        } else {
            f.chew += dt
            if (f.chew > 1.3f) {
                food = null
                flash(Emotion.JOY, 2.5f)
                jump(0.6f)
                sparkle(6)
            }
        }
    }

    private fun drawFood(c: Canvas) {
        val f = food ?: return
        if (f.chewing) return
        val (x, y) = foodPos(f)
        val s = r * 0.2f * (1f - 0.35f * max(0f, f.t - 0.8f) / 0.2f)
        // glow
        fill.color = Color.BLACK; fill.shader = RadialGradient(x, y, s * 2.4f,
            intArrayOf(Palette.withAlpha(if (f.food == Food.BERRY) Color.parseColor("#FF5FA8") else Color.parseColor("#FFD27A"), 0.45f), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, s * 2.4f, fill)
        if (f.food == Food.BERRY) {
            fill.color = Color.BLACK; fill.shader = RadialGradient(x - s * 0.35f, y - s * 0.35f, s * 1.3f,
                intArrayOf(Color.parseColor("#FFD1EC"), Color.parseColor("#FF4FA0"), Color.parseColor("#8E2BD6")),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, s, fill)
            fill.shader = null
            fill.color = Color.parseColor("#7DF2A9")
            rect.set(x - s * 0.05f, y - s * 1.35f, x + s * 0.55f, y - s * 0.85f)
            c.save(); c.rotate(-30f, x, y - s); c.drawOval(rect, fill); c.restore()
        } else {
            fill.color = Color.BLACK; fill.shader = RadialGradient(x - s * 0.3f, y - s * 0.3f, s * 1.4f,
                intArrayOf(Color.parseColor("#FFF6CF"), Color.parseColor("#FFC85A"), Color.parseColor("#E07B2E")),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            starPath(x, y, s * 1.15f, s * 0.55f, 5, -PI.toFloat() / 2f + time * 2f)
            c.drawPath(tmpPath, fill)
            fill.shader = null
        }
        fill.shader = null
    }

    // ------------------------------------------------------------------ firefly game

    private fun stepGame(dt: Float) {
        if (gameLeft <= 0f) { flies.clear(); return }
        gameLeft -= dt
        if (gameLeft <= 0f) {
            gameLeft = 0f
            flies.clear()
            listener?.onGameEnd(gameCaught)
            return
        }
        nextFly -= dt
        if (nextFly <= 0f && flies.count { !it.caught } < 3) {
            nextFly = 0.55f + rnd.nextFloat() * 0.7f
            var x: Float; var y: Float
            do {
                x = width * (0.12f + rnd.nextFloat() * 0.76f)
                y = height * (0.14f + rnd.nextFloat() * 0.5f)
            } while (hypot(x - cx, y - cy) < r * 1.15f)
            flies += Firefly(x, y, (rnd.nextFloat() - 0.5f) * r * 0.6f, (rnd.nextFloat() - 0.5f) * r * 0.5f, 2.2f + rnd.nextFloat() * 0.9f, rnd.nextFloat() * 6f)
        }
        val it = flies.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.age += dt
            if (f.age > (if (f.caught) 0.35f else f.life)) { it.remove(); continue }
            if (!f.caught) {
                f.vx += sin(time * 2f + f.phase) * r * 0.9f * dt
                f.vy += cos(time * 1.7f + f.phase) * r * 0.9f * dt
                f.x += f.vx * dt; f.y += f.vy * dt
                f.x = f.x.coerceIn(width * 0.06f, width * 0.94f); f.y = f.y.coerceIn(height * 0.08f, height * 0.7f)
            }
        }
    }

    private fun drawFlies(c: Canvas) {
        for (f in flies) {
            val life = if (f.caught) 1f - f.age / 0.35f else min(1f, f.age / 0.25f) * min(1f, (f.life - f.age) / 0.4f)
            val pulse = 0.75f + 0.25f * sin(time * 8f + f.phase)
            val s = r * 0.075f * (if (f.caught) 1f + (f.age / 0.35f) * 2.5f else 1f)
            fill.color = Color.BLACK; fill.shader = RadialGradient(f.x, f.y, s * 4.5f,
                intArrayOf(Palette.withAlpha(Color.parseColor("#E9FF8A"), 0.6f * life * pulse), Palette.withAlpha(Color.parseColor("#9BFF6B"), 0.2f * life), Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(f.x, f.y, s * 4.5f, fill)
            fill.shader = null
            fill.color = Palette.withAlpha(Color.parseColor("#FFFFE0"), life)
            c.drawCircle(f.x, f.y, s * 0.8f, fill)
        }
    }

    private fun drawGameHud(c: Canvas, w: Float) {
        if (gameLeft <= 0f) return
        val y = height * 0.2f
        text.color = Palette.withAlpha(Color.WHITE, 0.95f)
        text.textSize = 30f * density
        c.drawText("✦ $gameCaught", w / 2f, y, text)
        val bw = w * 0.36f
        stroke.color = Palette.withAlpha(Color.WHITE, 0.18f)
        stroke.strokeWidth = 4f * density
        c.drawLine(w / 2f - bw / 2f, y + 16f * density, w / 2f + bw / 2f, y + 16f * density, stroke)
        stroke.color = Palette.withAlpha(Color.parseColor("#E9FF8A"), 0.9f)
        val p = (gameLeft / gameTotal).coerceIn(0f, 1f)
        c.drawLine(w / 2f - bw / 2f, y + 16f * density, w / 2f - bw / 2f + bw * p, y + 16f * density, stroke)
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true; touchX = x; touchY = y; downX = x; downY = y; downAt = e.eventTime
                moved = 0f; strokeDist = 0f
                touchInBody = hypot(x - cx, y - cy) < r * 1.15f
                if (gameLeft > 0f && tryCatch(x, y)) { touching = false; return true }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val d = hypot(x - touchX, y - touchY)
                moved += d
                touchX = x; touchY = y
                val inBody = hypot(x - cx, y - cy) < r * 1.2f
                if (inBody && touchInBody && stage != Stage.EGG) {
                    strokeDist += d
                    lastStrokeAt = time
                    if (strokeDist > r * 0.9f && e.eventTime - lastStrokeCredit > 1500L) {
                        strokeDist = 0f
                        lastStrokeCredit = e.eventTime
                        listener?.onStroke()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                touching = false
                val tap = moved < 14f * density && e.eventTime - downAt < 350L
                if (tap) {
                    performClick()
                    if (hypot(x - cx, y - cy) < r * 1.15f) {
                        if (stage == Stage.EGG) listener?.onEggTap() else { squash(2f); listener?.onPoke() }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> touching = false
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun tryCatch(x: Float, y: Float): Boolean {
        val hit = flies.filter { !it.caught }.minByOrNull { hypot(it.x - x, it.y - y) } ?: return false
        if (hypot(hit.x - x, hit.y - y) > max(r * 0.42f, 44f * density)) return false
        hit.caught = true; hit.age = 0f
        gameCaught++
        repeat(6) {
            val a = rnd.nextFloat() * 6.28f; val sp = r * (0.5f + rnd.nextFloat())
            particles += Particle(SPARK, hit.x, hit.y, cos(a) * sp, sin(a) * sp, 0f, 0.6f, r * 0.05f, Color.parseColor("#E9FF8A"), 0f, 6f)
        }
        flash(Emotion.EXCITED, 1.2f)
        haptic()
        listener?.onFireflyCaught(gameCaught)
        return true
    }

    private fun haptic() {
        if (!testMode) runCatching { performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    }

    private companion object {
        const val HEART = 0
        const val SPARK = 1
        const val ZED = 2
        const val CRUMB = 3
        const val RING = 4
    }
}
