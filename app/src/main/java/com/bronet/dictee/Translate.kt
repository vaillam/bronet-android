package com.bronet.dictee

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/** Traduction locale (ML Kit) : un modèle par langue, téléchargé une seule fois. */
object LocalTranslator {
    private val cache = HashMap<String, Translator>()

    private fun client(src: String, tgt: String): Translator = cache.getOrPut("$src-$tgt") {
        Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
        )
    }

    fun prepare(src: Lang, tgt: Lang, status: (String) -> Unit, done: (String?) -> Unit) {
        if (src.mlkit == tgt.mlkit) { done(null); return }
        status("Téléchargement des modèles locaux ${src.label} ↔ ${tgt.label}…")
        val forward = client(src.mlkit, tgt.mlkit)
        val back = client(tgt.mlkit, src.mlkit)
        val cond = DownloadConditions.Builder().build()
        forward.downloadModelIfNeeded(cond)
            .addOnSuccessListener {
                back.downloadModelIfNeeded(cond)
                    .addOnSuccessListener { done(null) }
                    .addOnFailureListener { done("Téléchargement impossible : vérifie le Wi-Fi.") }
            }
            .addOnFailureListener { done("Téléchargement impossible : vérifie le Wi-Fi.") }
    }

    fun translate(text: String, src: Lang, tgt: Lang, status: (String) -> Unit, done: (String?, String?) -> Unit) {
        if (src.mlkit == tgt.mlkit) { done(text, null); return }
        val c = client(src.mlkit, tgt.mlkit)
        status("Préparation du modèle local (téléchargement la première fois)…")
        c.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                c.translate(text)
                    .addOnSuccessListener { done(it, null) }
                    .addOnFailureListener { done(null, "Traduction locale impossible : ${it.message ?: "erreur"}") }
            }
            .addOnFailureListener { done(null, "Modèle local indisponible : vérifie le Wi-Fi pour le premier téléchargement.") }
    }
}

/** Orthographe suisse de l'allemand standard. */
fun swissWrittenGerman(text: String) = text.replace("ß", "ss").replace("ẞ", "SS")
