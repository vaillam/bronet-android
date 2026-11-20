package com.bronet.dictee

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Lecture vocale (synthèse de la tablette). */
class Voice(ctx: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    /** @return false si la voix de cette langue n'est pas installée. */
    fun speak(text: String, lang: Lang): Boolean {
        if (!ready || text.isBlank()) return false
        val r = tts.setLanguage(Locale.forLanguageTag(lang.tts))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bronet")
        return true
    }

    fun stop() { tts.stop() }
    fun shutdown() { tts.stop(); tts.shutdown() }
}
