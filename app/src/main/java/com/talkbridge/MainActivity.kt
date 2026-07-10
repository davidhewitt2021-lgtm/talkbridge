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
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TalkBridge v4: manual language lock.
 *
 * The speaker's language is selected with two mode buttons rather than
 * auto-detected. This removes the entire detection stack (spoken language
 * ID, similarity routing, arbitration) — the source of every routing error —
 * and roughly halves the decode work per utterance.
 *
 * Pipeline per utterance:
 *   EN mode: VAD -> Whisper (EN-forced) -> ML Kit EN->PT -> speak PT
 *   PT mode: VAD -> Whisper (PT-forced) -> ensemble translation
 *            (Whisper translate task vs ML Kit, agreement-gated) -> speak EN
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512 // samples per VAD step (32ms)

        // --- Tuning knobs ---
        const val VAD_THRESHOLD = 0.60f  // Silero speech probability threshold
        const val MIN_SILENCE_S = 0.8f   // pause that ends an utterance
        const val MIN_SPEECH_S = 0.5f    // ignore blips shorter than this
        const val MAX_SPEECH_S = 18f     // hard cap per utterance
        const val WHISPER_THREADS = 4
        const val MAX_WORD_RUN = 2       // collapse word repeats beyond this
        const val MIN_TRANSLATE_S = 2.0f // clips shorter than this skip whisper-translate
        const val TRANSLATE_AGREE = 0.6  // whisper/ML Kit agreement to prefer whisper wording
        const val GITHUB_REPO = "davidhewitt2021-lgtm/talkbridge"
    }

    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var btnConversation: Button
    private lateinit var btnUpdate: Button
    private lateinit var btnModeEn: Button
    private lateinit var btnModePt: Button
    private lateinit var activityView: TextView
    private lateinit var meter: LevelMeterView

    private val colorEn = 0xFF1E5AA8.toInt() // blue
    private val colorPt = 0xFFDA291C.toInt() // red
    private var shownActivity = ""

    /** The locked speaker language: "en" or "pt". Set by the mode buttons. */
    @Volatile
    private var mode = "en"

    private var recognizerEn: OfflineRecognizer? = null
    private var recognizerPt: OfflineRecognizer? = null
    private var recognizerPtTranslate: OfflineRecognizer? = null
    private var vad: Vad? = null
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val decodesInFlight = AtomicInteger(0)

    private lateinit var enToPt: Translator
    private lateinit var ptToEn: Translator
    private var translatorsReady = false
    private var asrReady = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val running = AtomicBoolean(false)
    private val ttsSpeaking = AtomicBoolean(false)
    private var audioThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the last run crashed, show the report instead of the normal UI
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
        btnModeEn = findViewById(R.id.btnModeEn)
        btnModePt = findViewById(R.id.btnModePt)
        activityView = findViewById(R.id.activityView)
        meter = findViewById(R.id.meter)

        btnConversation.isEnabled = false
        btnConversation.setOnClickListener { toggleConversation() }
        btnUpdate.setOnClickListener { checkForUpdate() }
        btnModeEn.setOnClickListener { setMode("en") }
        btnModePt.setOnClickListener { setMode("pt") }
        setMode("en")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        initTts()
        initTranslators()
        initSpeechEngine()
    }

    /** Lock the speaker language and reflect it in the UI. */
    private fun setMode(lang: String) {
        mode = lang
        val enSelected = lang == "en"
        btnModeEn.alpha = if (enSelected) 1.0f else 0.45f
        btnModePt.alpha = if (enSelected) 0.45f else 1.0f
        meter.setAccent(if (enSelected) colorEn else colorPt)
    }

    // ---------- Crash reporting ----------

    private fun showCrashReport(trace: String) {
        val tv = TextView(this).apply {
            text = trace
            textSize = 12f
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setPadding(32, 32, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
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

    /** Load Whisper decoders + Silero VAD straight from APK assets. */
    private fun initSpeechEngine() {
        setStatus(getString(R.string.status_loading_speech))
        Thread {
            try {
                fun makeRecognizer(language: String, whisperTask: String) = OfflineRecognizer(
                    assets,
                    OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = "whisper/small-encoder.int8.onnx",
                                decoder = "whisper/small-decoder.int8.onnx",
                                language = language,
                                task = whisperTask,
                            ),
                            tokens = "whisper/small-tokens.txt",
                            numThreads = WHISPER_THREADS,
                            modelType = "whisper",
                        ),
                    )
                )
                recognizerEn = makeRecognizer("en", "transcribe")
                recognizerPt = makeRecognizer("pt", "transcribe")
                recognizerPtTranslate = makeRecognizer("pt", "translate")

                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "vad/silero_vad.onnx",
                        threshold = VAD_THRESHOLD,
                        minSilenceDuration = MIN_SILENCE_S,
                        minSpeechDuration = MIN_SPEECH_S,
                        maxSpeechDuration = MAX_SPEECH_S,
                        windowSize = VAD_WINDOW,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                )
                vad = Vad(assets, vadConfig)

                asrReady = true
                runOnUiThread { maybeReady() }
            } catch (e: Exception) {
                runOnUiThread { setStatus(getString(R.string.status_speech_failed, e.message)) }
            }
        }.start()
    }

    private fun maybeReady() {
        if (asrReady && translatorsReady) {
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
        partialText.text = ""
        meter.clear()
        showActivity("\uD83C\uDF99") // 🎙
        btnConversation.text = getString(R.string.btn_start_conversation)
        setStatus(getString(R.string.status_ready))
    }

    @SuppressLint("MissingPermission")
    private fun audioLoop() {
        val vad = this.vad ?: return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, VAD_WINDOW * 8)
        )
        val shortBuf = ShortArray(VAD_WINDOW)
        val floatBuf = FloatArray(VAD_WINDOW)
        var wasSpeaking = false

        try {
            recorder.startRecording()
            while (running.get()) {
                var read = 0
                while (read < VAD_WINDOW && running.get()) {
                    val n = recorder.read(shortBuf, read, VAD_WINDOW - read)
                    if (n > 0) read += n
                }
                if (read < VAD_WINDOW) continue

                var sum = 0.0
                for (i in 0 until VAD_WINDOW) {
                    val s = shortBuf[i].toDouble()
                    sum += s * s
                    floatBuf[i] = shortBuf[i] / 32768.0f
                }
                val level = (Math.sqrt(sum / VAD_WINDOW) / 32768.0 * 12.0).toFloat()

                // Echo suppression: ignore the mic while the phone is talking
                if (ttsSpeaking.get()) {
                    wasSpeaking = true
                    meter.push(0f)
                    showActivity("\uD83D\uDD0A") // 🔊
                    continue
                }
                if (wasSpeaking) {
                    vad.flush()
                    while (!vad.empty()) vad.pop()
                    wasSpeaking = false
                    showActivity("\uD83C\uDF99") // 🎙
                }
                meter.push(level)

                vad.acceptWaveform(floatBuf)

                if (vad.isSpeechDetected()) {
                    showActivity("\uD83D\uDC42") // 👂
                } else if (decodesInFlight.get() == 0) {
                    showActivity("\uD83C\uDF99") // 🎙
                }

                while (!vad.empty()) {
                    val samples = vad.front().samples
                    vad.pop()
                    // Capture the mode at utterance time, so switching modes
                    // mid-decode cannot cross-wire a pending segment
                    val utteranceLang = mode
                    decodesInFlight.incrementAndGet()
                    runOnUiThread {
                        partialText.text = getString(R.string.status_transcribing)
                    }
                    decodeExecutor.execute { decodeSegment(samples, utteranceLang) }
                }
            }
            vad.flush()
            while (!vad.empty()) vad.pop()
        } catch (e: Exception) {
            runOnUiThread { toast(getString(R.string.mic_failed, e.message)) }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }
    }

    /** Runs on the decode executor: one forced decode in the locked language. */
    private fun decodeSegment(samples: FloatArray, lang: String) {
        try {
            val durationS = samples.size.toFloat() / SAMPLE_RATE
            if (lang == "en") {
                val text = sanitizeTranscript(decodeWith(recognizerEn, samples), durationS)
                    ?: return
                translateEnglish(text)
            } else {
                val text = sanitizeTranscript(decodeWith(recognizerPt, samples), durationS)
                    ?: return
                translatePortuguese(samples, text, durationS)
            }
        } catch (e: Exception) {
            runOnUiThread { toast(getString(R.string.mic_failed, e.message)) }
        } finally {
            if (decodesInFlight.decrementAndGet() == 0) {
                runOnUiThread { partialText.text = "" }
            }
        }
    }

    /** One forced decode of a segment; returns the raw transcript. */
    private fun decodeWith(rec: OfflineRecognizer?, samples: FloatArray): String {
        val r = rec ?: return ""
        val stream = r.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        r.decode(stream)
        val text = r.getResult(stream).text
        stream.release()
        return text
    }

    /**
     * Cleans Whisper repetition-loop artifacts and event tags, and rejects
     * transcripts that were mostly hallucinated. Returns text or null.
     */
    private fun sanitizeTranscript(raw: String, durationS: Float): String? {
        fun norm(w: String) = w.lowercase().trim('.', ',', '!', '?', ';', ':', '…', '-')

        // Strip whisper event tags emitted on music/noise, including
        // unclosed ones: [Música], (music), "(laughs", ♪
        val cleaned = raw.replace(Regex("\\[[^\\]]*\\]?|\\([^)]*\\)?|♪"), " ")
        val words = cleaned.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val junk = setOf("laughs", "laughter", "music", "applause", "sighs",
                         "risos", "música", "musica", "aplausos")
        if (words.all { it.lowercase().trim('.', ',', '!') in junk }) return null

        val collapsed = ArrayList<String>(words.size)
        var runWord = ""
        var runLen = 0
        for (w in words) {
            if (norm(w) == runWord) {
                runLen++
                if (runLen <= MAX_WORD_RUN) collapsed.add(w)
            } else {
                runWord = norm(w)
                runLen = 1
                collapsed.add(w)
            }
        }

        var result: List<String> = collapsed
        while (result.size >= 4 && result.size % 2 == 0) {
            val half = result.size / 2
            val looped = (0 until half).all { norm(result[it]) == norm(result[it + half]) }
            if (!looped) break
            result = result.subList(0, half)
        }

        val loopRatio = words.size.toDouble() / result.size
        if (loopRatio > 3.0 && result.size <= 3) return null
        if (durationS > 4f && result.size <= 1) return null

        return result.joinToString(" ")
    }

    // ---------- Translation ----------

    /** EN -> PT via ML Kit; speak Portuguese. */
    private fun translateEnglish(text: String) {
        enToPt.translate(text)
            .addOnSuccessListener { translated ->
                addTranscriptEntry("en", text, translated)
                speak(translated, "pt")
            }
            .addOnFailureListener { e -> toast(getString(R.string.translate_failed, e.message)) }
    }

    /**
     * PT -> EN translation ensemble. ML Kit is faithful to the transcript;
     * whisper-translate is more fluent but prone to phonetic passthrough
     * ("cerveja" -> "survey"). Whisper's wording is used only when the two
     * independent translations agree; on divergence, faithfulness wins.
     */
    private fun translatePortuguese(samples: FloatArray, ptTranscript: String, durationS: Float) {
        val whisperEnglish =
            if (durationS >= MIN_TRANSLATE_S)
                sanitizeTranscript(decodeWith(recognizerPtTranslate, samples), durationS)
            else null

        ptToEn.translate(ptTranscript)
            .addOnSuccessListener { mlkit ->
                val chosen = if (whisperEnglish != null &&
                    wordSimilarity(whisperEnglish, mlkit) >= TRANSLATE_AGREE
                ) whisperEnglish else mlkit
                addTranscriptEntry("pt", ptTranscript, chosen)
                speak(chosen, "en")
            }
            .addOnFailureListener {
                val fallback = whisperEnglish
                if (fallback != null) {
                    runOnUiThread { addTranscriptEntry("pt", ptTranscript, fallback) }
                    speak(fallback, "en")
                }
            }
    }

    /** Word-set overlap (Jaccard) between two texts, 0..1. Ensemble use only. */
    private fun wordSimilarity(a: String, b: String): Double {
        fun tokens(t: String) = t.lowercase()
            .replace(Regex("[^a-z0-9à-ÿ ]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        val ta = tokens(a); val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
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

    private fun showActivity(icon: String) {
        if (icon == shownActivity) return
        shownActivity = icon
        runOnUiThread { activityView.text = icon }
    }

    private fun setStatus(msg: String) = runOnUiThread { statusText.text = msg }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------- Lifecycle ----------

    override fun onDestroy() {
        super.onDestroy()
        running.set(false)
        decodeExecutor.shutdown()
        tts?.shutdown()
        if (::enToPt.isInitialized) enToPt.close()
        if (::ptToEn.isInitialized) ptToEn.close()
        recognizerEn?.release()
        recognizerPt?.release()
        recognizerPtTranslate?.release()
        vad?.release()
    }
}
