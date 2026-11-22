package com.lou.lumi.brain

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.lou.lumi.pet.Emotion
import com.lou.lumi.pet.PetEngine
import com.lou.lumi.pet.PetKeeper
import com.lou.lumi.pet.PetState
import com.lou.lumi.pet.Reaction
import com.lou.lumi.pet.Stage
import com.lou.lumi.voice.AudioIn
import com.lou.lumi.voice.AudioOut
import com.lou.lumi.voice.Config
import com.lou.lumi.voice.LlmEngine
import com.lou.lumi.voice.ModelStore
import com.lou.lumi.voice.SentenceSplitter
import com.lou.lumi.voice.SttEngine
import com.lou.lumi.voice.TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Lumi's voice: the cascade from Gemma Voice, 100 % on the phone –
 *
 *   mic ─> Silero VAD ─> Whisper ─> Gemma 4 E2B (LiteRT-LM) ─> [Gefühl]-Filter ─> Satz-Chunker ─> Piper ─> speaker
 *
 * Differences to the assistant: no wake word – a conversation is opened with the mic button and
 * closes by itself after [Config.SESSION_IDLE_MS] of silence; every message carries the pet's
 * current state; the answer's leading emotion tag drives the face; the voice is pitched up the
 * younger the pet is. Barge-in (talking over Lumi stops it) works as before.
 */
class PetBrain(private val context: Context, private val keeper: PetKeeper) {

    val state = MutableStateFlow(BrainState.OFF)
    val status = MutableStateFlow("Gehirn schläft noch")
    /** What Whisper understood last. */
    val heard = MutableStateFlow("")
    /** Lumi's current answer, without tags, growing while it is generated. */
    val answer = MutableStateFlow("")
    /** Latest feeling from an answer tag; the UI turns it into a face (value + sequence number). */
    val emotion = MutableStateFlow<Pair<Emotion, Long>?>(null)
    val micLevel = MutableStateFlow(0f)
    val speakingLevel = MutableStateFlow(0f)
    val metrics = MutableStateFlow("")

    /** Extra pitch on top of the stage pitch (Settings → Stimmlage), 0.85 … 1.25. */
    @Volatile var pitchOffset = 1f

    @Volatile private var store = ModelStore(context)
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val loaded = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val alive = AtomicBoolean(false)
    private val sessionOpen = AtomicBoolean(false)
    private val pendingSession = AtomicBoolean(false)
    private val speakingNow = AtomicBoolean(false)
    private val recycling = AtomicBoolean(false)
    private val gpuFallbackTried = AtomicBoolean(false)
    private val turnId = AtomicLong(0)
    private val sessionUntil = AtomicLong(0)
    private val lastTurnAt = AtomicLong(0)
    private val turnLang = AtomicReference("de")
    /** Spoken „Gute Nacht“: Lumi answers first, then falls asleep. */
    private val sleepAfterSpeech = AtomicBoolean(false)
    private val preferGpu = AtomicBoolean(true)

    private var stt: SttEngine? = null
    private var tts: TtsEngine? = null
    private var llm: LlmEngine? = null
    @Volatile private var audioIn: AudioIn? = null
    @Volatile private var audioOut: AudioOut? = null

    private val segments = LinkedBlockingQueue<FloatArray>()
    private val chunks = LinkedBlockingQueue<String>()
    private var sttThread: Thread? = null
    private var ttsThread: Thread? = null
    private var bargeSpeechSince = 0L
    @Volatile private var speakingPeak = 0f

    private var lastSttMs = 0L
    private var lastTtftMs = 0L
    private var lastTps = 0.0
    @Volatile private var lastTtsMs = 0L

    val isLoaded: Boolean get() = loaded.get()
    val isTalking: Boolean get() = sessionOpen.get()

    // ------------------------------------------------------------------ models

    fun modelsMissing(): List<String> = store.missing()

    fun modelsPresent(): Boolean = store.missing().isEmpty()

    fun refreshModels() { store = ModelStore(context) }

    fun modelOverview(): String = store.describe().joinToString("\n") { (label, f) ->
        (if (f.exists()) "✓ " else "✗ ") + label + if (f.exists()) " (" + human(f.length()) + ")" else ""
    }

    fun modelRoot(): String = store.root.absolutePath

    // ------------------------------------------------------------------ lifecycle

