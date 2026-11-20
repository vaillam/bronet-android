package com.bronet.dictee

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

/**
 * Dictée « maintenir pour parler » : le micro reste ouvert tant que la touche est tenue
 * et la dictée se termine immédiatement au relâchement.
 *
 * Trois façons d'écouter, de la meilleure à la plus simple ; on descend d'un cran si le
 * service vocal de la tablette refuse :
 *  - CONTINU (Android 13+) : l'application tient le micro et envoie le son au service vocal
 *    dans une seule session segmentée, qui ne se termine qu'à la fermeture du micro.
 *  - FLUX (Android 13+) : l'application tient le micro ; le service vocal ferme sa session à
 *    chaque pause et on la relance aussitôt, le son de l'intervalle étant mis en réserve.
 *  - CLASSIQUE : le service vocal ouvre lui-même le micro (bip), relancé après chaque pause.
 */
class Dictation(private val ctx: Context) {

    interface Callbacks {
        fun onPartial(text: String)
        fun onSegment(text: String)
        fun onEnd(error: String?)
        /** Problème passager pendant l'appui (affiché, l'écoute continue de réessayer). */
        fun onTrouble(message: String) {}
    }

    private enum class Way(val label: String) {
        CONTINU("micro continu, session unique"),
        FLUX("micro continu, sessions relancées"),
        CLASSIQUE("micro du service vocal"),
    }

    private val handler = Handler(Looper.getMainLooper())
    /** Journal de diagnostic (affiché à l'écran). */
    var log: (String) -> Unit = {}

    private var rec: SpeechRecognizer? = null
    private var cb: Callbacks? = null
    private var tag = "fr-CA"
    private var active = false
    private var released = false
    private var ready = false
    private var listening = false
    private var lastPartial = ""
    private var failures = 0          // erreurs consécutives sans aucun texte
    private var lastStart = 0L
    private var heard = false         // du texte a été reconnu pendant cet appui
    private var heardInWay = false    // du texte a été reconnu avec la façon actuelle

