package com.neoai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceController(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: (() -> Unit)? = null

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onSpeechStart: (() -> Unit)? = null
    var onSpeechEnd: ((Boolean) -> Unit)? = null

    fun isListening() = listening

    fun startListening(language: String) {
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError?.invoke("Is phone par speech recognition available nahi hai. Text se likh dein.")
                return@post
            }
            if (listening) return@post
            if (recognizer == null) recognizer = buildRecognizer()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            }
            listening = true
            runCatching { recognizer?.startListening(intent) }
                .onFailure {
                    listening = false
                    onError?.invoke("Mic start nahi hua. Dobara try karein.")
                }
        }
    }

    fun stopListening() {
        main.post {
            if (listening) runCatching { recognizer?.stopListening() }
            listening = false
        }
    }

    fun cancel() {
        main.post {
            runCatching { recognizer?.cancel() }
            listening = false
        }
    }

    private fun buildRecognizer(): SpeechRecognizer {
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                onError?.invoke(errorText(error))
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isBlank()) onError?.invoke("Kuch sunai nahi diya.") else onFinal?.invoke(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) onPartial?.invoke(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        return r
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Mic audio error."
        SpeechRecognizer.ERROR_CLIENT -> "Speech client error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission chahiye."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition ko network chahiye. Internet check karein."
        SpeechRecognizer.ERROR_NO_MATCH -> "Samajh nahi aaya, dobara boliye."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy hai, thoda ruk kar boliye."
        SpeechRecognizer.ERROR_SERVER -> "Speech server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch sunai nahi diya."
        else -> "Voice error ($error)."
    }

    // ---------------------------------------------------------------- TTS

    /** Speak with the phone's built-in engine. Used when ElevenLabs is absent. */
    fun speakLocal(text: String, language: String) {
        val task = {
            val engine = tts
            if (engine == null || !ttsReady) {
                onSpeechEnd?.invoke(false)
            } else {
                val locale = Locale.forLanguageTag(normalizeLang(language))
                val res = engine.setLanguage(locale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.ENGLISH)
                }
                onSpeechStart?.invoke()
                val id = "neo-" + System.nanoTime()
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            }
        }
        if (ttsReady) {
            if (Looper.myLooper() == Looper.getMainLooper()) task() else main.post(task)
        } else {
            pendingSpeak = task
            initTts(language)
        }
    }

    private fun initTts(language: String) {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val locale = Locale.forLanguageTag(normalizeLang(language))
                tts?.setLanguage(locale)
                tts?.setSpeechRate(0.98f)
                tts?.setPitch(1.08f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = onSpeechStart?.invoke()
                    override fun onDone(utteranceId: String?) = onSpeechEnd?.invoke(true)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onSpeechEnd?.invoke(false)
                    override fun onError(utteranceId: String?, errorCode: Int) = onSpeechEnd?.invoke(false)
                })
                pendingSpeak?.invoke()
                pendingSpeak = null
            } else {
                onSpeechEnd?.invoke(false)
            }
        }
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        main.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            ttsReady = false
        }
    }

    private fun normalizeLang(language: String): String = when {
        language.startsWith("hi") -> "hi-IN"
        language.startsWith("en") -> "en-IN"
        else -> "hi-IN"
    }
}