    /** Load Whisper, Piper and Gemma in the background (~7 s). No-op without models. */
    fun load() {
        if (loaded.get() || loading.getAndSet(true)) return
        if (!modelsPresent()) {
            loading.set(false)
            state.value = BrainState.OFF
            status.value = "Kein Gehirn installiert · ⋯ → Gehirn importieren"
            return
        }
        Thread {
            try {
                state.value = BrainState.WAKING
                val t0 = System.currentTimeMillis()
                status.value = "wacht auf … (Ohren)"
                stt = SttEngine(store).also { it.init() }
                status.value = "wacht auf … (Stimme)"
                if (Config.ttsVoiceId["de"] == null) preferredVoice()?.let { Config.ttsVoiceId["de"] = it }
                tts = TtsEngine(store).also { it.init() }
                status.value = "wacht auf … (Gemma 4)"
                llm = LlmEngine(store).also {
                    it.systemPrompt = Persona.systemPrompt(keeper.state.value, System.currentTimeMillis())
                    it.init(preferGpu.get()) { msg -> status.value = msg }
                }
                preferGpu.set(llm!!.usingGpu)
                startThreads()
                loaded.set(true)
                val secs = (System.currentTimeMillis() - t0) / 1000
                status.value = "wach · alles offline · ${if (preferGpu.get()) "GPU" else "CPU"} · ${secs}s"
                state.value = BrainState.READY
                Log.i(TAG, "brain up in ${secs}s, bundle=${llm?.bundleName()}")
                if (pendingSession.getAndSet(false)) startConversation()
            } catch (t: Throwable) {
                Log.e(TAG, "load failed", t)
                status.value = "Gehirn-Fehler: ${t.message ?: t.javaClass.simpleName}"
                state.value = BrainState.ERROR
                releaseEngines()
            } finally {
                loading.set(false)
            }
        }.apply { name = "brain-load"; start() }
    }

    fun shutdown() {
        endConversation()
        alive.set(false)
        stopThreads()
        audioOut?.release(); audioOut = null
        releaseEngines()
        loaded.set(false)
        state.value = BrainState.OFF
    }

    private fun releaseEngines() {
        runCatching { stt?.release() }; runCatching { tts?.release() }; runCatching { llm?.release() }
        stt = null; tts = null; llm = null
    }

    // ------------------------------------------------------------------ conversation (open mic)

    /** Mic button: open the conversation. Loads the brain first if needed. */
    fun startConversation() {
        if (!loaded.get()) {
            pendingSession.set(true)
            load()
            return
        }
        if (sessionOpen.getAndSet(true)) return
        refreshPersonaIfIdle()
        val mic = AudioIn(::handleFrame)
        if (!mic.start()) {
            sessionOpen.set(false)
            status.value = "Mikrofon nicht verfügbar (Berechtigung?)"
            return
        }
        audioIn = mic
        Thread { ensureOut() }.start()        // reopen on the voice-communication path (AEC)
        sessionUntil.set(System.currentTimeMillis() + Config.SESSION_IDLE_MS)
        bargeSpeechSince = 0L
        if (state.value == BrainState.READY) state.value = BrainState.LISTENING
        status.value = "hört zu …"
        Log.i(TAG, "conversation open")
    }

    /** Mic button again, timeout, or the app goes to the background. */
    fun endConversation() {
        pendingSession.set(false)
        if (!sessionOpen.getAndSet(false)) return
        audioIn?.stop(); audioIn = null
        stt?.resetVad()
        segments.clear()
        micLevel.value = 0f
        audioOut?.setCommunicationMode(audioManager, false)
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
        if (!speakingNow.get()) Thread { ensureOut() }.start()   // back to plain speaker output
        if (state.value == BrainState.LISTENING || state.value == BrainState.HEARING) state.value = BrainState.READY
        if (loaded.get()) status.value = "wach · tippe aufs Mikro zum Reden"
        Log.i(TAG, "conversation closed")
        maybeRecycleEngine()
    }

    /** Typed message (or a probe): answered out loud even with the mic closed. */
    fun say(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (!loaded.get()) { status.value = "Gehirn ist noch nicht wach"; return }
        Thread { onUtterance(t, guessLang(t), interrupted = speakingNow.get(), typed = true) }
            .apply { name = "typed-turn"; start() }
    }

    /** Stop talking right now (button / sleep). */
    fun hush() = interrupt("still")

