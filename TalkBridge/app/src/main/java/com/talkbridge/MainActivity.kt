package com.talkbridge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var statusText: TextView
    private lateinit var partialText: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var btnEnglish: Button
    private lateinit var btnPortuguese: Button

    private var modelEn: Model? = null
    private var modelPt: Model? = null
    private var speechService: SpeechService? = null
    private var activeLang: String? = null // "en" or "pt" while listening

    private lateinit var enToPt: Translator
    private lateinit var ptToEn: Translator
    private var translatorsReady = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        partialText = findViewById(R.id.partialText)
        transcript = findViewById(R.id.transcript)
        transcriptScroll = findViewById(R.id.transcriptScroll)
        btnEnglish = findViewById(R.id.btnEnglish)
        btnPortuguese = findViewById(R.id.btnPortuguese)

        btnEnglish.isEnabled = false
        btnPortuguese.isEnabled = false

        btnEnglish.setOnClickListener { toggleListening("en") }
        btnPortuguese.setOnClickListener { toggleListening("pt") }

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
            .addOnSuccessListener {
                translatorsReady = true
                maybeReady()
            }
            .addOnFailureListener {
                setStatus(getString(R.string.status_translation_failed))
            }
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

    /** Copies an asset directory to internal storage on first run, returns the target dir. */
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
            // It's a file
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        } else {
            target.mkdirs()
            for (child in children) {
                copyAssetDir("$assetPath/$child", File(target, child))
            }
        }
    }

    private fun maybeReady() {
        if (modelEn != null && modelPt != null && translatorsReady) {
            btnEnglish.isEnabled = true
            btnPortuguese.isEnabled = true
            setStatus(getString(R.string.status_ready))
        }
    }

    // ---------- Listening ----------

    private fun toggleListening(lang: String) {
        if (activeLang == lang) {
            stopListening()
            return
        }
        if (activeLang != null) stopListening()
        startListening(lang)
    }

    private fun startListening(lang: String) {
        val model = if (lang == "en") modelEn else modelPt
        if (model == null) return
        try {
            val recognizer = Recognizer(model, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)
            activeLang = lang
            updateButtons()
            setStatus(
                if (lang == "en") getString(R.string.status_listening_en)
                else getString(R.string.status_listening_pt)
            )
        } catch (e: Exception) {
            toast(getString(R.string.mic_failed, e.message))
        }
    }

    private fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        activeLang = null
        partialText.text = ""
        updateButtons()
        setStatus(getString(R.string.status_ready))
    }

    private fun updateButtons() {
        btnEnglish.text = if (activeLang == "en")
            getString(R.string.btn_stop) else getString(R.string.btn_english)
        btnPortuguese.text = if (activeLang == "pt")
            getString(R.string.btn_stop) else getString(R.string.btn_portuguese)
        btnEnglish.alpha = if (activeLang == "pt") 0.4f else 1f
        btnPortuguese.alpha = if (activeLang == "en") 0.4f else 1f
    }

    // ---------- Vosk callbacks ----------

    override fun onPartialResult(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("partial") } ?: return
        partialText.text = text
    }

    override fun onResult(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("text") } ?: return
        if (text.isBlank()) return
        val lang = activeLang ?: return
        partialText.text = ""
        translateAndSpeak(text, lang)
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = hypothesis?.let { JSONObject(it).optString("text") } ?: return
        if (text.isBlank()) return
        // activeLang may already be cleared by stopListening(); infer from button state is
        // unnecessary — capture nothing, just skip if unknown.
        val lang = activeLang ?: return
        translateAndSpeak(text, lang)
    }

    override fun onError(e: Exception?) {
        toast(getString(R.string.mic_failed, e?.message))
        stopListening()
    }

    override fun onTimeout() {
        stopListening()
    }

    // ---------- Translate + speak ----------

    private fun translateAndSpeak(text: String, sourceLang: String) {
        val translator = if (sourceLang == "en") enToPt else ptToEn
        translator.translate(text)
            .addOnSuccessListener { translated ->
                addTranscriptEntry(sourceLang, text, translated)
                speak(translated, if (sourceLang == "en") "pt" else "en")
            }
            .addOnFailureListener { e ->
                toast(getString(R.string.translate_failed, e.message))
            }
    }

    private fun speak(text: String, lang: String) {
        val t = tts ?: return
        if (!ttsReady) return
        val locale = if (lang == "pt") Locale("pt", "PT") else Locale.UK
        val availability = t.isLanguageAvailable(locale)
        t.language = if (availability >= TextToSpeech.LANG_AVAILABLE) {
            locale
        } else if (lang == "pt") {
            Locale("pt") // fall back to any Portuguese voice
        } else {
            Locale.ENGLISH
        }
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "utt-${System.currentTimeMillis()}")
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

    private fun setStatus(msg: String) {
        statusText.text = msg
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------- Lifecycle ----------

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        tts?.shutdown()
        enToPt.close()
        ptToEn.close()
        modelEn?.close()
        modelPt?.close()
    }
}