    private var way = if (Build.VERSION.SDK_INT >= 33) Way.CONTINU else Way.CLASSIQUE
    private var pump: MicPump? = null
    private var readEnd: ParcelFileDescriptor? = null

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)
    fun isActive() = active

    fun start(languageTag: String, callbacks: Callbacks) {
        cancel()
        tag = languageTag
        cb = callbacks
        active = true
        released = false
        failures = 0
        heard = false
        heardInWay = false
        log("façon d'écouter : ${way.label}")
        if (way != Way.CLASSIQUE && !startPump()) degradeTo(Way.CLASSIQUE, "micro indisponible pour l'application")
        // Laisser au service le temps de libérer la session précédente.
        val since = SystemClock.elapsedRealtime() - lastStart
        if (since < 300) handler.postDelayed({ if (active && !released) begin() }, 300 - since)
        else begin()
    }

    /** La touche est relâchée : fermer le micro et livrer aussitôt le dernier segment. */
    fun release() {
        if (!active || released) return
        released = true
        log("touche relâchée : fermeture du micro")
        handler.removeCallbacksAndMessages(null)   // annule une relance en attente
        if (listening) {
            pump?.detach()                          // fin du son → le service termine la session
            if (ready) try { rec?.stopListening() } catch (_: Exception) {}
            // Filet de sécurité : si le service ne répond pas, livrer ce qu'on a.
            handler.postDelayed({ if (active) { flushPartial(); finish(null) } }, 3000)
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
        stopPump()
    }

    fun destroy() {
        cancel()
        try { rec?.destroy() } catch (_: Exception) {}
        rec = null
    }

    // ------------------------------------------------------------------ micro de l'application

    private fun startPump(): Boolean {
        if (pump != null) return true
        val p = MicPump()
        return if (p.start()) { pump = p; log("micro ouvert par l'application"); true } else false
    }

    private fun stopPump() {
        pump?.stop()
        pump = null
        closeReadEnd()
    }

    private fun closeReadEnd() {
        try { readEnd?.close() } catch (_: Exception) {}
        readEnd = null
    }

    /** Passer à la façon d'écouter suivante (plus simple) pour la suite. */
    private fun degrade(why: String) =
        degradeTo(if (way == Way.CONTINU) Way.FLUX else Way.CLASSIQUE, why)

    private fun degradeTo(next: Way, why: String) {
        if (next == way) return
        log("$why → passage en « ${next.label} »")
        way = next
        heardInWay = false
        if (way == Way.CLASSIQUE) stopPump()
        try { rec?.destroy() } catch (_: Exception) {}
        rec = null
    }

    // ------------------------------------------------------------------ sessions

    private fun begin() {
        if (!active || released) return
        ready = false
        listening = true
        lastPartial = ""
        lastStart = SystemClock.elapsedRealtime()
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
        }
        val p = pump
        if (way != Way.CLASSIQUE && p != null && Build.VERSION.SDK_INT >= 33) {
            try {
                closeReadEnd()
                val pipe = ParcelFileDescriptor.createPipe()
                readEnd = pipe[0]
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicPump.RATE)
                if (way == Way.CONTINU)
                    intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                p.attach(pipe[1])
                log("session de reconnaissance (son fourni par l'application)")
            } catch (e: Exception) {
                degradeTo(Way.CLASSIQUE, "tuyau audio impossible (${e.message})")
                handler.post { begin() }
                return
            }
        } else {
            log("micro : démarrage de l'écoute (bip)")
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            retry(ERR_START, "Reconnaissance vocale indisponible : " + (e.message ?: "erreur"))
        }
    }

    /** Fin normale d'une session (pause) : relancer si la touche est tenue. */
    private fun next() {
        listening = false
        if (active && !released) {
            pump?.detach()   // le son continue d'être mis en réserve pour la session suivante
            handler.postDelayed({ if (active && !released) begin() }, if (way == Way.CLASSIQUE) 150L else 0L)
        } else {
            finish(null)
        }
    }

    /**
     * Erreur passagère : relancer tant que la touche est tenue, sans abandonner.
     * Les relances s'espacent (jusqu'à 2 s) ; après plusieurs échecs, le moteur est recréé.
     */
    private fun retry(code: Int, msg: String) {
        listening = false
        if (!active) return
        if (released) { flushPartial(); finish(null); return }
        pump?.detach()
        failures++
        log("nouvel essai n° $failures après erreur $code")
        if (failures >= 2) cb?.onTrouble("$msg — nouvel essai… (code $code)")
        var wait = minOf(300L * failures, 2000L)
        if (failures % 4 == 0 || code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
            try { rec?.destroy() } catch (_: Exception) {}
            rec = null
            wait = maxOf(wait, 1000L)
        }
        handler.postDelayed({ if (active && !released) begin() }, wait)
    }

    private fun flushPartial() {
        if (lastPartial.isNotBlank()) cb?.onSegment(lastPartial.trim())
        lastPartial = ""
        cb?.onPartial("")
    }

    private fun deliver(results: Bundle?) {
        var t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        log("résultat : ${t.length} caractères")
        if (t.isBlank()) t = lastPartial
        lastPartial = ""
        if (t.isNotBlank()) { failures = 0; heard = true; heardInWay = true; cb?.onSegment(t.trim()) }
        cb?.onPartial("")
    }

    private fun finish(error: String?) {
        val c = cb
        log("écoute terminée" + (error?.let { " : $it" } ?: ""))
        active = false
        listening = false
        cb = null
        handler.removeCallbacksAndMessages(null)
        stopPump()
        c?.onEnd(error)
    }

    /** Rappels liés à un moteur précis : ceux d'un ancien moteur détruit sont ignorés. */
    private inner class Listener(private val owner: SpeechRecognizer) : RecognitionListener {
        private fun mine() = rec === owner && active

        override fun onReadyForSpeech(params: Bundle?) {
            if (!mine()) return
            log("micro prêt")
            ready = true
            if (released) try { rec?.stopListening() } catch (_: Exception) {}
        }
        override fun onBeginningOfSpeech() { if (mine()) log("parole détectée") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (mine()) log("fin de parole (pause)") }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!mine()) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) {
                lastPartial = t
                failures = 0
                heard = true
                heardInWay = true
                cb?.onPartial(t)
            }
        }

        // Session segmentée : un segment par pause, la session continue.
        override fun onSegmentResults(segmentResults: Bundle) {
            if (mine()) deliver(segmentResults)
        }

        override fun onEndOfSegmentedSession() {
            if (!mine()) return
            log("fin de la session continue")
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
            log("erreur du service vocal : code $error")
            val busy = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT
            when (error) {
                // Silence : ce n'est pas un échec, on continue d'écouter.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    flushPartial(); next()
                }
                // Micro non autorisé : inutile de réessayer.
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { flushPartial(); finish(message(error)) }
                // Langue refusée : bloquant seulement si rien n'a encore été reconnu.
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    flushPartial()
                    when {
                        heard -> retry(error, message(error))
                        way != Way.CLASSIQUE -> { degradeTo(Way.CLASSIQUE, "langue refusée avec le son fourni"); retry(error, message(error)) }
                        else -> finish(message(error))
                    }
                }
                else -> {
                    flushPartial()
                    // Le service refuse peut-être notre façon d'écouter : en essayer une plus simple.
                    if (way != Way.CLASSIQUE && !heardInWay && !released) degrade("erreur $error sans aucun texte")
                    if (busy) try { rec?.cancel() } catch (_: Exception) {}
                    retry(error, if (busy) "Service vocal occupé" else message(error))
                }
            }
        }
    }

    private fun message(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Erreur audio : vérifie le microphone."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorise le microphone pour Bronet Dictée."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Réseau indisponible pour la reconnaissance vocale. Télécharge le pack de langue hors ligne dans les réglages vocaux Google."
        SpeechRecognizer.ERROR_SERVER -> "Le service vocal a refusé la demande."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Le service vocal s'est arrêté."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Trop de demandes au service vocal."
        12 -> "Cette langue n'est pas prise en charge par la reconnaissance vocale de la tablette."
        13 -> "Pack vocal manquant : Réglages › Gestion générale › Langue › Saisie vocale Google › Langues hors connexion."
        else -> "Reconnaissance vocale interrompue"
    }

    private companion object { const val ERR_START = -1 }
}

