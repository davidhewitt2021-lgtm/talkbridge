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
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
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
 * TalkBridge v3 engine: OpenAI Whisper (base, int8) running on-device via
 * sherpa-onnx, segmented by the Silero neural voice-activity detector.
 *
 * Compared to the old dual-Vosk design:
 *  - ONE decoder; Whisper detects the spoken language natively per utterance
 *  - Neural VAD instead of an energy heuristic: far fewer hallucinations
 *  - Much better accuracy, especially for Portuguese and accented speech
 *  - Trade-off: no live partial text (Whisper decodes whole utterances)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512 // samples per VAD step (32ms)

        // --- Tuning knobs ---
        const val VAD_THRESHOLD = 0.60f       // Silero speech probability threshold
        const val MIN_SILENCE_S = 0.8f        // pause that ends an utterance
        const val MIN_SPEECH_S = 0.5f         // ignore blips shorter than this
        const val MAX_SPEECH_S = 18f          // hard cap per utterance
        const val WHISPER_THREADS = 4
        const val MAX_WORD_RUN = 2            // collapse word repeats beyond this
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

    private val colorEn = 0xFF1E5AA8.toInt() // blue
    private val colorPt = 0xFFDA291C.toInt() // red
    private val colorIdle = 0xFF888888.toInt()
    private var shownFlag = ""

    // Two language-forced decoders (forced decode beats auto-detect decode)
    // plus a fast dedicated language-identification pass (Whisper tiny)
    private var recognizerEn: OfflineRecognizer? = null
    private var recognizerPt: OfflineRecognizer? = null
    private var slid: SpokenLanguageIdentification? = null
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

    private var lastCommittedLang = "en" // fallback for ambiguous detections

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
        initSpeechEngine()
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

    /** Load Whisper + SLID + Silero VAD straight from APK assets. */
    private fun initSpeechEngine() {
        setStatus(getString(R.string.status_loading_speech))
        Thread {
            try {
                fun makeRecognizer(language: String) = OfflineRecognizer(
                    assets,
                    OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            whisper = OfflineWhisperModelConfig(
                                encoder = "whisper/base-encoder.int8.onnx",
                                decoder = "whisper/base-decoder.int8.onnx",
                                language = language, // forced: no in-decode detection
                                task = "transcribe",
                            ),
                            tokens = "whisper/base-tokens.txt",
                            numThreads = WHISPER_THREADS,
                            modelType = "whisper",
                        ),
                    )
                )
                recognizerEn = makeRecognizer("en")
                recognizerPt = makeRecognizer("pt")

                // Dedicated language ID: a fast Whisper-tiny pass per segment
                slid = SpokenLanguageIdentification(
                    assets,
                    SpokenLanguageIdentificationConfig(
                        whisper = SpokenLanguageIdentificationWhisperConfig(
                            encoder = "lid/tiny-encoder.int8.onnx",
                            decoder = "lid/tiny-decoder.int8.onnx",
                        ),
                        numThreads = 2,
                    )
                )

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
        showFlag("\uD83C\uDF99", colorIdle) // 🎙
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

                // Level meter (RMS of this 32ms window)
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
                    showFlag("\uD83D\uDD0A", colorIdle) // 🔊
                    continue
                }
                if (wasSpeaking) {
                    vad.flush()
                    while (!vad.empty()) vad.pop() // discard anything captured pre-TTS
                    wasSpeaking = false
                }
                meter.push(level)

                // Neural VAD: Silero decides what is speech
                vad.acceptWaveform(floatBuf)

                if (vad.isSpeechDetected()) {
                    showFlag("\uD83D\uDC42", colorIdle) // 👂 hearing speech
                } else if (decodesInFlight.get() == 0) {
                    showFlag("\uD83C\uDF99", colorIdle) // 🎙 idle
                }

                // Completed speech segments -> Whisper (off this thread)
                while (!vad.empty()) {
                    val samples = vad.front().samples
                    vad.pop()
                    decodesInFlight.incrementAndGet()
                    runOnUiThread {
                        partialText.text = getString(R.string.status_transcribing)
                    }
                    decodeExecutor.execute { decodeSegment(samples) }
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

    /** Runs on the decode executor: identify language, then forced decode. */
    private fun decodeSegment(samples: FloatArray) {
        try {
            // Stage 1: fast language ID (Whisper tiny, ~0.2-0.5s)
            val lid = slid ?: return
            val lidStream = lid.createStream()
            lidStream.acceptWaveform(samples, SAMPLE_RATE)
            val detected = lid.compute(lidStream).lowercase().filter { it.isLetter() }
            lidStream.release()

            val lang = when (detected) {
                "en" -> "en"
                // pt-PT is occasionally tagged as Galician or Spanish;
                // in a two-language app, fold those into Portuguese
                "pt", "gl", "es" -> "pt"
                "" -> lastCommittedLang // ID failed: assume same speaker
                else -> return // confidently another language: not for us
            }

            // Stage 2: decode with the language FORCED — no detection wobble,
            // and forced decodes are more accurate than auto ones
            val rec = (if (lang == "en") recognizerEn else recognizerPt) ?: return
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream)
            stream.release()

            val durationS = samples.size.toFloat() / SAMPLE_RATE
            val text = sanitizeTranscript(result.text, durationS) ?: return

            lastCommittedLang = lang
            runOnUiThread {
                if (lang == "pt") showFlag("\uD83C\uDDF5\uD83C\uDDF9", colorPt)
                else showFlag("\uD83C\uDDEC\uD83C\uDDE7", colorEn)
            }
            translateAndSpeak(text, lang)
        } catch (e: Exception) {
            runOnUiThread { toast(getString(R.string.mic_failed, e.message)) }
        } finally {
            if (decodesInFlight.decrementAndGet() == 0) {
                runOnUiThread { partialText.text = "" }
            }
        }
    }

    /**
     * Whisper's known failure mode on noisy or clipped audio is the
     * repetition loop ("no no no no…"). This cleans transcripts up and
     * rejects ones that were mostly hallucinated:
     *  1. Collapse runs of the same word beyond MAX_WORD_RUN
     *  2. Collapse whole-phrase loops (text that is a repeated block)
     *  3. Reject if the transcript was dominated by looping
     *  4. Reject if lots of audio produced almost no words (decode failure)
     * Returns the cleaned text, or null if the segment should be discarded.
     */
    private fun sanitizeTranscript(raw: String, durationS: Float): String? {
        fun norm(w: String) = w.lowercase().trim('.', ',', '!', '?', ';', ':', '…', '-')

        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        // 1. Collapse word runs: "no no no no no" -> "no no"
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

        // 2. Collapse phrase loops: "how are you how are you" -> "how are you"
        var result: List<String> = collapsed
        while (result.size >= 4 && result.size % 2 == 0) {
            val half = result.size / 2
            val looped = (0 until half).all { norm(result[it]) == norm(result[it + half]) }
            if (!looped) break
            result = result.subList(0, half)
        }

        // 3. If most of the segment was looping and little survived, it was
        //    a hallucination artifact, not speech
        val loopRatio = words.size.toDouble() / result.size
        if (loopRatio > 3.0 && result.size <= 3) return null

        // 4. Several seconds of audio decoding to a word or two means the
        //    decode failed — better to say nothing than something wrong
        if (durationS > 4f && result.size <= 1) return null

        return result.joinToString(" ")
    }

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

    private fun showFlag(flag: String, accent: Int) {
        if (flag == shownFlag) return
        shownFlag = flag
        meter.setAccent(accent)
        runOnUiThread { flagView.text = flag }
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
        slid?.release()
        vad?.release()
    }
}
