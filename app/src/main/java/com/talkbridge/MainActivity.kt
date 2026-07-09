package com.talkbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1600 // 0.1s of audio

        // --- Tuning knobs ---
        const val MIN_CONF = 0.70         // whole-utterance confidence floor
        const val SINGLE_WORD_CONF = 0.90 // single words must be near-certain
        const val MERGE_GAP_MS = 1000L    // silence needed to end a sentence

        // --- Tier 1 intelligence ---
        const val SPEECH_THRESHOLD_MULT = 2.5f // voice = level above noiseFloor * this
        const val SPEECH_THRESHOLD_MIN = 0.045f // absolute minimum voice level
        const val SPEECH_HANGOVER_MS = 700L    // keep decoding this long after voice stops
        const val NOISE_FLOOR_ALPHA = 0.05f    // background noise adaptation speed
        const val LOCK_MIN_WORDS = 4           // words needed before locking language
        const val LOCK_MARGIN = 0.12           // confidence gap needed to lock
        const val STICKY_BONUS = 0.08          // score bonus for last speaker's language

        const val GITHUB_REPO = "davidhewitt2021-lgtm/talkbridge"
    }

    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var btnConversation: Button
    private lateinit var btnUpdate: Button
    private lateinit var flagView: TextView
    private lateinit var meter: LevelMeterView

    // Colours for the meter per language
    private val colorEn = 0xFF1E5AA8.toInt() // blue
    private val colorPt = 0xFFDA291C.toInt() // red
    private val colorIdle = 0xFF888888.toInt()

    @Volatile
    private var hasPendingUtterance = false
    private var shownFlag = "" // avoid redundant UI churn

    // Tier 1 state
    private val languageId by lazy { LanguageIdentification.getClient() }
    private var lastCommittedLang = "" // sticky prior: people rarely alternate every sentence

    @Volatile
    private var lockedLang: String? = null // set once one decoder is clearly winning

    private var modelEn: Model? = null
    private var modelPt: Model? = null

    private lateinit var enToPt: Translator
    private lateinit var ptToEn: Translator
    private var translatorsReady = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val running = AtomicBoolean(false)
    private val ttsSpeaking = AtomicBoolean(false)
    private var audioThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Utterance accumulation (UI thread only) ----
    // Both decodes of the same audio are buffered in full; language is decided
    // once, over the whole utterance, at commit time.
    private val enBuf = StringBuilder()
    private val ptBuf = StringBuilder()
    private var enConfSum = 0.0
    private var enWordCount = 0
    private var ptConfSum = 0.0
    private var ptWordCount = 0

    @Volatile
    private var lastVoiceActivityMs = 0L

    private val commitRunnable = object : Runnable {
        override fun run() {
            val idle = SystemClock.elapsedRealtime() - lastVoiceActivityMs
            if (idle < MERGE_GAP_MS) {
                // Still talking — check again once the gap could be complete
                mainHandler.postDelayed(this, MERGE_GAP_MS - idle)
            } else {
                commitPending()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the last run crashed, show the report instead of the normal UI
        // (and skip all initialisation so we can't crash the same way again)
        val crashFile = File(filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            val trace = crashFile.readText()
            crashFile.delete()
            showCrashReport(trace)
            return
        }

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        partialText = findViewById(R.id.partialText)
        transcript = findViewById(R.id.transcript)
        transcriptScroll = findViewById(R.id.transcriptScroll)
        btnConversation = findViewById(R.id.btnConversation)
        btnUpdate = findViewById(R.id.btnUpdate)
        flagView = findViewById(R.id.flagView)
        meter = findViewById(R.id.meter)

        btnConversation.isEnabled = false
        btnConversation.setOnClickListener { toggleConversation() }
        btnUpdate.setOnClickListener { checkForUpdate() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        initTts()
        initTranslators()
        initVoskModels()
    }

    // ---------- Crash reporting ----------

    /** Fullscreen selectable stack trace, auto-copied to the clipboard. */
    private fun showCrashReport(trace: String) {
        val tv = TextView(this).apply {
            text = trace
            textSize = 12f
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setPadding(32, 32, 32, 32)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        setContentView(scroll)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TalkBridge crash", trace))
        Toast.makeText(
            this,
            "Crash report copied to clipboard — paste it into the chat",
            Toast.LENGTH_LONG
        ).show()
    }

    // ---------- Initialisation ----------

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) toast(getString(R.string.tts_failed))
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { ttsSpeaking.set(true) }
            override fun onDone(utteranceId: String?) { ttsSpeaking.set(false) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { ttsSpeaking.set(false) }
        })
    }

    private fun initTranslators() {
        enToPt = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                .build()
        )
        ptToEn = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.PORTUGUESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
        val conditions = DownloadConditions.Builder().build()
        setStatus(getString(R.string.status_downloading_translation))
        enToPt.downloadModelIfNeeded(conditions)
            .continueWithTask { ptToEn.downloadModelIfNeeded(conditions) }
            .addOnSuccessListener { translatorsReady = true; maybeReady() }
            .addOnFailureListener { setStatus(getString(R.string.status_translation_failed)) }
    }

    private fun initVoskModels() {
        setStatus(getString(R.string.status_loading_speech))
        Thread {
            try {
                val enDir = ensureAssetDir("model-en")
                val ptDir = ensureAssetDir("model-pt")
                modelEn = Model(enDir.absolutePath)
                modelPt = Model(ptDir.absolutePath)
                runOnUiThread { maybeReady() }
            } catch (e: Exception) {
                runOnUiThread { setStatus(getString(R.string.status_speech_failed, e.message)) }
            }
        }.start()
    }

    private fun ensureAssetDir(name: String): File {
        val target = File(filesDir, name)
        val marker = File(target, ".complete")
        if (marker.exists()) return target
        target.deleteRecursively()
        copyAssetDir(name, target)
        marker.createNewFile()
        return target
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        } else {
            target.mkdirs()
            for (child in children) copyAssetDir("$assetPath/$child", File(target, child))
        }
    }

    private fun maybeReady() {
        if (modelEn != null && modelPt != null && translatorsReady) {
            btnConversation.isEnabled = true
            setStatus(getString(R.string.status_ready))
        }
    }

    // ---------- Conversation mode ----------

    private fun toggleConversation() {
        if (running.get()) stopConversation() else startConversation()
    }

    @SuppressLint("MissingPermission")
    private fun startConversation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        running.set(true)
        btnConversation.text = getString(R.string.btn_stop_conversation)
        setStatus(getString(R.string.status_conversing))
        audioThread = Thread { audioLoop() }.also { it.start() }
    }

    private fun stopConversation() {
        running.set(false)
        audioThread?.join(1500)
        audioThread = null
        mainHandler.removeCallbacks(commitRunnable)
        commitPending()
        partialText.text = ""
        meter.clear()
        showFlag("\uD83C\uDF99", colorIdle) // 🎙
        btnConversation.text = getString(R.string.btn_start_conversation)
        setStatus(getString(R.string.status_ready))
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        val recEn = Recognizer(modelEn, SAMPLE_RATE.toFloat()).apply { setWords(true) }
        val recPt = Recognizer(modelPt, SAMPLE_RATE.toFloat()).apply { setWords(true) }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK * 4)
        )
        val buffer = ShortArray(CHUNK)
        var wasSpeaking = false

        // VAD state: adaptive background-noise estimate + speech hangover
        var noiseFloor = 0.02f
        var inSpeech = false
        var lastVoicedMs = 0L

        try {
            recorder.startRecording()
            while (running.get()) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n <= 0) continue

                // Voice level for the meter (RMS of this 0.1s chunk)
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buffer[i].toDouble()
                    sum += s * s
                }
                val level = (Math.sqrt(sum / n) / 32768.0 * 12.0).toFloat()

                // Echo suppression: ignore the mic while the phone is talking
                if (ttsSpeaking.get()) {
                    wasSpeaking = true
                    meter.push(0f)
                    showFlag("\uD83D\uDD0A", colorIdle) // 🔊
                    continue
                }
                if (wasSpeaking) {
                    recEn.reset(); recPt.reset()
                    lockedLang = null
                    inSpeech = false
                    wasSpeaking = false
                }
                meter.push(level)

                // ---- VAD: is anyone actually talking? ----
                val threshold = maxOf(noiseFloor * SPEECH_THRESHOLD_MULT, SPEECH_THRESHOLD_MIN)
                val voiced = level > threshold
                val now = SystemClock.elapsedRealtime()
                if (voiced) {
                    lastVoicedMs = now
                    lastVoiceActivityMs = now
                    inSpeech = true
                } else {
                    // Adapt the noise estimate only from non-voice audio
                    noiseFloor += NOISE_FLOOR_ALPHA * (level - noiseFloor)
                }
                val active = inSpeech && (voiced || now - lastVoicedMs <= SPEECH_HANGOVER_MS)

                if (inSpeech && !active) {
                    // Utterance ended (VAD): force-finalize whatever the decoders hold
                    inSpeech = false
                    val enHyp = parseHyp(recEn.finalResult)
                    val ptHyp = parseHyp(recPt.finalResult)
                    recEn.reset(); recPt.reset()
                    lockedLang = null
                    if (enHyp.words > 0 || ptHyp.words > 0) {
                        runOnUiThread { bufferSegment(enHyp, ptHyp) }
                    } else {
                        runOnUiThread { partialText.text = "" }
                    }
                    continue
                }

                if (!active) {
                    // Silence: the decoders never see it — no hallucinations
                    // from background noise, and near-zero CPU while quiet
                    if (!hasPendingUtterance) showFlag("\uD83C\uDF99", colorIdle) // 🎙
                    continue
                }

                // ---- Active speech: feed the decoders ----
                // Once one language is clearly winning, stop paying for the loser
                val lock = lockedLang
                val enDone = if (lock != "pt") recEn.acceptWaveForm(buffer, n) else false
                val ptDone = if (lock != "en") recPt.acceptWaveForm(buffer, n) else false

                if (enDone || ptDone) {
                    val enHyp = parseHyp(if (lock != "pt") { if (enDone) recEn.result else recEn.finalResult } else null)
                    val ptHyp = parseHyp(if (lock != "en") { if (ptDone) recPt.result else recPt.finalResult } else null)
                    recEn.reset(); recPt.reset()
                    if (enHyp.words > 0 || ptHyp.words > 0) {
                        lastVoiceActivityMs = SystemClock.elapsedRealtime()
                        runOnUiThread { bufferSegment(enHyp, ptHyp) }
                    }
                } else {
                    val pEn = if (lock != "pt") JSONObject(recEn.partialResult).optString("partial") else ""
                    val pPt = if (lock != "en") JSONObject(recPt.partialResult).optString("partial") else ""
                    val partial = if (pPt.length > pEn.length) pPt else pEn
                    if (partial.isNotEmpty()) {
                        lastVoiceActivityMs = SystemClock.elapsedRealtime()
                        // Provisional language lead: longer decode usually fits better
                        if (lock == "pt" || pPt.length > pEn.length + 2) {
                            showFlag("\uD83C\uDDF5\uD83C\uDDF9", colorPt) // 🇵🇹
                        } else if (lock == "en" || pEn.length > pPt.length + 2) {
                            showFlag("\uD83C\uDDEC\uD83C\uDDE7", colorEn) // 🇬🇧
                        }
                    }
                    runOnUiThread { partialText.text = partial }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { toast(getString(R.string.mic_failed, e.message)) }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            recEn.close()
            recPt.close()
        }
    }

    private data class Hyp(val text: String, val avgConf: Double, val words: Int)

    private fun parseHyp(json: String?): Hyp {
        if (json == null) return Hyp("", 0.0, 0)
        val obj = JSONObject(json)
        val text = obj.optString("text").trim()
        val arr = obj.optJSONArray("result") ?: return Hyp(text, 0.0, 0)
        if (arr.length() == 0) return Hyp(text, 0.0, 0)
        var sum = 0.0
        for (i in 0 until arr.length()) sum += arr.getJSONObject(i).optDouble("conf", 0.0)
        return Hyp(text, sum / arr.length(), arr.length())
    }

    /**
     * Buffer BOTH decodes of this audio segment. Nothing is discarded here —
     * the noise gate and language decision happen once, over the whole
     * utterance, in commitPending(). (Gating per-segment was the v2 bug that
     * dropped the middle of sentences.)
     */
    private fun bufferSegment(en: Hyp, pt: Hyp) {
        if (en.text.isNotBlank()) {
            if (enBuf.isNotEmpty()) enBuf.append(' ')
            enBuf.append(en.text)
            enConfSum += en.avgConf * en.words
            enWordCount += en.words
        }
        if (pt.text.isNotBlank()) {
            if (ptBuf.isNotEmpty()) ptBuf.append(' ')
            ptBuf.append(pt.text)
            ptConfSum += pt.avgConf * pt.words
            ptWordCount += pt.words
        }
        partialText.text = ""
        hasPendingUtterance = true

        // Provisional flag from the running confidence totals — more reliable
        // than partial length once whole segments are in
        val enAvg = if (enWordCount > 0) enConfSum / enWordCount else 0.0
        val ptAvg = if (ptWordCount > 0) ptConfSum / ptWordCount else 0.0
        if (ptAvg > enAvg) showFlag("\uD83C\uDDF5\uD83C\uDDF9", colorPt)
        else if (enAvg > 0.0) showFlag("\uD83C\uDDEC\uD83C\uDDE7", colorEn)

        // Lock-in: if one decoder is clearly winning with enough evidence,
        // stop feeding the loser for the rest of this utterance (saves ~half
        // the CPU and reduces cross-language confusion mid-sentence)
        if (lockedLang == null &&
            maxOf(enWordCount, ptWordCount) >= LOCK_MIN_WORDS &&
            kotlin.math.abs(enAvg - ptAvg) >= LOCK_MARGIN
        ) {
            lockedLang = if (ptAvg > enAvg) "pt" else "en"
        }

        mainHandler.removeCallbacks(commitRunnable)
        mainHandler.postDelayed(commitRunnable, MERGE_GAP_MS)
    }

    /** Update flag emoji + meter colour, skipping redundant UI posts. */
    private fun showFlag(flag: String, accent: Int) {
        if (flag == shownFlag) return
        shownFlag = flag
        meter.setAccent(accent)
        runOnUiThread { flagView.text = flag }
    }

    /**
     * Decide language over the full utterance using four signals:
     *   1. Vosk decoder confidence (weight 1.5)
     *   2. ML Kit language ID — does the decode actually read as its own
     *      language? Garbled cross-language decodes score lower (weight 0.5)
     *   3. Word-count share — the right decoder transcribes more (weight 0.5)
     *   4. Sticky prior — small bonus for whoever spoke last
     * Then gate noise and translate.
     */
    private fun commitPending() {
        val enAvg = if (enWordCount > 0) enConfSum / enWordCount else 0.0
        val ptAvg = if (ptWordCount > 0) ptConfSum / ptWordCount else 0.0
        val enText = enBuf.toString()
        val ptText = ptBuf.toString()
        val enWords = enWordCount
        val ptWords = ptWordCount

        enBuf.clear(); ptBuf.clear()
        enConfSum = 0.0; enWordCount = 0
        ptConfSum = 0.0; ptWordCount = 0
        hasPendingUtterance = false
        lockedLang = null

        if (enWords == 0 && ptWords == 0) return

        val totalWords = (enWords + ptWords).coerceAtLeast(1)
        val enShare = enWords.toDouble() / totalWords
        val ptShare = ptWords.toDouble() / totalWords

        // Ask ML Kit whether each decode reads as its own language
        langConfidence(enText, "en") { enLid ->
            langConfidence(ptText, "pt") { ptLid ->
                var scoreEn = 1.5 * enAvg + 0.5 * enLid + 0.5 * enShare
                var scorePt = 1.5 * ptAvg + 0.5 * ptLid + 0.5 * ptShare
                if (lastCommittedLang == "en") scoreEn += STICKY_BONUS
                if (lastCommittedLang == "pt") scorePt += STICKY_BONUS

                val (lang, text, avg, words) =
                    if (scorePt > scoreEn) Committed("pt", ptText, ptAvg, ptWords)
                    else Committed("en", enText, enAvg, enWords)

                // Noise gate — applied once, to the whole utterance
                if (words == 0 || avg < MIN_CONF) return@langConfidence
                if (words == 1 && avg < SINGLE_WORD_CONF) return@langConfidence

                lastCommittedLang = lang
                translateAndSpeak(text, lang)
            }
        }
    }

    /** Confidence (0..1) that [text] is in language [tag], via on-device ML Kit. */
    private fun langConfidence(text: String, tag: String, then: (Double) -> Unit) {
        if (text.isBlank()) { then(0.0); return }
        languageId.identifyPossibleLanguages(text)
            .addOnSuccessListener { candidates ->
                val match = candidates.firstOrNull { it.languageTag.startsWith(tag) }
                then(match?.confidence?.toDouble() ?: 0.0)
            }
            .addOnFailureListener { then(0.5) } // neutral if the identifier fails
    }

    private data class Committed(
        val lang: String, val text: String, val avg: Double, val words: Int
    )

    // ---------- Translate + speak ----------

    private fun translateAndSpeak(text: String, sourceLang: String) {
        val translator = if (sourceLang == "en") enToPt else ptToEn
        translator.translate(text)
            .addOnSuccessListener { translated ->
                addTranscriptEntry(sourceLang, text, translated)
                speak(translated, if (sourceLang == "en") "pt" else "en")
            }
            .addOnFailureListener { e -> toast(getString(R.string.translate_failed, e.message)) }
    }

    private fun speak(text: String, lang: String) {
        val t = tts ?: return
        if (!ttsReady) return
        val locale = if (lang == "pt") Locale("pt", "PT") else Locale.UK
        t.language = if (t.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) locale
        else if (lang == "pt") Locale("pt") else Locale.ENGLISH
        ttsSpeaking.set(true)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "utt-${System.currentTimeMillis()}")
    }

    // ---------- Self-update via GitHub Releases ----------

    private fun checkForUpdate() {
        btnUpdate.isEnabled = false
        setStatus(getString(R.string.status_checking_update))
        Thread {
            try {
                val api = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                val conn = api.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val release = JSONObject(body)
                val tag = release.optString("tag_name").removePrefix("v")
                val latest = tag.toIntOrNull() ?: 0
                val assets = release.optJSONArray("assets")
                val apkUrl = (0 until (assets?.length() ?: 0))
                    .map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url")

                if (latest <= BuildConfig.VERSION_CODE || apkUrl == null) {
                    runOnUiThread {
                        setStatus(getString(R.string.status_up_to_date, BuildConfig.VERSION_CODE))
                        btnUpdate.isEnabled = true
                    }
                    return@Thread
                }

                runOnUiThread { setStatus(getString(R.string.status_downloading_update, latest)) }
                val apkFile = File(cacheDir, "update.apk")
                URL(apkUrl).openStream().use { input ->
                    FileOutputStream(apkFile).use { output -> input.copyTo(output) }
                }

                val uri = FileProvider.getUriForFile(this, "com.talkbridge.fileprovider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runOnUiThread {
                    btnUpdate.isEnabled = true
                    setStatus(getString(R.string.status_ready))
                    startActivity(intent)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnUpdate.isEnabled = true
                    setStatus(getString(R.string.status_update_failed, e.message))
                }
            }
        }.start()
    }

    // ---------- UI helpers ----------

    private fun addTranscriptEntry(sourceLang: String, original: String, translated: String) {
        val entry = TextView(this)
        val flagOriginal = if (sourceLang == "en") "\uD83C\uDDEC\uD83C\uDDE7" else "\uD83C\uDDF5\uD83C\uDDF9"
        val flagTranslated = if (sourceLang == "en") "\uD83C\uDDF5\uD83C\uDDF9" else "\uD83C\uDDEC\uD83C\uDDE7"
        entry.text = "$flagOriginal  $original\n$flagTranslated  $translated"
        entry.textSize = 18f
        entry.setPadding(24, 20, 24, 20)
        entry.setTypeface(Typeface.DEFAULT)
        entry.gravity = if (sourceLang == "en") Gravity.START else Gravity.END
        transcript.addView(entry)
        transcriptScroll.post { transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(msg: String) = runOnUiThread { statusText.text = msg }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------- Lifecycle ----------

    override fun onDestroy() {
        super.onDestroy()
        running.set(false)
        tts?.shutdown()
        enToPt.close()
        ptToEn.close()
        languageId.close()
        modelEn?.close()
        modelPt?.close()
    }
}