/**
 * Micro tenu par l'application pendant tout l'appui. Le son (PCM 16 bits, 16 kHz, mono)
 * est écrit vers la session de reconnaissance en cours ; entre deux sessions il est mis
 * en réserve (jusqu'à 15 s) puis envoyé à la suivante, pour ne perdre aucun mot.
 *
 * Toutes les écritures sont non bloquantes et faites par le fil du micro : si le service
 * vocal ne lit pas assez vite, le son attend en réserve, l'écran ne se fige jamais.
 */
private class MicPump {
    companion object { const val RATE = 16000 }

    private val lock = Object()
    private var out: ParcelFileDescriptor? = null
    private val pending = ArrayDeque<ByteArray>()
    private var headOffset = 0          // octets déjà envoyés du premier morceau en réserve
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        return try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return false }
            record = r
            running = true
            thread = Thread({ loop(r) }, "bronet-micro").also { it.start() }
            true
        } catch (_: Exception) { false }
    }

    private fun loop(r: AudioRecord) {
        val chunk = ByteArray(RATE * 2 / 10)   // 100 ms
        while (running) {
            val n = try { r.read(chunk, 0, chunk.size) } catch (_: Exception) { -1 }
            if (n <= 0) { if (n < 0) try { Thread.sleep(20) } catch (_: InterruptedException) {}; continue }
            synchronized(lock) {
                pending.addLast(chunk.copyOf(n))
                while (pending.size > 150) { pending.removeFirst(); headOffset = 0 }   // 15 s au plus
                flush()
            }
        }
    }

    /** Envoie la réserve sans jamais bloquer (appelé sous verrou). */
    private fun flush() {
        val o = out ?: return
        while (pending.isNotEmpty()) {
            val head = pending.first()
            val written = try {
                Os.write(o.fileDescriptor, head, headOffset, head.size - headOffset)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) return      // le service ne suit pas : on attend
                closeQuietly(o); out = null; return            // session fermée
            } catch (_: Exception) { closeQuietly(o); out = null; return }
            headOffset += written
            if (headOffset >= head.size) { pending.removeFirst(); headOffset = 0 } else return
        }
    }

    /** Brancher la session suivante ; la réserve lui sera envoyée par le fil du micro. */
    fun attach(writeEnd: ParcelFileDescriptor) {
        try {
            Os.fcntlInt(writeEnd.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
        } catch (_: Exception) {}
        synchronized(lock) {
            out?.let { closeQuietly(it) }
            out = writeEnd
        }
    }

    /** Fermer le son de la session en cours (le micro reste ouvert, la suite est mise en réserve). */
    fun detach() {
        synchronized(lock) { out?.let { closeQuietly(it) }; out = null }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}     // débloque la lecture en cours
        try { thread?.join(300) } catch (_: Exception) {}
        thread = null
        try { record?.release() } catch (_: Exception) {}
        record = null
        synchronized(lock) { out?.let { closeQuietly(it) }; out = null; pending.clear(); headOffset = 0 }
    }

    private fun closeQuietly(o: ParcelFileDescriptor) { try { o.close() } catch (_: Exception) {} }
}
