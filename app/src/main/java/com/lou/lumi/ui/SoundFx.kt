package com.lou.lumi.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Little synthesised sounds – no audio files: a chirp for a poke, a giggle, munching, a „pling“
 * for a caught firefly, a sparkle arpeggio for hatching. Generated once, played from static
 * AudioTracks so a tap never waits.
 */
class SoundFx {

    enum class Sfx { CHIRP, GIGGLE, MUNCH, PLING, HATCH, PURR, SLEEPY, NOPE }

    var enabled = true
    private val rate = 22_050
    private val main = Handler(Looper.getMainLooper())
    private val pcm: Map<Sfx, ShortArray> by lazy {
        mapOf(
            Sfx.CHIRP to chirp(520.0, 1150.0, 0.13),
            Sfx.GIGGLE to concat(chirp(700.0, 1100.0, 0.07), silence(0.035), chirp(760.0, 1250.0, 0.07), silence(0.035), chirp(820.0, 1400.0, 0.09)),
            Sfx.MUNCH to munch(),
            Sfx.PLING to bell(1568.0, 0.35),
            Sfx.HATCH to concat(bell(784.0, 0.16), bell(988.0, 0.16), bell(1175.0, 0.16), bell(1568.0, 0.45)),
            Sfx.PURR to purr(),
            Sfx.SLEEPY to chirp(620.0, 300.0, 0.45, volume = 0.25),
            Sfx.NOPE to concat(chirp(420.0, 330.0, 0.09), silence(0.04), chirp(400.0, 300.0, 0.12)),
        )
    }

    fun play(s: Sfx) {
        if (!enabled) return
        val data = pcm[s] ?: return
        Thread {
            runCatching {
                val track = AudioTrack(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    data.size * 2, AudioTrack.MODE_STATIC, 0,
                )
                track.write(data, 0, data.size)
                track.play()
                main.postDelayed({ runCatching { track.release() } }, data.size * 1000L / rate + 250L)
            }
        }.start()
    }

    // ------------------------------------------------------------------ synthesis

    private fun n(sec: Double) = (sec * rate).toInt()

    private fun env(i: Int, len: Int, attack: Double = 0.012): Double {
        val t = i.toDouble() / rate
        val a = (t / attack).coerceAtMost(1.0)
        val rel = ((len - i).toDouble() / rate / 0.04).coerceAtMost(1.0)
        return a * rel
    }

    /** A rising/falling sine glide with a soft second harmonic – cute, not beepy. */
    private fun chirp(f0: Double, f1: Double, sec: Double, volume: Double = 0.32): ShortArray {
        val len = n(sec)
        var ph = 0.0
        return ShortArray(len) { i ->
            val p = i.toDouble() / len
            val f = f0 + (f1 - f0) * (p * p * (3 - 2 * p))
            ph += 2 * PI * f / rate
            val v = (sin(ph) + 0.25 * sin(2 * ph)) * env(i, len) * volume
            (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    private fun bell(f: Double, sec: Double): ShortArray {
        val len = n(sec)
        return ShortArray(len) { i ->
            val t = i.toDouble() / rate
            val e = exp(-t * 7.0) * env(i, len, 0.004)
            val v = (sin(2 * PI * f * t) * 0.6 + sin(2 * PI * f * 2.01 * t) * 0.25 + sin(2 * PI * f * 3.02 * t) * 0.1) * e * 0.35
            (v * 32767).toInt().toShort()
        }
    }

    private fun munch(): ShortArray {
        val r = Random(5)
        val parts = (0 until 3).map {
            val len = n(0.07)
            var lp = 0.0
            ShortArray(len) { i ->
                lp += (r.nextDouble() * 2 - 1 - lp) * 0.18
                (lp * env(i, len, 0.003) * 0.9 * 32767).toInt().toShort()
            }
        }
        return concat(parts[0], silence(0.09), parts[1], silence(0.09), parts[2])
    }

    private fun purr(): ShortArray {
        val len = n(0.7)
        val r = Random(9)
        var lp = 0.0
        return ShortArray(len) { i ->
            val t = i.toDouble() / rate
            lp += (r.nextDouble() * 2 - 1 - lp) * 0.05
            val am = 0.5 + 0.5 * sin(2 * PI * 24 * t)
            val v = (lp * 2.2 * am + sin(2 * PI * 95 * t) * 0.15 * am) * env(i, len, 0.08) * 0.5
            (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    private fun silence(sec: Double) = ShortArray(n(sec))

    private fun concat(vararg a: ShortArray): ShortArray {
        val out = ShortArray(a.sumOf { it.size })
        var o = 0
        for (x in a) { x.copyInto(out, o); o += x.size }
        return out
    }
}
