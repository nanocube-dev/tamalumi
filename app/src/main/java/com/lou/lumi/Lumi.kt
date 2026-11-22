package com.lou.lumi

import android.content.Context
import com.lou.lumi.brain.PetBrain
import com.lou.lumi.pet.PetKeeper
import com.lou.lumi.voice.ModelDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide owners: the pet and its ~3 GB brain must survive the activity being recreated
 * (rotation is locked, but theme or display changes still recreate it).
 */
object Lumi {
    lateinit var keeper: PetKeeper
        private set
    lateinit var brain: PetBrain
        private set

    fun init(app: Context) {
        if (::keeper.isInitialized) return
        keeper = PetKeeper(app)
        brain = PetBrain(app, keeper)
    }
}

/** The one-time brain download, owned by the process so it keeps going while the activity is recreated. */
object BrainDownload {
    val running = MutableStateFlow(false)
    val progress = MutableStateFlow<ModelDownloader.Progress?>(null)
    val result = MutableStateFlow<ModelDownloader.Result?>(null)
    private val cancel = AtomicBoolean(false)

    fun start(app: Context) {
        if (running.value) return
        cancel.set(false)
        result.value = null
        progress.value = null
        running.value = true
        Thread {
            val r = runCatching { ModelDownloader(app).run(cancel) { progress.value = it } }
                .getOrElse { ModelDownloader.Result.Failed(it.message ?: it.javaClass.simpleName) }
            result.value = r
            running.value = false
        }.apply { name = "brain-download"; start() }
    }

    fun cancel() = cancel.set(true)
}
