package com.lou.lumi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lou.lumi.BrainDownload
import com.lou.lumi.Lumi
import com.lou.lumi.R
import com.lou.lumi.brain.AnswerReport
import com.lou.lumi.brain.BrainState
import com.lou.lumi.brain.ReportGuard
import com.lou.lumi.brain.Understanding
import com.lou.lumi.pet.CareReminder
import com.lou.lumi.pet.Food
import com.lou.lumi.pet.PetEngine
import com.lou.lumi.pet.PetState
import com.lou.lumi.pet.Reaction
import com.lou.lumi.pet.Stage
import com.lou.lumi.voice.Config
import com.lou.lumi.voice.ModelDownloader
import com.lou.lumi.voice.ModelImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One screen: Lumi in the middle, its needs below, five actions at the bottom. Everything else
 * (memories, brain import, voice, time-lapse) lives in the ⋯ menu, so the pet stays the focus.
 */
class MainActivity : AppCompatActivity() {

    private val keeper get() = Lumi.keeper
    private val brain get() = Lumi.brain
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val sfx = SoundFx()
    private val importer by lazy { ModelImporter(applicationContext) }
    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private lateinit var pet: PetView
    private lateinit var needs: NeedsView
    private lateinit var nameView: TextView
    private lateinit var subtitle: TextView
    private lateinit var brainStatus: TextView
    private lateinit var heard: TextView
    private lateinit var caption: TextView
    private lateinit var reportChip: TextView
    private lateinit var inputRow: LinearLayout
    private lateinit var input: EditText
    private lateinit var btnTalk: ImageButton
    private lateinit var talkLabel: TextView
    private lateinit var btnSleep: ImageButton
    private lateinit var sleepLabel: TextView