    // ------------------------------------------------------------------ threads

    private fun startThreads() {
        alive.set(true)
        sttThread = Thread {
            while (alive.get()) {
                val samples = try { segments.poll(150, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (samples == null) {
                    // a conversation nobody talks in closes by itself
                    if (sessionOpen.get() && !speakingNow.get() && state.value == BrainState.LISTENING &&
                        System.currentTimeMillis() > sessionUntil.get()) {
                        Log.i(TAG, "conversation idle → closing")
                        endConversation()
                    }
                    continue
                }
                val engine = stt ?: continue
                val wasSpeaking = speakingNow.get()
                state.compareAndSet(BrainState.LISTENING, BrainState.HEARING)
                val t = engine.transcribe(samples)
                lastSttMs = t.ms
                Log.i(TAG, "STT[${t.lang}] ${t.ms}ms: ${t.text}")
                val text = cleanTranscript(t.text)
                if (text.length < 2 || (wasSpeaking && text.split(" ").size < 3)) {
                    backToListening(); continue
                }
                sessionUntil.set(System.currentTimeMillis() + Config.SESSION_IDLE_MS)
                onUtterance(text, t.lang.ifEmpty { guessLang(text) }, wasSpeaking, typed = false)
            }
        }.apply { name = "stt-worker"; priority = Thread.NORM_PRIORITY + 1; start() }

        ttsThread = Thread {
            while (alive.get()) {
                val chunk = try { chunks.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
                if (chunk == END) {
                    if (speakingNow.get()) {
                        audioOut?.drain()
                        speakingNow.set(false)
                        speakingLevel.value = 0f
                        if (state.value == BrainState.SPEAKING) state.value = if (sessionOpen.get()) BrainState.LISTENING else BrainState.READY
                        sessionUntil.set(System.currentTimeMillis() + Config.SESSION_IDLE_MS)
                    }
                    if (sleepAfterSpeech.getAndSet(false)) {
                        keeper.spokenActions.tryEmit(Understanding.Action.SLEEP)
                        endConversation()
                    }
                    continue
                }
                val out = ensureOut() ?: continue
                if (!speakingNow.get()) {
                    speakingPeak = 0f
                    speakingNow.set(true)
                    state.value = BrainState.SPEAKING
                    bargeSpeechSince = 0L
                }
                val engine = tts ?: continue
                val t0 = System.currentTimeMillis()
                engine.speakChunk(chunk, turnLang.get(), speed = Config.ttsSpeed * 1.04f / currentPitch()) { samples -> out.write(samples) }
                lastTtsMs = System.currentTimeMillis() - t0
                renderMetrics()
            }
        }.apply { name = "tts-worker"; priority = Thread.NORM_PRIORITY + 1; start() }
    }

    private fun stopThreads() {
        sttThread?.let { runCatching { it.interrupt(); it.join(500) } }
        ttsThread?.let { runCatching { it.interrupt(); it.join(500) } }
        sttThread = null; ttsThread = null
        segments.clear(); chunks.clear()
    }

    /**
     * The younger, the higher: Piper renders slower (speed = 1/pitch) and the track plays faster
     * by the same factor – same tempo, brighter voice. Cheap, no extra model, sounds cute.
     */
    private fun currentPitch(): Float {
        val s = keeper.state.value
        val stagePitch = when (PetEngine.stage(s, System.currentTimeMillis())) {
            Stage.EGG, Stage.BABY -> 1.30f
            Stage.CHILD -> 1.20f
            Stage.TEEN -> 1.11f
            Stage.ADULT -> 1.04f
        }
        return (stagePitch * pitchOffset).coerceIn(0.8f, 1.6f)
    }

    /**
     * Playback stream at the pitched rate; reopened when the stage (pitch) changes or the mic
     * opens/closes (voice-communication path with echo cancelling vs. plain speaker output).
     */
    @Synchronized
    private fun ensureOut(): AudioOut? {
        val engine = tts ?: return null
        val rate = (engine.sampleRate() * currentPitch()).toInt()
        val comm = sessionOpen.get()
        audioOut?.let { if (it.rate == rate && it.voiceComm == comm) return it }
        audioOut?.release()
        val o = AudioOut(rate, ::publishSpeakingLevel, voiceComm = comm)
        if (!o.start()) { Log.e(TAG, "AudioOut @ $rate Hz failed"); audioOut = null; return null }
        if (comm) o.setCommunicationMode(audioManager, true)
        audioOut = o
        Log.i(TAG, "Audioausgabe $rate Hz (Tonhöhe ${"%.2f".format(currentPitch())}, ${if (comm) "Gespräch" else "Lautsprecher"})")
        return o
    }

    private fun handleFrame(frame: FloatArray) {
        val s = stt ?: return
        s.accept(frame)
        val rms = rmsOf(frame)
        micLevel.value = rms
        val speech = s.speechActive()
        val now = System.currentTimeMillis()
        if (speech && state.value == BrainState.SPEAKING && rms > Config.BARGE_RMS_FLOOR) {
            if (bargeSpeechSince == 0L) bargeSpeechSince = now
            else if (now - bargeSpeechSince >= Config.BARGE_IN_MS) interrupt("barge-in")
        } else if (!speech) {
            bargeSpeechSince = 0L
        }
        if (speech) sessionUntil.set(now + Config.SESSION_IDLE_MS)
        for (seg in s.pollSegments()) segments.offer(seg.samples)
    }

    private fun publishSpeakingLevel(v: Float) {
        val l = if (speakingNow.get()) v else 0f
        speakingLevel.value = l
        if (l > speakingPeak) speakingPeak = l
    }

    // ------------------------------------------------------------------ turns

    private fun onUtterance(text: String, lang: String, interrupted: Boolean, typed: Boolean) {
        Log.i(TAG, "utterance: '$text' (lang=$lang, interrupted=$interrupted, typed=$typed)")
        heard.value = text
        val now = System.currentTimeMillis()
        val awayMs = if (now - lastTurnAt.get() > 30 * 60_000L) now - keeper.state.value.lastSeenAt else 0L

        // A sleeping Lumi that is talked to wakes up first.
        var event: String? = null
        if (keeper.state.value.asleep) {
            val o = keeper.apply { PetEngine.wake(it) }
            event = if (o.reaction == Reaction.GRUMPY_WAKE) "Du wurdest gerade aus dem Schlaf geweckt und bist noch grummelig."
            else "Du bist gerade aufgewacht."
        }
        event = listOfNotNull(event, understand(text)).joinToString(" ").ifBlank { null }

        val pet = keeper.state.value
        val prompt = Persona.stateLine(pet, PetEngine.mood(pet), now, event, awayMs) + "\n" + text
        turnLang.set(lang)
        lastTurnAt.set(now)
        keeper.change { PetEngine.talked(it) }
        startTurn(prompt)
    }

    /** Care actions, facts and names inside the sentence; returns what to tell Gemma about it. */
    private fun understand(text: String): String? {
        val u = Understanding.analyse(text)
        val notes = ArrayList<String>()
        u.petName?.let { n ->
            keeper.change { it.copy(name = n) }
            notes += "Dein Mensch hat dir gerade den Namen $n gegeben. Freu dich darüber."
            refreshPersona()
        }
        u.userName?.let { n -> keeper.change { it.copy(userName = n) } }
        if (u.forget) {
            keeper.change { it.copy(memories = emptyList()) }
            notes += "Dein Mensch hat dich gebeten, alles über ihn zu vergessen. Du hast es vergessen."
            refreshPersona()
        } else if (u.facts.isNotEmpty()) {
            keeper.change { PetEngine.rememberAll(it, u.facts) }
            Log.i(TAG, "merkt sich: ${u.facts}")
        }
        val pet = keeper.state.value
        when (u.action) {
            Understanding.Action.FEED_BERRY, Understanding.Action.FEED_COOKIE -> {
                val food = Understanding.foodFor(u.action)!!
                val probe = PetEngine.feed(pet, food)
                notes += if (probe.reaction == Reaction.FULL) "Dein Mensch bietet dir eine ${food.label} an, aber du bist pappsatt."
                else "Dein Mensch gibt dir gerade eine ${food.label}. Lecker!"
                keeper.spokenActions.tryEmit(u.action)
            }
            Understanding.Action.PLAY -> {
                notes += when (PetEngine.canPlay(pet)) {
                    Reaction.TOO_TIRED -> "Dein Mensch will spielen, aber du bist zu müde."
                    else -> "Dein Mensch will mit dir Glühwürmchen fangen spielen. Gleich geht es los!"
                }
                keeper.spokenActions.tryEmit(Understanding.Action.PLAY)
            }
            Understanding.Action.SLEEP -> {
                notes += "Dein Mensch bringt dich gerade ins Bett. Sag kurz gute Nacht."
                sleepAfterSpeech.set(true)
            }
            Understanding.Action.PET -> {
                notes += "Dein Mensch knuddelt dich gerade."
                keeper.spokenActions.tryEmit(Understanding.Action.PET)
            }
            Understanding.Action.WAKE, null -> {}
        }
        return notes.joinToString(" ").ifBlank { null }
    }

    private fun startTurn(prompt: String) {
        // A new turn while the last one still runs (typed over a remark, spoke while it thought):
        // stop the old one first – LiteRT-LM must never get two messages at once.
        val wasBusy = llm?.isBusy == true
        val goodNight = sleepAfterSpeech.get()      // set for *this* turn by understand(); interrupt() clears it
        if (wasBusy || speakingNow.get()) interrupt("neue Runde")
        sleepAfterSpeech.set(goodNight)
        if (wasBusy) Thread.sleep(150)
        val id = turnId.incrementAndGet()
        chunks.clear()
        audioOut?.resumeFlag()
        answer.value = ""
        state.value = BrainState.THINKING
        val engine = llm ?: run { state.value = BrainState.ERROR; status.value = "Gemma nicht geladen"; return }
        val acc = StringBuilder()
        val raw = StringBuilder()
        val tagged = AtomicBoolean(false)
        val splitter = SentenceSplitter(minChars = 10) { chunk ->
            if (guard.isBlocked(chunk)) { dropReported(id, acc, chunk); return@SentenceSplitter }
            // no tag from Gemma: read the feeling off the first sentence
            if (turnId.get() == id && !tagged.getAndSet(true)) EmotionTagFilter.guess(chunk)?.let {
                Log.i(TAG, "Gefühl (geraten): $it")
                emotion.value = it to System.nanoTime()
            }
            chunks.offer(chunk)
        }
        val filter = EmotionTagFilter(
            onEmotion = { e ->
                if (turnId.get() == id) {
                    Log.i(TAG, "Gefühl: $e")
                    tagged.set(true); emotion.value = e to System.nanoTime()
                }
            },
            onText = { t ->
                if (turnId.get() == id) {
                    acc.append(t)
                    answer.value = acc.toString().trim()
                    splitter.feed(t)
                }
            },
        )
        Log.i(TAG, "turn #$id: ${prompt.replace('\n', ' ').take(160)}")
        engine.ask(
            text = prompt,
            onToken = { tok -> if (turnId.get() == id) { raw.append(tok); filter.feed(tok) } },
            onDone = { stats ->
                if (turnId.get() != id) return@ask
                filter.flush()
                splitter.flush()?.takeUnless { guard.isBlocked(it).also { b -> if (b) dropReported(id, acc, it) } }?.let { last ->
                    if (!tagged.getAndSet(true)) EmotionTagFilter.guess(last)?.let { emotion.value = it to System.nanoTime() }
                    chunks.offer(last)
                }
                chunks.offer(END)
                lastTtftMs = stats.ttftMs; lastTps = stats.tokensPerSec
                Log.i(TAG, "LLM done: ttft=${stats.ttftMs}ms ${stats.tokens} tok ${"%.1f".format(stats.tokensPerSec)} tok/s: ${raw.toString().take(220)}")
                renderMetrics()
            },
            onError = { msg ->
                Log.e(TAG, "turn failed: $msg")
                if (turnId.get() != id) return@ask
                if (engine.looksLikeGpuFailure(msg) && !gpuFallbackTried.getAndSet(true)) {
                    Thread {
                        runCatching { engine.forceCpu { m -> status.value = m } }
                            .onSuccess { preferGpu.set(false); gpuFallbackTried.set(false); startTurn(prompt) }
                            .onFailure { e -> status.value = "Gemma-Fehler: ${e.message}"; state.value = BrainState.ERROR }
                    }.apply { name = "llm-cpu-fallback"; start() }
                    return@ask
                }
                status.value = "Gemma-Fehler: $msg"
                state.value = if (sessionOpen.get()) BrainState.LISTENING else BrainState.READY
            },
        )
    }

    private fun interrupt(why: String) {
        if (!speakingNow.get() && state.value != BrainState.THINKING && state.value != BrainState.SPEAKING) return
        Log.i(TAG, "interrupt ($why)")
        turnId.incrementAndGet()
        chunks.clear()
        answer.value = answer.value.trimEnd().let { if (it.isEmpty()) it else "$it …" }
        audioOut?.abort()
        speakingLevel.value = 0f
        llm?.cancel()
        speakingNow.set(false)
        sleepAfterSpeech.set(false)
        state.value = if (sessionOpen.get()) BrainState.LISTENING else BrainState.READY
    }

    private fun backToListening() {
        if (!speakingNow.get()) speakingLevel.value = 0f
        state.compareAndSet(BrainState.HEARING, if (sessionOpen.get()) BrainState.LISTENING else BrainState.READY)
    }

    // ------------------------------------------------------------------ persona

    /** New system prompt (name, age, memories) – only between conversations, it drops the context. */
    private fun refreshPersonaIfIdle() {
        if (System.currentTimeMillis() - lastTurnAt.get() < 90_000L) return
        refreshPersona()
    }

    private fun refreshPersona() {
        val e = llm ?: return
        if (e.isBusy) return
        val p = Persona.systemPrompt(keeper.state.value, System.currentTimeMillis())
        if (p != e.systemPrompt) {
            runCatching { e.resetConversation(p) }
            Log.i(TAG, "persona refreshed (${p.length} chars)")
        }
    }

    // ------------------------------------------------------------------ reports (Play policy on AI content)

    /** Sentences from reported answers; they are never spoken or shown again. */
    @Volatile var guard = ReportGuard()

    /** Removes a blocked sentence from the caption text of turn [id] (it is not spoken either). */
    private fun dropReported(id: Long, acc: StringBuilder, sentence: String) {
        Log.i(TAG, "gemeldeter Satz unterdrückt")
        val t = sentence.trim()
        val at = acc.indexOf(t)
        if (at >= 0) acc.replace(at, at + t.length, "…")
        if (turnId.get() == id) answer.value = acc.toString().trim()
    }

    /**
     * The user reported [answer]: Lumi stops at once, its sentences are blocked from now on, and the
     * conversation context starts fresh so Gemma does not build on what was reported.
     */
    fun onAnswerReported(allReported: List<String>) {
        guard = ReportGuard(allReported)
        interrupt("gemeldet")
        Thread {
            repeat(20) { if (llm?.isBusy == true) Thread.sleep(100) }
            forgetConversation()
        }.apply { name = "report-reset"; start() }
    }

    /** Fresh conversation context („Neues Gespräch“) – e.g. after the pet evolved. */
    fun forgetConversation() {
        val e = llm ?: return
        if (e.isBusy) return
        runCatching { e.resetConversation(Persona.systemPrompt(keeper.state.value, System.currentTimeMillis())) }
    }

    /** LiteRT-LM#2062: rebuild the engine now and then – only when nobody is talking (takes ~10 s). */
    private fun maybeRecycleEngine() {
        val e = llm ?: return
        if (recycling.get() || e.isBusy || sessionOpen.get() || speakingNow.get()) return
        if (e.inferences < Config.llmRecycleAfter) return
        recycling.set(true)
        Thread {
            Log.i(TAG, "Engine-Recycling nach ${e.inferences} Antworten (Upstream #2062)")
            e.systemPrompt = Persona.systemPrompt(keeper.state.value, System.currentTimeMillis())
            e.recycle(preferGpu.get()) { m -> status.value = m }
            preferGpu.set(e.usingGpu)
            if (loaded.get()) status.value = "wach · alles offline"
            recycling.set(false)
        }.apply { name = "llm-recycle"; start() }
    }

    // ------------------------------------------------------------------ spontaneous remarks

    private val lastRemarkAt = AtomicLong(0)

    /**
     * Lumi says something on its own about [event] (hatched, back after hours, game over …).
     * Skipped while a conversation turn is running, and at most every 20 s.
     */
    fun remark(event: String, force: Boolean = false) {
        if (!loaded.get()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRemarkAt.get() < 20_000L) return
        if (speakingNow.get() || state.value == BrainState.THINKING || state.value == BrainState.HEARING) return
        lastRemarkAt.set(now)
        val pet = keeper.state.value
        val prompt = Persona.stateLine(pet, PetEngine.mood(pet), now, event) +
            "\n(Niemand hat etwas gesagt. Sag von dir aus etwas ganz Kurzes dazu, höchstens ein Satz.)"
        turnLang.set("de")
        Thread { startTurn(prompt) }.apply { name = "remark"; start() }
    }

    // ------------------------------------------------------------------ voices

    fun voices(): List<ModelStore.Voice> = store.piperVoices("de")

    /** Without a choice: a light voice suits a small pet better than Thorsten's (if one is installed). */
    private fun preferredVoice(): String? {
        val v = voices()
        return listOf("eva", "kerstin", "ramona", "mls").firstNotNullOfOrNull { k -> v.firstOrNull { it.id.contains(k, true) }?.id }
    }

    fun activeVoiceId(): String? = tts?.activeVoiceId("de") ?: store.selectedVoice("de")?.id

    /** Switch the German Piper voice; the playback stream follows its sample rate. */
    fun setVoice(id: String): String? {
        val v = voices().firstOrNull { it.id == id } ?: return null
        Config.ttsVoiceId["de"] = id
        val engine = tts ?: return v.label
        val label = engine.useVoice("de", id) ?: return null
        Thread { ensureOut() }.start()
        return label
    }

    // ------------------------------------------------------------------ probes (adb)

    /** `--es speak "…"`: synthesise a sentence and push it through VAD → Whisper like the mic would. */
    fun speakIntoPipeline(text: String) {
        val engine = tts ?: return
        val s = stt ?: return
        val audio = runCatching { engine.synthesize(text, "de") }.getOrNull() ?: return
        val pcm = resample(audio.samples, audio.sampleRate, Config.SAMPLE_RATE)
        Thread {
            val mic = audioIn
            mic?.stop(); audioIn = null
            s.resetVad()
            var i = 0
            while (i < pcm.size) {
                val end = minOf(i + Config.VAD_WINDOW, pcm.size)
                handleFrame(pcm.copyOfRange(i, end)); i = end
                Thread.sleep(4)
            }
            repeat(40) { handleFrame(FloatArray(Config.VAD_WINDOW)); Thread.sleep(12) }
            if (mic != null && sessionOpen.get() && mic.start()) audioIn = mic
        }.apply { name = "probe-speak"; start() }
    }

    // ------------------------------------------------------------------ helpers

    private fun cleanTranscript(raw: String): String {
        val t = raw.trim()
        // Whisper hallucinates these on noise/silence
        val junk = listOf("Untertitel", "Vielen Dank fürs Zuschauen", "Untertitelung", "Copyright", "Amara.org", "Musik")
        if (junk.any { t.contains(it, ignoreCase = true) } && t.split(" ").size < 9) return ""
        return t.trim('[', ']', '(', ')', ' ')
    }

    private fun guessLang(text: String): String {
        val de = listOf("der", "die", "und", "ist", "ich", "wie", "was", "nicht", "ein", "bitte", "du", "auf", "für", "mit", "den", "das", "hallo", "hast")
        val t = " ${text.lowercase()} "
        return if (Config.sttLanguage == "de" || de.any { t.contains(" $it ") }) "de" else "en"
    }

    private fun rmsOf(frame: FloatArray): Float {
        if (frame.isEmpty()) return 0f
        var acc = 0.0
        for (v in frame) acc += (v * v).toDouble()
        return sqrt(acc / frame.size).toFloat()
    }

    private fun renderMetrics() {
        metrics.value = "STT ${lastSttMs} ms · Gemma(${if (preferGpu.get()) "GPU" else "CPU"}) TTFT ${lastTtftMs} ms · " +
            "%.1f tok/s · TTS ${lastTtsMs} ms".format(lastTps)
    }

    private fun human(b: Long) = if (b > (1L shl 30)) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)

    companion object {
        private const val TAG = "Lumi"
        private const val END = "\u0000end"

        fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
            if (fromRate == toRate || input.isEmpty()) return input
            val ratio = toRate.toDouble() / fromRate
            val out = FloatArray((input.size * ratio).toInt().coerceAtLeast(1))
            for (i in out.indices) {
                val src = i / ratio
                val i0 = src.toInt().coerceIn(0, input.size - 1)
                val i1 = (i0 + 1).coerceAtMost(input.size - 1)
                val frac = (src - i0).toFloat()
                out[i] = input[i0] * (1f - frac) + input[i1] * frac
            }
            return out
        }
    }
}
