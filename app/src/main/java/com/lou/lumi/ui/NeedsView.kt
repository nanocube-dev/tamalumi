package com.lou.lumi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Four quiet rings – Hunger, Energie, Laune, Nähe – with a hand-drawn glyph each. A ring that runs
 * low breathes softly in a warmer colour instead of flashing a warning.
 */
class NeedsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val values = floatArrayOf(0.7f, 0.9f, 0.7f, 0.3f)
    private val shown = values.copyOf()
    private val labels = arrayOf("Hunger", "Energie", "Laune", "Nähe")
    private val colors = arrayOf(
        intArrayOf(Color.parseColor("#FFB067"), Color.parseColor("#FF5FA8")),
        intArrayOf(Color.parseColor("#7FD4FF"), Color.parseColor("#8E7CFF")),
        intArrayOf(Color.parseColor("#FFE066"), Color.parseColor("#FF9E6B")),
        intArrayOf(Color.parseColor("#FF8FC8"), Color.parseColor("#C58EFF")),
    )
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = Color.parseColor("#99E9EEF7")
    }
    private val oval = RectF()
    private val path = Path()
    private var t0 = System.currentTimeMillis()

    /** Satiety, energy, fun, bond – 0..100. */
    fun setNeeds(satiety: Double, energy: Double, cheer: Double, bond: Double) {
        values[0] = (satiety / 100).toFloat(); values[1] = (energy / 100).toFloat()
        values[2] = (cheer / 100).toFloat(); values[3] = (bond / 100).toFloat()
        contentDescription = labels.indices.joinToString(", ") { "${labels[it]} ${(values[it] * 100).toInt()} Prozent" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val cell = w / 4f
        val labelSize = 11f * density
        label.textSize = labelSize
        val ring = min(cell * 0.36f, (h - labelSize * 1.7f) / 2.4f)
        val stroke = ring * 0.16f
        val time = (System.currentTimeMillis() - t0) / 1000f
        var animating = false
        for (i in 0 until 4) {
            val d = values[i] - shown[i]
            if (kotlin.math.abs(d) > 0.002f) { shown[i] += d * 0.12f; animating = true } else shown[i] = values[i]
            val cx = cell * (i + 0.5f)
            val cy = ring + stroke
            val low = shown[i] < 0.25f
            val pulse = if (low) 0.55f + 0.45f * (0.5f + 0.5f * sin(time * 3.2f)) else 1f
            if (low) animating = true
            track.strokeWidth = stroke
            track.color = Color.parseColor("#1FFFFFFF")
            canvas.drawCircle(cx, cy, ring, track)
            arc.strokeWidth = stroke
            arc.shader = SweepGradient(cx, cy, intArrayOf(colors[i][0], colors[i][1], colors[i][0]), floatArrayOf(0f, 0.5f, 1f))
            arc.alpha = (255 * pulse).toInt()
            oval.set(cx - ring, cy - ring, cx + ring, cy + ring)
            canvas.drawArc(oval, -90f, 360f * shown[i].coerceIn(0.015f, 1f), false, arc)
            glyph.color = Color.argb((235 * pulse).toInt(), 255, 255, 255)
            drawGlyph(canvas, i, cx, cy, ring * 0.5f)
            canvas.drawText(labels[i], cx, cy + ring + stroke + labelSize * 1.15f, label)
        }
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawGlyph(c: Canvas, i: Int, x: Float, y: Float, s: Float) {
        path.rewind()
        when (i) {
            0 -> { // berry with a leaf
                c.drawCircle(x, y + s * 0.18f, s * 0.62f, glyph)
                path.moveTo(x, y - s * 0.42f)
                path.quadTo(x + s * 0.2f, y - s * 1.0f, x + s * 0.75f, y - s * 0.85f)
                path.quadTo(x + s * 0.45f, y - s * 0.38f, x, y - s * 0.42f)
                c.drawPath(path, glyph)
            }
            1 -> { // crescent moon
                path.addCircle(x, y, s * 0.75f, Path.Direction.CW)
                val cut = Path().apply { addCircle(x + s * 0.42f, y - s * 0.3f, s * 0.62f, Path.Direction.CW) }
                path.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(path, glyph)
            }
            2 -> { // sparkle
                for (k in 0 until 8) {
                    val a = k * Math.PI.toFloat() / 4f - Math.PI.toFloat() / 2f
                    val rr = if (k % 2 == 0) s * 0.85f else s * 0.25f
                    val px = x + kotlin.math.cos(a) * rr; val py = y + kotlin.math.sin(a) * rr
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close(); c.drawPath(path, glyph)
            }
            else -> { // heart
                path.moveTo(x, y + s * 0.7f)
                path.cubicTo(x - s * 1.25f, y - s * 0.1f, x - s * 0.55f, y - s * 1.0f, x, y - s * 0.35f)
                path.cubicTo(x + s * 0.55f, y - s * 1.0f, x + s * 1.25f, y - s * 0.1f, x, y + s * 0.7f)
                path.close(); c.drawPath(path, glyph)
            }
        }
    }
}