    private var captionJob: Job? = null
    private var importing = false
    private var greetPending: String? = null
    private var probeSay: String? = null
    private var probeSpeak: String? = null
    /** Lumi's last generated answer and what the human said before it – what „Antwort melden“ reports. */
    private var lastAnswer = ""
    private var lastHeard = ""
    private val reportPrefs by lazy { getSharedPreferences("reports", MODE_PRIVATE) }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runImport("ZIP-Archiv") { it.importZip(uri, ::onImportProgress) }
    }
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) runImport("Modellordner") { it.importTree(uri, ::onImportProgress) }
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) toggleTalk() else showCaption("Ohne Mikrofon kann ich dich nicht hören. Du kannst mir aber schreiben.")
    }
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lumi.init(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        pet = findViewById(R.id.pet)
        needs = findViewById(R.id.needs)
        nameView = findViewById(R.id.name)
        subtitle = findViewById(R.id.subtitle)
        brainStatus = findViewById(R.id.brainStatus)
        heard = findViewById(R.id.heard)
        caption = findViewById(R.id.caption)
        reportChip = findViewById(R.id.reportChip)
        inputRow = findViewById(R.id.inputRow)
        input = findViewById(R.id.input)
        btnTalk = findViewById(R.id.btnTalk)
        talkLabel = findViewById(R.id.talkLabel)
        btnSleep = findViewById(R.id.btnSleep)
        sleepLabel = findViewById(R.id.sleepLabel)

        val overlay = findViewById<View>(R.id.overlay)
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        restoreSettings()
        brain.guard = ReportGuard(loadReports().map { it.answer })
        wireButtons()
        wirePet()
        observe()
        startTicker()

        val s = keeper.state.value
        val away = System.currentTimeMillis() - s.lastSeenAt
        if (!s.isEgg && away > 3 * 3_600_000L) {
            greetPending = "Dein Mensch ist gerade nach ${away / 3_600_000L} Stunden zurückgekommen. Begrüße ihn."
        }
        handleProbe(intent)
        if (brain.modelsPresent()) brain.load()
        else showCaption(if (s.isEgg) "Tipp das Ei an, um es zu wärmen." else "Ich kann noch nicht sprechen – im Menü ⋯ → Gehirn kann ich es mir holen.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleProbe(intent)
    }

    override fun onResume() {
        super.onResume()
        CareReminder.cancel(this)
        keeper.tick()
    }

    override fun onStop() {
        super.onStop()
        brain.endConversation()
        keeper.markSeen()
        keeper.save(force = true)
        CareReminder.schedule(this, keeper.state.value)
    }

    override fun onDestroy() {
        scope.cancel()
        if (isFinishing) brain.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ wiring

    private fun wireButtons() {
        findViewById<ImageButton>(R.id.btnFeed).setOnClickListener { v ->
            if (!checkAwakeAndHatched()) return@setOnClickListener
            PopupMenu(this, v).apply {
                menu.add(0, 0, 0, "Leuchtbeere · macht satt")
                menu.add(0, 1, 1, "Sternkeks · macht froh")
                setOnMenuItemClickListener { item -> doFeed(if (item.itemId == 0) Food.BERRY else Food.COOKIE); true }
            }.show()
        }
        findViewById<ImageButton>(R.id.btnPlay).setOnClickListener { doPlay() }
        btnTalk.setOnClickListener { toggleTalk() }
        btnSleep.setOnClickListener { if (keeper.state.value.asleep) doWake() else doSleep() }
        findViewById<ImageButton>(R.id.btnChat).setOnClickListener { toggleInput() }
        findViewById<ImageButton>(R.id.btnSend).setOnClickListener { sendTyped() }
        input.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { sendTyped(); true } else false
        }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu() }
        reportChip.setOnClickListener { reportAnswer() }
        caption.setOnLongClickListener { if (lastAnswer.isNotBlank() && reportChip.alpha > 0f) { reportAnswer(); true } else false }
    }

    private fun wirePet() {
        pet.listener = object : PetView.Listener {
            override fun onPoke() {
                val o = keeper.apply { PetEngine.poke(it) }
                if (o.reaction == Reaction.GIGGLE) sfx.play(SoundFx.Sfx.GIGGLE)
            }
            override fun onStroke() {
                val o = keeper.apply { PetEngine.pet(it) }
                if (o.reaction == Reaction.PURR) sfx.play(SoundFx.Sfx.PURR)
            }
            override fun onEggTap() {
                keeper.apply { PetEngine.tapEgg(it, System.currentTimeMillis()) }
                sfx.play(SoundFx.Sfx.CHIRP)
            }
            override fun onFoodEaten(food: Food) {
                keeper.apply { PetEngine.feed(it, food) }
                sfx.play(SoundFx.Sfx.MUNCH)
                if (Math.random() < 0.5) brain.remark("Du hast gerade eine ${food.label} gegessen.")
            }
            override fun onFireflyCaught(total: Int) = sfx.play(SoundFx.Sfx.PLING)
            override fun onGameEnd(caught: Int) {
                keeper.apply { PetEngine.played(it, caught) }
                showCaption(if (caught == 0) "Keins erwischt … nochmal?" else "$caught Glühwürmchen gefangen!")
                brain.remark("Ihr habt gerade Glühwürmchen fangen gespielt, dein Mensch hat $caught gefangen.", force = true)
            }
        }
    }

    private fun observe() {
        scope.launch {
            BrainDownload.progress.collect { p ->
                if (p == null || !BrainDownload.running.value) return@collect
                brainStatus.text = "●  Gehirn lädt … %d %% · %.1f / %.1f GB · %.0f MB/s".format(
                    (p.fraction * 100).toInt(), p.done / 1e9, p.total / 1e9, p.bytesPerSec / 1e6)
                brainStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            }
        }
        scope.launch {
            BrainDownload.result.collect { r ->
                when (r) {
                    null -> {}
                    ModelDownloader.Result.Done -> {
                        BrainDownload.result.value = null
                        brain.refreshModels()
                        showStatus()
                        if (brain.modelsPresent()) {
                            showCaption("Mein Gehirn ist da! Gleich kann ich reden.")
                            brain.load()
                        }
                    }
                    ModelDownloader.Result.Cancelled -> {
                        BrainDownload.result.value = null
                        showStatus()
                        showCaption("Download pausiert – er geht später an derselben Stelle weiter.")
                    }
                    is ModelDownloader.Result.Failed -> {
                        BrainDownload.result.value = null
                        showStatus()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Download unterbrochen")
                            .setMessage(r.message + "\n\nSchon geladene Teile bleiben erhalten.")
                            .setPositiveButton("Nochmal") { _, _ -> startDownload() }
                            .setNegativeButton("Später", null)
                            .show()
                    }
                }
            }
        }
        scope.launch { keeper.state.collect { render(it) } }
        scope.launch { keeper.reactions.collect { onReaction(it) } }
        scope.launch {
            keeper.spokenActions.collect { a ->
                when (a) {
                    Understanding.Action.FEED_BERRY -> doFeed(Food.BERRY, spoken = true)
                    Understanding.Action.FEED_COOKIE -> doFeed(Food.COOKIE, spoken = true)
                    Understanding.Action.PLAY -> doPlay(spoken = true)
                    Understanding.Action.SLEEP -> doSleep(spoken = true)
                    Understanding.Action.PET -> { keeper.apply { PetEngine.pet(it) }; sfx.play(SoundFx.Sfx.PURR) }
                    Understanding.Action.WAKE -> doWake()
                }
            }
        }
        scope.launch {
            brain.state.collect { s ->
                pet.brain = s
                val talking = brain.isTalking
                btnTalk.setBackgroundResource(if (talking) R.drawable.bg_talk_active else R.drawable.bg_talk)
                btnTalk.setImageResource(if (talking) R.drawable.ic_stop else R.drawable.ic_mic)
                talkLabel.text = if (talking) "Fertig" else "Reden"
                if (s == BrainState.READY) {
                    greetPending?.let { greetPending = null; brain.remark(it, force = true) }
                    probeSay?.let { probeSay = null; brain.say(it) }
                    probeSpeak?.let { probeSpeak = null; brain.speakIntoPipeline(it) }
                }
                showStatus()
                if (s == BrainState.SPEAKING || s == BrainState.THINKING) captionJob?.cancel()
                if (s == BrainState.LISTENING || s == BrainState.READY) fadeCaptionLater()
            }
        }
        // Keep the screen on only while it matters (open conversation, Lumi thinking/speaking, brain download);
        // otherwise the normal screen timeout applies – the flag used to be set permanently and drained the battery.
        scope.launch {
            combine(brain.state, BrainDownload.running) { s, downloading ->
                downloading || s == BrainState.LISTENING || s == BrainState.HEARING ||
                    s == BrainState.THINKING || s == BrainState.SPEAKING
            }.distinctUntilChanged().collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        scope.launch { brain.status.collect { showStatus() } }
        scope.launch { brain.micLevel.collect { pet.micLevel = it } }
        scope.launch { brain.speakingLevel.collect { pet.speakLevel = it } }
        scope.launch { brain.emotion.collect { e -> e?.let { pet.flash(it.first, 5f) } } }
        scope.launch {
            brain.heard.collect { t ->
                if (t.isBlank()) return@collect
                lastHeard = t
                heard.text = "„$t“"
                heard.animate().alpha(1f).setDuration(200).start()
            }
        }
        scope.launch {
            brain.answer.collect { t ->
                if (t.isBlank()) return@collect
                captionJob?.cancel()
                lastAnswer = t
                caption.text = t
                caption.animate().alpha(1f).setDuration(180).start()
                showReportChip(true)
            }
        }
    }

    /** The chip under the name: what Lumi is doing right now in a conversation, otherwise the brain status. */
    private fun showStatus() {
        if (importing || BrainDownload.running.value) return
        val text = when (brain.state.value) {
            BrainState.LISTENING -> "hört zu …"
            BrainState.HEARING -> "versteht …"
            BrainState.THINKING -> "denkt nach …"
            BrainState.SPEAKING -> "spricht …"
            else -> brain.status.value
        }
        brainStatus.text = "●  $text"
        brainStatus.setTextColor(statusColor())
    }

    private fun statusColor(): Int = ContextCompat.getColor(this, when (brain.state.value) {
        BrainState.LISTENING, BrainState.HEARING, BrainState.SPEAKING, BrainState.THINKING -> R.color.accent
        else -> R.color.fg_dim
    })

    private fun startTicker() {
        scope.launch {
            while (isActive) {
                delay(1000)
                keeper.tick()            // the state flow re-renders (lastUpdate always changes)
            }
        }
    }

    // ------------------------------------------------------------------ render

    private var lastStage: Stage? = null

    private fun render(s: PetState) {
        val now = System.currentTimeMillis()
        val stage = PetEngine.stage(s, now)
        val mood = PetEngine.mood(s)
        pet.stage = stage
        pet.mood = mood
        pet.asleep = s.asleep
        pet.hue = s.hue
        pet.eggProgress = (s.eggWarmth / PetEngine.EGG_TAPS_TO_HATCH).toFloat().coerceIn(0f, 1f)
            .coerceAtLeast(((now - s.bornAt).toFloat() / PetEngine.EGG_AUTO_HATCH_MS).coerceIn(0f, 1f) * 0.8f)
        nameView.text = s.name
        subtitle.text = if (s.isEgg) "noch im Ei · bald geht's los"
        else "${stage.label} · ${PetEngine.ageLabel(s, now)} · ${if (s.asleep) "schläft" else mood.label}"
        needs.setNeeds(s.satiety, s.energy, s.cheer, s.bond)
        btnSleep.setImageResource(if (s.asleep) R.drawable.ic_wake else R.drawable.ic_sleep)
        sleepLabel.text = if (s.asleep) "Wecken" else "Licht aus"
        pet.contentDescription = "${s.name}, ${stage.label}, ${if (s.asleep) "schläft" else mood.label}"
        if (lastStage != null && lastStage != stage && stage != Stage.EGG && lastStage != Stage.EGG) {
            showCaption("${s.name} ist gewachsen: jetzt ${stage.label}!")
            brain.forgetConversation()
            brain.remark("Du bist gerade gewachsen und jetzt ${stage.label}. Freu dich!", force = true)
        }
        lastStage = stage
    }

    private fun onReaction(r: Reaction) {
        pet.react(r)
        when (r) {
            Reaction.HATCHED -> {
                sfx.play(SoundFx.Sfx.HATCH)
                scope.launch { delay(1600); askName() }
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            Reaction.EGG_WOBBLE -> {}
            Reaction.EVOLVED -> sfx.play(SoundFx.Sfx.HATCH)
            Reaction.FELL_ASLEEP -> sfx.play(SoundFx.Sfx.SLEEPY)
            Reaction.FULL, Reaction.REFUSED_ASLEEP, Reaction.TOO_TIRED -> sfx.play(SoundFx.Sfx.NOPE)
            Reaction.WOKE_UP, Reaction.GRUMPY_WAKE -> sfx.play(SoundFx.Sfx.CHIRP)
            else -> {}
        }
    }

    // ------------------------------------------------------------------ actions

    private fun checkAwakeAndHatched(): Boolean {
        val s = keeper.state.value
        if (s.isEgg) { showCaption("Noch im Ei – tipp es an, damit es warm wird."); return false }
        if (s.asleep) { pet.react(Reaction.REFUSED_ASLEEP); sfx.play(SoundFx.Sfx.NOPE); showCaption("Pssst … ${s.name} schläft."); return false }
        return true
    }

    private fun doFeed(food: Food, spoken: Boolean = false) {
        if (!checkAwakeAndHatched()) return
        val probe = PetEngine.feed(keeper.state.value, food)
        if (probe.reaction == Reaction.FULL) {
            keeper.apply { PetEngine.feed(it, food) }
            if (!spoken) showCaption("Pappsatt! Vielleicht später.")
            return
        }
        pet.feed(food)
    }

    private fun doPlay(spoken: Boolean = false) {
        if (pet.gameRunning) return
        if (!checkAwakeAndHatched()) return
        when (PetEngine.canPlay(keeper.state.value)) {
            Reaction.TOO_TIRED -> { pet.react(Reaction.TOO_TIRED); sfx.play(SoundFx.Sfx.NOPE); if (!spoken) showCaption("Zu müde zum Spielen … *gähn*") }
            Reaction.PLAYED -> {
                pet.startGame(15f)
                sfx.play(SoundFx.Sfx.GIGGLE)
                showCaption("Fang die Glühwürmchen! Tipp sie an.")
            }
            else -> {}
        }
    }

    private fun doSleep(spoken: Boolean = false) {
        val s = keeper.state.value
        if (s.isEgg || s.asleep) return
        brain.hush()
        brain.endConversation()
        pet.stopGame()
        keeper.apply { PetEngine.sleep(it) }
        if (!spoken) showCaption("Gute Nacht, ${s.name}.")
    }

    private fun doWake() {
        val o = keeper.apply { PetEngine.wake(it) }
        if (o.reaction == Reaction.GRUMPY_WAKE) showCaption("Hmpf … noch so müde.")
    }

    private fun toggleTalk() {
        if (brain.isTalking) { brain.endConversation(); return }
        if (!brain.modelsPresent()) { showBrainImport(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO); return
        }
        if (keeper.state.value.isEgg) { showCaption("Aus dem Ei kann noch niemand antworten …"); return }
        captionJob?.cancel()
        brain.startConversation()
        // the state flow may not change (WAKING) – reflect the button right away
        btnTalk.setBackgroundResource(R.drawable.bg_talk_active)
        btnTalk.setImageResource(R.drawable.ic_stop)
        talkLabel.text = "Fertig"
        if (!brain.isLoaded) showCaption("${keeper.state.value.name} wacht auf … gleich kannst du reden.")
    }

    private fun toggleInput() {
        val show = inputRow.visibility != View.VISIBLE
        inputRow.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) { input.requestFocus(); imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        else imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        if (keeper.state.value.isEgg) { showCaption("Aus dem Ei kann noch niemand antworten …"); return }
        if (!brain.isLoaded) {
            showCaption(if (brain.modelsPresent()) "Einen Moment, ich wache noch auf …" else "Ich kann noch nicht denken – Menü ⋯ → Gehirn importieren.")
            if (brain.modelsPresent()) probeSay = t
            return
        }
        brain.say(t)
    }

    private fun showCaption(text: String, seconds: Long = 5) {
        captionJob?.cancel()
        caption.text = text
        caption.animate().alpha(1f).setDuration(180).start()
        heard.animate().alpha(0f).setDuration(300).start()
        showReportChip(false)   // app texts, not AI answers
        captionJob = scope.launch {
            delay(seconds * 1000)
            caption.animate().alpha(0f).setDuration(700).start()
        }
    }

    private fun fadeCaptionLater() {
        captionJob?.cancel()
        captionJob = scope.launch {
            delay(7000)
            caption.animate().alpha(0f).setDuration(900).start()
            heard.animate().alpha(0f).setDuration(900).start()
            // the report button stays a little longer than the answer, so it can still be reached
            delay(5000)
            showReportChip(false)
        }
    }

    private fun showReportChip(show: Boolean) {
        reportChip.animate().cancel()
        if (show) {
            reportChip.visibility = View.VISIBLE
            reportChip.animate().alpha(1f).setDuration(180).start()
        } else {
            reportChip.animate().alpha(0f).setDuration(400).withEndAction { reportChip.visibility = View.INVISIBLE }.start()
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun askName() {
        val s = keeper.state.value
        val field = EditText(this).apply {
            setText(s.name); setSelection(text.length)
            setTextColor(ContextCompat.getColor(context, R.color.fg))
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Hallo! Wie soll ich heißen?")
            .setView(field)
            .setPositiveButton("So heiße ich") { _, _ ->
                val n = field.text.toString().trim().take(16).ifBlank { "Lumi" }
                keeper.change { it.copy(name = n) }
                brain.forgetConversation()
                brain.remark("Du bist gerade aus dem Ei geschlüpft, das ist dein allererster Moment. Dein Mensch hat dich $n genannt.", force = true)
                if (!brain.modelsPresent() && !BrainDownload.running.value) scope.launch { delay(1200); showBrainImport() }
            }
            .setCancelable(false)
            .show()
    }

    private fun showMenu() {
        val s = keeper.state.value
        val items = arrayOf(
            "Über ${s.name}",
            "Erinnerungen (${s.memories.size})",
            if (BrainDownload.running.value) "Gehirn lädt …" else "Gehirn (Modelle)",
            "Stimme",
            "Stimmlage: ${pitchLabel()}",
            "Zeitraffer: ${if (s.growth > 1.0) "an (1 Tag = 1 Stunde)" else "aus"}",
            "Geräusche: ${if (sfx.enabled) "an" else "aus"}",
            "Erinnerungs-Mitteilungen: ${if (CareReminder.enabled(this)) "an" else "aus"}",
            "Gemeldete Antworten (${loadReports().size})",
            "Lizenzen & Quellcode",
            "Neues Ei …",
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showAbout()
                    1 -> showMemories()
                    2 -> showBrainImport()
                    3 -> showVoices()
                    4 -> showPitch()
                    5 -> keeper.change { it.copy(growth = if (it.growth > 1.0) 1.0 else 24.0).rebase(it) }
                    6 -> { sfx.enabled = !sfx.enabled; settings.edit().putBoolean("sfx", sfx.enabled).apply() }
                    7 -> settings.edit().putBoolean(CareReminder.KEY_ENABLED, !CareReminder.enabled(this)).apply()
                    8 -> showReports()
                    9 -> showLicenses()
                    10 -> confirmNewEgg()
                }
            }
            .show()
    }

    /** Switching the time-lapse must not make the pet jump in age: keep the age it has right now. */
    private fun PetState.rebase(old: PetState): PetState {
        if (old.isEgg) return this
        val now = System.currentTimeMillis()
        val ageMs = PetEngine.ageHours(old, now) * PetEngine.HOUR
        return copy(hatchedAt = now - (ageMs / growth).toLong())
    }

    private fun showAbout() {
        val s = keeper.state.value
        val now = System.currentTimeMillis()
        val msg = buildString {
            append("${PetEngine.stage(s, now).label}, ${PetEngine.ageLabel(s, now)} alt\n")
            append("Charakter: ${PetEngine.traits(s).joinToString(", ")}\n\n")
            append("Hunger ${s.satiety.toInt()} % · Energie ${s.energy.toInt()} % · Laune ${s.cheer.toInt()} % · Nähe ${s.bond.toInt()} %\n")
            append("Gefüttert ${s.feeds}× · gespielt ${s.plays}× · gestreichelt ${s.pets}× · geredet ${s.talks}×\n\n")
            append("Gehirn: ${brain.status.value}\n")
            if (brain.metrics.value.isNotBlank()) append(brain.metrics.value + "\n")
            append("\nAlles läuft offline auf dem Handy: Whisper hört, Gemma 4 denkt, Piper spricht.")
        }
        AlertDialog.Builder(this).setTitle(s.name).setMessage(msg).setPositiveButton("Schön", null).show()
    }

    // ------------------------------------------------------------------ reporting answers (Play policy on AI content)

    private fun loadReports(): List<AnswerReport> = AnswerReport.decodeAll(reportPrefs.getString("list", null))

    private fun saveReports(list: List<AnswerReport>, fresh: Boolean) {
        reportPrefs.edit().putString("list", AnswerReport.encodeAll(list)).apply()
        if (fresh) brain.onAnswerReported(list.map { it.answer }) else brain.guard = ReportGuard(list.map { it.answer })
    }

    /** Report Lumi's last answer: pick a reason, optional note – stays on the phone unless the user passes it on. */
    private fun reportAnswer() {
        val answer = lastAnswer.trim()
        if (answer.isBlank()) { showCaption("Es gibt noch keine Antwort von ${keeper.state.value.name}, die du melden könntest."); return }
        brain.hush()
        val reasons = AnswerReport.Reason.entries
        var picked = -1
        val note = EditText(this).apply {
            hint = "Anmerkung (freiwillig)"
            setTextColor(ContextCompat.getColor(context, R.color.fg))
            setHintTextColor(ContextCompat.getColor(context, R.color.fg_dim))
            setPadding(48, 24, 48, 24)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Antwort melden")
            .setSingleChoiceItems(reasons.map { it.label }.toTypedArray(), -1) { d, i ->
                picked = i
                (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
            .setView(note)
            .setPositiveButton("Melden") { _, _ ->
                val r = AnswerReport(System.currentTimeMillis(), reasons[picked], answer, lastHeard.trim(), note.text.toString().trim())
                saveReports(listOf(r) + loadReports(), fresh = true)
                lastAnswer = ""
                caption.text = ""
                showReportChip(false)
                afterReport(r)
            }
            .setNegativeButton("Abbrechen", null)
            .create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false }
        dialog.show()
    }

    private fun afterReport(r: AnswerReport) {
        val msg = "Danke! ${keeper.state.value.name} sagt das nicht noch einmal und fängt das Gespräch neu an. " +
            "Die Meldung bleibt auf deinem Handy (⋯ → Gemeldete Antworten)."
        AlertDialog.Builder(this)
            .setTitle("Gemeldet")
            .setMessage(if (REPORT_EMAIL.isBlank()) msg else "$msg\n\nDu kannst sie auch an den Entwickler schicken, damit Lumi besser wird.")
            .setPositiveButton("OK", null)
            .apply { if (REPORT_EMAIL.isNotBlank()) setNegativeButton("Per E-Mail schicken") { _, _ -> mailReports(listOf(r)) } }
            .show()
    }

    private fun showReports() {
        val list = loadReports()
        val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        val msg = if (list.isEmpty()) "Noch keine. Wenn dir eine Antwort von ${keeper.state.value.name} nicht gefällt, tipp unter dem Untertitel auf „⚑ Antwort melden“."
        else list.joinToString("\n\n") { "${df.format(java.util.Date(it.at))} · ${it.reason.label}\n„${it.answer}“" }
        AlertDialog.Builder(this)
            .setTitle("Gemeldete Antworten")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .apply {
                if (list.isNotEmpty() && REPORT_EMAIL.isNotBlank()) setNeutralButton("Per E-Mail schicken") { _, _ -> mailReports(list) }
                if (list.isNotEmpty()) setNegativeButton("Liste leeren") { _, _ -> saveReports(emptyList(), fresh = false) }
            }
            .show()
    }

    /** Hands the reports to the user's mail app – Lumi itself sends nothing. */
    private fun mailReports(list: List<AnswerReport>) {
        val body = "Gemeldete Antworten von Lumi ${versionName()}:\n\n" + list.joinToString("\n") { it.describe() }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(REPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Lumi: gemeldete Antwort")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        runCatching { startActivity(i) }.onFailure { showCaption("Keine E-Mail-App gefunden.") }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun showMemories() {
        val s = keeper.state.value
        val msg = if (s.memories.isEmpty()) "Noch nichts. Erzähl mir von dir – zum Beispiel „Ich heiße …“, „Ich mag …“ oder „Merk dir, dass …“."
        else s.memories.joinToString("\n") { "• $it" }
        AlertDialog.Builder(this)
            .setTitle("Was ${s.name} über dich weiß")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .apply {
                if (s.memories.isNotEmpty()) setNegativeButton("Alles vergessen") { _, _ ->
                    keeper.change { it.copy(memories = emptyList(), userName = "") }
                    brain.forgetConversation()
                }
            }
            .show()
    }

    private fun showBrainImport() {
        val dl = ModelDownloader(applicationContext)
        if (BrainDownload.running.value) {
            val p = BrainDownload.progress.value
            AlertDialog.Builder(this)
                .setTitle("Lumis Gehirn lädt")
                .setMessage(if (p == null) "Verbinde …" else "%d %% · %.1f von %.1f GB\n\nDu kannst Lumi währenddessen weiter pflegen.".format((p.fraction * 100).toInt(), p.done / 1e9, p.total / 1e9))
                .setPositiveButton("Weiter laden", null)
                .setNegativeButton("Pausieren") { _, _ -> BrainDownload.cancel() }
                .show()
            return
        }
        if (brain.modelsPresent()) {
            AlertDialog.Builder(this)
                .setTitle("Lumis Gehirn")
                .setMessage("Alles da ✓\n\n" + brain.modelOverview())
                .setPositiveButton("OK", null)
                .setNeutralButton("Ordner / ZIP …") { _, _ -> showManualImport() }
                .show()
            return
        }
        val gb = runCatching { dl.bytesMissing() / 1e9 }.getOrDefault(3.0)
        val totalGb = runCatching { dl.manifest().sumOf { it.size } / 1e9 }.getOrDefault(gb)
        // after an interrupted download only the rest is missing – say so instead of a puzzling small total
        val rest = if (gb < 0.1) "%.0f MB".format(maxOf(1.0, gb * 1000)) else "%.1f GB".format(gb)
        val size = if (gb < totalGb - 0.05) "zusammen %.1f GB, davon fehlen noch %s".format(totalGb, rest)
                   else "zusammen %.1f GB".format(gb)
        AlertDialog.Builder(this)
            .setTitle("Lumis Gehirn")
            .setMessage(
                "Damit ich hören, denken und sprechen kann, brauche ich einmalig mein Gehirn: " +
                    "Gemma 4 (Denken), Whisper (Hören) und eine Stimme – $size.\n\n" +
                    "Es kommt direkt von Hugging Face. Danach läuft alles offline auf deinem Handy – " +
                    "nichts von dem, was du mir sagst, verlässt es.\n\nWLAN empfohlen."
            )
            .setPositiveButton("Herunterladen") { _, _ -> startDownload() }
            .setNeutralButton("Ordner / ZIP …") { _, _ -> showManualImport() }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun startDownload() {
        val dl = ModelDownloader(applicationContext)
        val go = {
            BrainDownload.start(applicationContext)
            showCaption("Ich hole mein Gehirn … das dauert ein paar Minuten.")
        }
        if (dl.isMetered()) {
            AlertDialog.Builder(this)
                .setTitle("Mobile Daten?")
                .setMessage("Du bist nicht im WLAN. Der Download ist %.1f GB groß.".format(dl.bytesMissing() / 1e9))
                .setPositiveButton("Trotzdem laden") { _, _ -> go() }
                .setNegativeButton("Lieber im WLAN", null)
                .show()
        } else go()
    }

    private fun showManualImport() {
        AlertDialog.Builder(this)
            .setTitle("Gehirn aus Dateien")
            .setMessage("Für Modelle, die schon auf dem Handy liegen (z. B. aus Gemma Voice):\n\n" + brain.modelOverview())
            .setPositiveButton("Ordner wählen") { _, _ -> pickTree.launch(null) }
            .setNeutralButton("ZIP wählen") { _, _ -> pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun runImport(label: String, work: (ModelImporter) -> ModelImporter.Outcome) {
        if (importing) return
        importing = true
        brainStatus.text = "●  $label wird importiert …"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { work(importer) } }
            importing = false
            brain.refreshModels()
            val o = result.getOrNull()
            val msg = if (o == null) "Fehler: ${result.exceptionOrNull()?.message}" else buildString {
                append("übernommen: ${o.copied.size} · übersprungen: ${o.skipped.size} · Fehler: ${o.failed.size}\n")
                o.failed.take(6).forEach { append("• $it\n") }
                o.notes.distinct().take(6).forEach { append("• $it\n") }
                append("\n"); append(brain.modelOverview())
            }
            AlertDialog.Builder(this@MainActivity).setTitle(label).setMessage(msg).setPositiveButton("OK", null).show()
            if (brain.modelsPresent()) brain.load()
            Log.i(TAG, "import $label: $o")
        }
    }

    private fun onImportProgress(p: ModelImporter.Progress) {
        runOnUiThread {
            brainStatus.text = if (p.totalBytes > 0) "●  ${p.phase}: %.0f / %.0f MB".format(p.doneBytes / 1e6, p.totalBytes / 1e6)
            else "●  ${p.phase} ${p.current}"
        }
    }

    private fun showVoices() {
        val voices = brain.voices()
        if (voices.isEmpty()) { showBrainImport(); return }
        val active = brain.activeVoiceId()
        val labels = voices.map { it.label + if (it.id == active) "  ✓" else "" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Stimme")
            .setItems(labels) { _, i ->
                val id = voices[i].id
                settings.edit().putString("voice_de", id).apply()
                scope.launch(Dispatchers.Default) {
                    brain.setVoice(id)
                    if (brain.isLoaded) brain.remark("Du hast gerade eine neue Stimme bekommen. Sag, wie du sie findest.", force = true)
                }
            }
            .show()
    }

    private fun pitchLabel() = when {
        brain.pitchOffset < 0.95f -> "tiefer"
        brain.pitchOffset > 1.05f -> "höher"
        else -> "passend zum Alter"
    }

    private fun showPitch() {
        val opts = arrayOf("tiefer", "passend zum Alter", "höher")
        val vals = floatArrayOf(0.9f, 1f, 1.12f)
        AlertDialog.Builder(this)
            .setTitle("Stimmlage")
            .setItems(opts) { _, i ->
                brain.pitchOffset = vals[i]
                settings.edit().putFloat("pitch", vals[i]).apply()
                if (brain.isLoaded) brain.remark("Deine Stimme klingt jetzt ${opts[i]}.", force = true)
            }
            .show()
    }

    /** GPL-3.0: everyone who gets the app must be able to find its licence and its source. */
    private fun showLicenses() {
        val notices = runCatching { assets.open("third_party_notices.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        val head = if (SOURCE_URL.isNotBlank()) "Quellcode: $SOURCE_URL\n\n" else "Quellcode: wird mit der ersten Store-Version auf GitHub veröffentlicht.\n\n"
        val text = TextView(this).apply {
            setText(head + notices)
            setTextColor(ContextCompat.getColor(context, R.color.fg))
            textSize = 12f
            setTextIsSelectable(true)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad)
        }
        // Cap the text height: with three buttons (or large fonts) Android stacks them vertically, and an
        // unbounded ScrollView pushed the stacked buttons off the dialog on the S23 (seen 2026-11-22).
        val scroll = object : android.widget.ScrollView(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val cap = (resources.displayMetrics.heightPixels * 0.55f).toInt()
                val size = MeasureSpec.getSize(heightSpec)
                val max = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) cap else minOf(cap, size)
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }.apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle("Lizenzen & Quellcode")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .setNeutralButton("Lizenztext") { _, _ -> showGplText() }
            .apply {
                if (SOURCE_URL.isNotBlank()) setNegativeButton("Quellcode") { _, _ ->
                    // the browser opens it – Lumi itself still makes no connection
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }
                }
            }
            .show()
    }

    private fun showGplText() {
        val gpl = runCatching { assets.open("LICENSE-GPL-3.0.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        val text = TextView(this).apply {
            setText(gpl)
            setTextColor(ContextCompat.getColor(context, R.color.fg))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("GNU GPL v3")
            .setView(android.widget.ScrollView(this).apply { addView(text) })
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmNewEgg() {
        val s = keeper.state.value
        AlertDialog.Builder(this)
            .setTitle("Neues Ei?")
            .setMessage("${s.name} und alle Erinnerungen werden gelöscht. Das lässt sich nicht rückgängig machen.")
            .setPositiveButton("Neues Ei") { _, _ ->
                brain.endConversation()
                keeper.newEgg()
                brain.forgetConversation()
                lastStage = null
                showCaption("Ein neues Ei! Tipp es an, damit es warm wird.")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun restoreSettings() {
        sfx.enabled = settings.getBoolean("sfx", true)
        brain.pitchOffset = settings.getFloat("pitch", 1f)
        settings.getString("voice_de", null)?.let { Config.ttsVoiceId["de"] = it }
    }

    // ------------------------------------------------------------------ probes (adb)

    /**
     * Test hooks, e.g.
     * `adb shell am start -n de.tamalumi.app/com.lou.lumi.ui.MainActivity --ez hatch true --es say "Hallo, ich heiße Lou"`
     */
    private fun handleProbe(i: Intent?) {
        val x = i?.extras ?: return
        val now = System.currentTimeMillis()
        if (x.getBoolean("hatch", false)) keeper.apply { PetEngine.tapEgg(it.copy(eggWarmth = 99.0), now) }
        if (x.containsKey("grow")) keeper.change { it.copy(growth = x.getInt("grow", 1).toDouble().coerceIn(1.0, 48.0)) }
        if (x.containsKey("age_hours")) keeper.change {
            if (it.isEgg) it else it.copy(hatchedAt = now - (x.getInt("age_hours", 0) * PetEngine.HOUR / it.growth).toLong())
        }
        x.getString("needs")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 4 }?.let { v ->
            keeper.change { it.copy(satiety = v[0], energy = v[1], cheer = v[2], bond = v[3]) }
        }
        x.getString("feed")?.let { doFeed(if (it == "cookie") Food.COOKIE else Food.BERRY) }
        if (x.getBoolean("play", false)) doPlay()
        if (x.getBoolean("sleep", false)) doSleep()
        if (x.getBoolean("wake", false)) doWake()
        x.getString("say")?.let { if (brain.isLoaded) brain.say(it) else probeSay = it }
        x.getString("speak")?.let { if (brain.isLoaded) brain.speakIntoPipeline(it) else probeSpeak = it }
        if (x.getBoolean("talk", false)) toggleTalk()
        x.getString("llm_bundle")?.let { Config.llmBundleHint = it }
        if (x.getBoolean("download", false)) BrainDownload.start(applicationContext)
        if (x.getBoolean("cpu", false)) Config.llmGpuWeights = false
        Log.i(TAG, "probe extras: ${x.keySet()}")
        i.replaceExtras(Bundle())
    }

    private companion object {
        const val TAG = "Lumi.UI"
        /** Public repository with the source of exactly this app (GPL-3.0). */
        const val SOURCE_URL = "https://github.com/nanocube-dev/tamalumi"
        /** Where users may e-mail reported answers (optional, their own mail app). Empty: reports stay on the phone. */
        const val REPORT_EMAIL = ""
    }
}

