package com.talkbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        const val MIN_CONF = 0.72        // discard utterances below this avg confidence
        const val SINGLE_WORD_CONF = 0.90 // single words must be near-certain
        const val MERGE_GAP_MS = 900L     // pause length that ends a sentence
        const val GITHUB_REPO = "davidhewitt2021-lgtm/talkbridge"
    }

    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var btnConversation: Button
    private lateinit var btnUpdate: Button

    private var modelEn: Model? = null
    private var modelPt: Model? = null

    private lateinit var enToPt: Translator
    private lateinit var ptToEn: Translator
    private var translatorsReady = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val running = AtomicBoolean(false)      // conversation mode on/off
    private val ttsSpeaking = AtomicBoolean(false)  // echo suppression flag
    private var audioThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Sentence merging state
    private var pendingText = ""
    private var pendingLang = ""
    private val commitRunnable = Runnable { commitPending() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        partialText = findViewById(R.id.partialText)
        transcript = findViewById(R.id.transcript)
        transcriptScroll = findViewById(R.id.transcriptScroll)
        btnConversation = findViewById(R.id.btnConversation)
        btnUpdate = findViewById(R.id.btnUpdate)

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

        try {
            recorder.startRecording()
            while (running.get()) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n <= 0) continue

                // Echo suppression: while the phone is talking, discard mic input
                if (ttsSpeaking.get()) {
                    wasSpeaking = true
                    continue
                }
                if (wasSpeaking) {
                    // Flush anything the recognizers were mid-way through
                    recEn.reset(); recPt.reset()
                    wasSpeaking = false
                }

                val enDone = recEn.acceptWaveForm(buffer, n)
                val ptDone = recPt.acceptWaveForm(buffer, n)

                if (enDone || ptDone) {
                    val enHyp = parseHyp(if (enDone) recEn.result else recEn.finalResult)
                    val ptHyp = parseHyp(if (ptDone) recPt.result else recPt.finalResult)
                    recEn.reset(); recPt.reset()
                    handleSegment(enHyp, ptHyp)
                } else {
                    // Live partial: show whichever language has more to say
                    val pEn = JSONObject(recEn.partialResult).optString("partial")
                    val pPt = JSONObject(recPt.partialResult).optString("partial")
                    val partial = if (pPt.length > pEn.length) pPt else pEn
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

    /** Decide language for a finished segment, gate out noise, merge into pending sentence. */
    private fun handleSegment(en: Hyp, pt: Hyp) {
        if (en.words == 0 && pt.words == 0) return

        val (lang, hyp) = if (pt.avgConf > en.avgConf) "pt" to pt else "en" to en

        // Noise gate
        if (hyp.avgConf < MIN_CONF) return
        if (hyp.words == 1 && hyp.avgConf < SINGLE_WORD_CONF) return

        runOnUiThread {
            mainHandler.removeCallbacks(commitRunnable)
            if (pendingLang.isNotEmpty() && pendingLang != lang) {
                // Speaker switched language: finish the previous sentence first
                commitPending()
            }
            pendingLang = lang
            pendingText = if (pendingText.isEmpty()) hyp.text else "$pendingText ${hyp.text}"
            partialText.text = ""
            // Wait for a real pause before translating, in case the sentence continues
            mainHandler.postDelayed(commitRunnable, MERGE_GAP_MS)
        }
    }

    private fun commitPending() {
        val text = pendingText
        val lang = pendingLang
        pendingText = ""
        pendingLang = ""
        if (text.isBlank()) return
        translateAndSpeak(text, lang)
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
        ttsSpeaking.set(true) // set eagerly; listener keeps it accurate
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
        modelEn?.close()
        modelPt?.close()
    }
}
