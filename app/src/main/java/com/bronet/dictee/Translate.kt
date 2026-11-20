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
                // Texte long : traduit morceau par morceau (ML Kit gère mal les longs passages).
                val parts = chunks(text)
                val out = ArrayList<String>(parts.size)
                fun step(i: Int) {
                    if (i == parts.size) { done(out.joinToString(" "), null); return }
                    if (parts.size > 1) status("Traduction locale ${i + 1}/${parts.size}…")
                    c.translate(parts[i])
                        .addOnSuccessListener { out.add(it.trim()); step(i + 1) }
                        .addOnFailureListener { done(null, "Traduction locale impossible : ${it.message ?: "erreur"}") }
                }
                step(0)
            }
            .addOnFailureListener { done(null, "Modèle local indisponible : vérifie le Wi-Fi pour le premier téléchargement.") }
    }
}

/** Découpe un texte en morceaux d'environ [max] caractères, aux fins de phrase si possible. */
fun chunks(text: String, max: Int = 400): List<String> {
    val sentences = text.trim().split(Regex("(?<=[.!?…。！？])\\s+")).filter { it.isNotBlank() }
    val res = ArrayList<String>()
    val cur = StringBuilder()
    fun push() { if (cur.isNotBlank()) res.add(cur.toString().trim()); cur.setLength(0) }
    for (s in sentences) {
        if (s.length > max) {
            // Phrase très longue sans ponctuation (dictée) : couper aux espaces.
            push()
            val words = s.split(Regex("\\s+"))
            for (w in words) { if (cur.length + w.length + 1 > max) push(); if (cur.isNotEmpty()) cur.append(' '); cur.append(w) }
            push()
        } else {
            if (cur.length + s.length + 1 > max) push()
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
    }
    push()
    return res.ifEmpty { listOf(text) }
}

/** Orthographe suisse de l'allemand standard. */
fun swissWrittenGerman(text: String) = text.replace("ß", "ss").replace("ẞ", "SS")
