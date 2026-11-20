package com.bronet.dictee

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Dictée « maintenir pour parler » : écoute tant que la touche est tenue, quelle que soit
 * la longueur, et ne termine qu'au relâchement.
 *
 * - Android 13+ : session segmentée (écoute continue, un segment livré à chaque pause).
 * - Sinon, ou si le service vocal ne la prend pas en charge : relance immédiate après
 *   chaque pause ou erreur passagère, tant que la touche est tenue.
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
    private var gotSomething = false   // résultat reçu dans la session en cours
    private var failures = 0           // erreurs consécutives sans aucun résultat

    /** Mode segmenté : essayé sur Android 13+, abandonné si le service vocal le refuse. */
    private var segmented = Build.VERSION.SDK_INT >= 33

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)
    fun isActive() = active

    fun start(languageTag: String, callbacks: Callbacks) {
        cancel()
        recreate()   // moteur neuf : aucun rappel tardif de la prise précédente
        tag = languageTag
        cb = callbacks
        active = true
        released = false
        failures = 0
        begin()
    }

    /** La touche est relâchée : terminer proprement et livrer le dernier segment. */
    fun release() {
        if (!active) return
        released = true
        handler.removeCallbacksAndMessages(null)   // annule une relance en attente
        if (listening) {
            if (ready) try { rec?.stopListening() } catch (_: Exception) {}
            // Filet de sécurité : si le service ne répond pas, livrer ce qu'on a.
            handler.postDelayed({ if (active) { flushPartial(); finish(null) } }, 5000)
        } else {
            flushPartial()
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

    private fun recreate() {
        try { rec?.destroy() } catch (_: Exception) {}
        rec = null
    }

    private fun begin() {
        if (!active) return
        ready = false
        listening = true
        lastPartial = ""
        gotSomething = false
        val recognizer = rec ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(Listener(it))
            rec = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // Tolérer de longues pauses (ignoré par certains services, sans effet néfaste).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            if (segmented) {
                // Session continue jusqu'à 1 h ou jusqu'au relâchement (stopListening).
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3_600_000L)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
            }
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            listening = false
            retry(400, recreate = true, err = "Reconnaissance vocale indisponible : " + (e.message ?: "erreur"))
        }
    }

    /** Fin normale d'une session (pause, fin de segment) : relancer si la touche est tenue. */
    private fun next() {
        listening = false
        if (active && !released) {
            handler.postDelayed({ if (active && !released) begin() }, 80)
        } else {
            finish(null)
        }
    }

    /**
     * Erreur passagère : relancer tant que la touche est tenue, sans jamais abandonner
     * (seul le relâchement termine). Les relances s'espacent si les échecs se répètent.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun retry(delayMs: Long, recreate: Boolean = false, err: String? = null) {
        listening = false
        if (!active) return
        if (released) { flushPartial(); finish(null); return }
        failures++
        val wait = minOf(delayMs * (1 + failures / 3), 2000L)
        if (recreate || failures % 5 == 0) recreate()
        handler.postDelayed({ if (active && !released) begin() }, wait)
    }

    private fun flushPartial() {
        if (lastPartial.isNotBlank()) cb?.onSegment(lastPartial.trim())
        lastPartial = ""
        cb?.onPartial("")
    }

    private fun deliver(results: Bundle?) {
        var t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (t.isBlank()) t = lastPartial
        lastPartial = ""
        if (t.isNotBlank()) {
            gotSomething = true
            failures = 0
            cb?.onSegment(t.trim())
        }
        cb?.onPartial("")
    }

    private fun finish(error: String?) {
        val c = cb
        active = false
        listening = false
        cb = null
        handler.removeCallbacksAndMessages(null)
        c?.onEnd(error)
    }

    /** Rappels liés à un moteur précis : ceux d'un ancien moteur sont ignorés. */
    private inner class Listener(private val owner: SpeechRecognizer) : RecognitionListener {
        private fun mine() = rec === owner && active

        override fun onReadyForSpeech(params: Bundle?) {
            if (!mine()) return
            ready = true
            if (released) try { rec?.stopListening() } catch (_: Exception) {}
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!mine()) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) {
                lastPartial = t
                failures = 0
                cb?.onPartial(t)
            }
        }

        // Mode segmenté : un segment par pause, la session continue.
        override fun onSegmentResults(segmentResults: Bundle) {
            if (mine()) deliver(segmentResults)
        }

        override fun onEndOfSegmentedSession() {
            if (!mine()) return
            flushPartial()
            next()
        }

        override fun onResults(results: Bundle?) {
            if (!mine()) return
            deliver(results)
            next()
        }

        override fun onError(error: Int) {
            if (!mine()) return
            val keepGoing = !released
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Silence : ce n'est pas un échec, on continue d'écouter.
                    flushPartial()
                    if (gotSomething) failures = 0
                    next()
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> { flushPartial(); finish(message(error)) }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    try { rec?.cancel() } catch (_: Exception) {}
                    flushPartial(); retry(300)
                }
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> { flushPartial(); retry(300, recreate = true, err = message(error)) }
                SpeechRecognizer.ERROR_CLIENT -> {
                    // Souvent renvoyée par un service qui refuse la session segmentée.
                    if (segmented && !gotSomething && keepGoing) segmented = false
                    flushPartial(); retry(150, recreate = true)
                }
                else -> {
                    if (segmented && !gotSomething && keepGoing && failures >= 1) segmented = false
                    flushPartial(); retry(400, err = message(error))
                }
            }
        }
    }

    private fun message(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Erreur audio : vérifie le microphone."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorise le microphone pour Bronet Dictée."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Réseau indisponible pour la reconnaissance vocale. Télécharge le pack de langue hors ligne dans les réglages vocaux Google."
        SpeechRecognizer.ERROR_SERVER -> "Le service vocal a refusé la demande. Réessaie."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Le service vocal s'est arrêté. Réessaie."
        12 -> "Cette langue n'est pas prise en charge par la reconnaissance vocale de la tablette."
        13 -> "Pack vocal manquant : Réglages › Gestion générale › Langue › Saisie vocale Google › Langues hors connexion."
        else -> "Reconnaissance vocale interrompue (code $code)."
    }
}
