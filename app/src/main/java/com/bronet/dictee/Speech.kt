package com.bronet.dictee

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Dictée « maintenir pour parler » : écoute tant que la touche est tenue
 * (relance automatique après une pause), puis termine au relâchement.
 */
class Dictation(private val ctx: Context) {

    interface Callbacks {
        fun onPartial(text: String)
        fun onSegment(text: String)
        fun onEnd(error: String?)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var cb: Callbacks? = null
    private var tag = "fr-CA"
    private var active = false
    private var released = false
    private var ready = false
    private var listening = false
    private var lastPartial = ""

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)
    fun isActive() = active

    fun start(languageTag: String, callbacks: Callbacks) {
        cancel()
        tag = languageTag
        cb = callbacks
        active = true
        released = false
        begin()
    }

    /** La touche est relâchée : terminer proprement et livrer le dernier segment. */
    fun release() {
        if (!active) return
        released = true
        if (listening) {
            if (ready) rec?.stopListening()
        } else {
            finish(null)
        }
    }

    fun cancel() {
        active = false
        cb = null
        handler.removeCallbacksAndMessages(null)
        listening = false
        try { rec?.cancel() } catch (_: Exception) {}
    }

    fun destroy() {
        cancel()
        try { rec?.destroy() } catch (_: Exception) {}
        rec = null
    }

    private fun begin() {
        ready = false
        listening = true
        lastPartial = ""
        val recognizer = rec ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(listener)
            rec = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            finish("Reconnaissance vocale indisponible : " + (e.message ?: "erreur"))
        }
    }

    private fun next() {
        if (active && !released) {
            handler.postDelayed({ if (active && !released) begin() }, 150)
        } else {
            finish(null)
        }
    }

    private fun finish(error: String?) {
        val c = cb
        active = false
        listening = false
        cb = null
        handler.removeCallbacksAndMessages(null)
        c?.onEnd(error)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            ready = true
            if (released) rec?.stopListening()
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) {
                lastPartial = t
                cb?.onPartial(t)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            var t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isBlank()) t = lastPartial
            if (t.isNotBlank()) cb?.onSegment(t.trim())
            cb?.onPartial("")
            next()
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (lastPartial.isNotBlank()) cb?.onSegment(lastPartial.trim())
                    cb?.onPartial("")
                    next()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    try { rec?.cancel() } catch (_: Exception) {}
                    if (active && !released) handler.postDelayed({ if (active && !released) begin() }, 400)
                    else finish(null)
                }
                SpeechRecognizer.ERROR_CLIENT -> finish(null)
                else -> finish(message(error))
            }
        }
    }

    private fun message(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Erreur audio : vérifie le microphone."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorise le microphone pour Bronet Dictée."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Réseau indisponible pour la reconnaissance vocale. Télécharge le pack de langue hors ligne dans les réglages vocaux Google."
        SpeechRecognizer.ERROR_SERVER -> "Le service vocal a refusé la demande. Réessaie."
        12 -> "Cette langue n'est pas prise en charge par la reconnaissance vocale de la tablette."
        13 -> "Pack vocal manquant : Réglages › Gestion générale › Langue › Saisie vocale Google › Langues hors connexion."
        else -> "Reconnaissance vocale interrompue (code $code)."
    }
}
