package com.lou.lumi.voice

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Stage 3 of the cascade: Gemma 4 E2B, run locally through LiteRT-LM — the same
 * bundling path the talk describes ("the model is bundled with your app").
 * Token-level streaming in, cancellation support for barge-in.
 */
class LlmEngine(private val store: ModelStore) {

    /** Persona of the pet; takes effect with the next [resetConversation] (or engine start). */
    @Volatile var systemPrompt: String = "Du bist ein kleines, freundliches Wesen."

    data class Stats(val ttftMs: Long, val totalMs: Long, val tokens: Int, val tokensPerSec: Double)

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    @Volatile private var busy = false

    /** True while one inference is running. */
    val isBusy: Boolean get() = busy
    @Volatile var usingGpu = false
        private set

    val isReady: Boolean get() = conversation != null

    fun init(preferGpu: Boolean, onProgress: (String) -> Unit) {
        release()
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)

        if (preferGpu) {
            onProgress("Lade Gemma 4 E2B am GPU …")
            try {
                open(Backend.GPU())
                usingGpu = true
                return
            } catch (t: Throwable) {
                Log.w(TAG, "GPU backend failed, falling back to CPU: ${t.message}")
                onProgress("GPU nicht nutzbar, CPU …")
                release()
            }
        }
        open(Backend.CPU(threadCount = Config.llmThreads))
        usingGpu = false
    }

    /** True when the failure looks like an unusable GPU/OpenCL delegate. */
    fun looksLikeGpuFailure(message: String?): Boolean {
        if (!usingGpu) return false
        val m = message ?: return false
        return m.contains("OpenCL", true) || m.contains("GPU", true) ||
            m.contains("delegate", true) || m.contains("clGetPlatform", true)
    }

    /** Reload the engine on CPU; used when the GPU delegate only fails at inference time. */
    fun forceCpu(onProgress: (String) -> Unit) {
        release()
        onProgress("GPU nicht nutzbar – Gemma läuft jetzt auf der CPU …")
        open(Backend.CPU(threadCount = Config.llmThreads))
        usingGpu = false
    }

    /** Name of the bundle the engine currently holds, for the status line. */
    fun bundleName(): String = openedBundle ?: "–"

    private var openedBundle: String? = null

    private fun modelPathFor(backend: Backend): File =
        store.llmModelFor(backend is Backend.GPU)

    private fun open(backend: Backend) {
        val e = Engine(
            EngineConfig(
                modelPath = modelPathFor(backend).absolutePath,
                backend = backend,
                maxNumTokens = Config.llmMaxTokensOverride,
                cacheDir = store.cacheDir.absolutePath,
            )
        )
        e.initialize()
        engine = e
        conversation = e.createConversation(newConversationConfig())
        openedBundle = modelPathFor(backend).name
        Log.i(
            TAG,
            "engine up, backend=${backend.name}, bundle=$openedBundle, " +
                "maxTokens=${Config.llmMaxTokensOverride ?: "bundle-default"}, thinking=${Config.llmThinking}",
        )
    }

    private fun newConversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(systemPrompt),
        samplerConfig = SamplerConfig(
            topK = Config.TOP_K,
            topP = Config.TOP_P,
            temperature = Config.TEMPERATURE,
        ),
        thinkingConfig = ThinkingConfig(
            enableThinking = Config.llmThinking,
            thinkingTokenBudget = Config.llmThinkingBudget,
        ),
    )

    /**
     * The blocking call, mirroring how an older pet app of the owner drove the engine: one conversation per
     * question, `sendMessage` instead of the streaming callback. Used by the probe to tell apart
     * a broken bundle from a broken streaming path.
     */
    fun askSync(text: String): String {
        val e = engine ?: return "(keine Engine)"
        val conv = e.createConversation(newConversationConfig())
        return conv.use { it.sendMessage(text).toString().trim() }
    }

    /** Completed inferences since the engine was created (Upstream-Bug #2062 Zähler). */
    var inferences = 0
        private set

    /**
     * google-ai-edge/LiteRT-LM#2062: after several inferences the native state of one engine
     * corrupts (Tensor-Mask / free corruption) and the next send aborts the process. The
     * older pet project recycles its engine every few calls; here the engine is rebuilt only
     * when a turn failed or after [RECYCLE_AFTER] turns, and never while one is running.
     */
    fun recycle(preferGpu: Boolean, onProgress: (String) -> Unit) {
        val was = inferences
        release()
        runCatching {
            init(preferGpu, onProgress)
            inferences = 0
            Log.i(TAG, "Engine recycelt (nach $was Inferenzen)")
        }.onFailure { Log.e(TAG, "Recycling fehlgeschlagen", it) }
    }

    /** Drop the multi-turn context and start a fresh conversation, optionally with a new persona. */
    fun resetConversation(prompt: String? = null) {
        if (prompt != null) systemPrompt = prompt
        val e = engine ?: return
        runCatching { conversation?.close() }
        conversation = e.createConversation(newConversationConfig())
    }

    fun ask(
        text: String,
        onToken: (String) -> Unit,
        onDone: (Stats) -> Unit,
        onError: (String) -> Unit,
    ) {
        val conv = conversation ?: run { onError("LLM ist nicht geladen"); return }
        busy = true
        Log.i(TAG, "ask: ${text.take(60)} (gpu=$usingGpu)")

        val t0 = System.nanoTime()
        val firstTokenAt = AtomicLong(0L)
        val tokenCount = AtomicInteger(0)
        val acc = StringBuilder()

        runCatching {
            conv.sendMessageAsync(text, object : MessageCallback {
                override fun onMessage(message: Message) {
                    if (!busy) return
                    val chunk = message.textPart()
                    if (chunk.isEmpty()) return
                    firstTokenAt.compareAndSet(0L, System.nanoTime())
                    // Robust for both delta and cumulative streaming callbacks.
                    val delta = if (acc.isNotEmpty() && chunk.startsWith(acc.toString())) {
                        val d = chunk.substring(acc.length)
                        acc.setLength(0)
                        acc.append(chunk)
                        d
                    } else {
                        acc.append(chunk)
                        chunk
                    }
                    tokenCount.incrementAndGet()
                    if (delta.isNotEmpty()) onToken(delta)
                }

                override fun onDone() {
                    inferences++
                    Log.i(TAG, "onDone (busy=$busy, tokens=${tokenCount.get()})")
                    if (!busy) return
                    busy = false
                    val now = System.nanoTime()
                    val first = if (firstTokenAt.get() > 0) firstTokenAt.get() else now
                    val totalMs = (now - t0) / 1_000_000
                    val genMs = (now - first) / 1_000_000
                    val n = tokenCount.get()
                    onDone(
                        Stats(
                            ttftMs = (first - t0) / 1_000_000,
                            totalMs = totalMs,
                            tokens = n,
                            tokensPerSec = if (genMs > 0) n * 1000.0 / genMs else 0.0,
                        )
                    )
                }

                override fun onError(t: Throwable) {
                    Log.e(TAG, "onError from LiteRT-LM", t)
                    busy = false
                    onError(t.message ?: t.javaClass.simpleName)
                }
            })
        }.onFailure {
            Log.e(TAG, "sendMessageAsync threw", it)
            busy = false
            onError(it.message ?: "LLM-Fehler")
        }
    }

    /** Stop generation right now (user interrupted us). */
    fun cancel() {
        Log.i(TAG, "cancel: generation aborted")
        busy = false
        runCatching { conversation?.cancelProcess() }
    }

    fun contextTokens(): Int = runCatching { conversation?.getTokenCount() ?: 0 }.getOrDefault(0)

    fun release() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
    }

    private fun Message.textPart(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString(separator = "") { it.text }

    companion object {
        private const val TAG = "Lumi.LLM"

        /** How many answers we accept before the idle refresh (the older pet project used 5). */
        const val RECYCLE_AFTER = 8
        // Runtime-adjustable copy lives in Config.llmRecycleAfter.
    }
}
