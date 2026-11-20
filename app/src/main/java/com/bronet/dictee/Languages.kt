package com.bronet.dictee

/** Langues de Bronet. `speech` = reconnaissance vocale Android, `mlkit` = modèle de traduction local. */
data class Lang(
    val key: String,
    val label: String,
    val english: String,
    val speech: String,
    val mlkit: String,
    val tts: String = speech,
) {
    override fun toString() = label

    companion object {
        val all = listOf(
            Lang("fr", "Français", "French", "fr-CA", "fr", "fr-CA"),
            Lang("fr-QC", "Français (Montréal)", "French as spoken in Montreal, Quebec", "fr-CA", "fr", "fr-CA"),
            Lang("en-CA", "Anglais (Canada)", "Canadian English", "en-CA", "en"),
            Lang("en-ON", "Anglais (Ontario)", "Canadian English as used in Ontario", "en-CA", "en"),
            Lang("en-US", "Anglais (États-Unis)", "American English", "en-US", "en"),
            Lang("en-GB", "Anglais (Royaume-Uni)", "British English", "en-GB", "en"),
            Lang("tl-Batangas", "Tagalog (Batangas)", "Tagalog as spoken in Batangas, Philippines", "fil-PH", "tl"),
            Lang("es-GT", "Espagnol (Guatemala)", "Spanish as used in Guatemala", "es-GT", "es"),
            Lang("es-ES", "Espagnol (Espagne)", "Spanish as used in Spain", "es-ES", "es"),
            Lang("es-MX", "Espagnol (Mexique)", "Mexican Spanish", "es-MX", "es"),
            Lang("de-CH", "Allemand écrit (Suisse)", "Swiss Standard German, not Swiss German dialect", "de-CH", "de"),
            Lang("de-DE", "Allemand (Allemagne)", "German as used in Germany", "de-DE", "de"),
            Lang("vi-VN", "Vietnamien", "Vietnamese", "vi-VN", "vi"),
            Lang("ms-MY", "Malais (Malaisie)", "Malay as used in Malaysia, not Indonesian", "ms-MY", "ms"),
            Lang("th-TH", "Thaï", "Thai", "th-TH", "th"),
            Lang("id-ID", "Indonésien", "Indonesian", "id-ID", "id"),
            Lang("ja-JP", "Japonais", "Japanese", "ja-JP", "ja"),
            Lang("ko-KR", "Coréen", "Korean", "ko-KR", "ko"),
            Lang("zh-CN", "Mandarin (chinois simplifié)", "Mandarin Chinese in simplified Chinese characters", "zh-CN", "zh"),
            Lang("pt-PT", "Portugais (Portugal)", "European Portuguese as used in Portugal", "pt-PT", "pt"),
            Lang("pt-BR", "Portugais (Brésil)", "Brazilian Portuguese", "pt-BR", "pt"),
            Lang("it-IT", "Italien", "Italian", "it-IT", "it"),
            Lang("ar", "Arabe standard", "Modern Standard Arabic", "ar-SA", "ar"),
        )

        fun byKey(key: String?, fallback: String): Lang =
            all.firstOrNull { it.key == key } ?: all.first { it.key == fallback }
    }
}
