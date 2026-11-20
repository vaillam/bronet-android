package com.bronet.dictee

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

enum class Mode { DICT, TRANS, ASK, CONV_A, CONV_B }

class MainActivity : Activity() {

    private val BLUE = Color.parseColor("#1F5FBF")
    private val GREEN = Color.parseColor("#2E7D32")
    private val PURPLE = Color.parseColor("#6A3FB5")
    private val RED = Color.parseColor("#C62828")
    private val GREY = Color.parseColor("#5F6B7A")

    private lateinit var prefs: SharedPreferences
    private lateinit var speech: Dictation
    private lateinit var voice: Voice

    private lateinit var dictLang: Lang
    private lateinit var trSrc: Lang
    private lateinit var trTgt: Lang
    private lateinit var convA: Lang
    private lateinit var convB: Lang
    private var gptMode = false

    private lateinit var status: TextView
    private lateinit var partial: TextView
    private lateinit var dictBox: EditText
    private lateinit var outLabel: TextView
    private lateinit var outBox: TextView
    private lateinit var mainScroll: ScrollView
    private lateinit var mainBar: LinearLayout
    private lateinit var convScroll: LinearLayout
    private lateinit var convBar: LinearLayout
    private lateinit var convLog: LinearLayout
    private lateinit var convLogScroll: ScrollView
    private lateinit var convSpeak: CheckBox
    private lateinit var autoCopy: CheckBox
    private lateinit var speakResult: CheckBox
    private var lastOutput = ""
    private var lastOutputLang: Lang? = null

    private var curMode: Mode? = null
    private var curButton: Button? = null
    private var curLabel = ""
    private var curColor = BLUE
    private var holdStart = 0L
    private var holding = false   // la touche est physiquement tenue
    private var holdCancelled = false   // appui annulé par le système, écoute maintenue
    private val buf = StringBuilder()

    private var accountDialog: AlertDialog? = null
    private var accountInfo: TextView? = null

    // ------------------------------------------------------------ cycle de vie
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("bronet", Context.MODE_PRIVATE)
        ChatGpt.init(this)
        speech = Dictation(this)
        voice = Voice(this)
        dictLang = Lang.byKey(prefs.getString("dict", null), "fr")
        trSrc = Lang.byKey(prefs.getString("trSrc", null), "fr")
        trTgt = Lang.byKey(prefs.getString("trTgt", null), "en-CA")
        convA = Lang.byKey(prefs.getString("convA", null), "fr")
        convB = Lang.byKey(prefs.getString("convB", null), "en-CA")
        gptMode = prefs.getBoolean("gpt", false)
        buildUi()
        if (!speech.available()) setStatus("Reconnaissance vocale absente : installe/active « Services vocaux Google ».")
        else setStatus("Prêt. Maintiens une touche en bas de l'écran pour parler, relâche pour terminer.")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onPause() {
        super.onPause()
        speech.cancel()
        voice.stop()
        resetHoldUi()
    }

    override fun onDestroy() {
        speech.destroy()
        voice.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) setStatus("Micro autorisé. Maintiens une touche pour parler.")
        else setStatus("Micro refusé : Réglages › Applications › Bronet Dictée › Autorisations › Microphone.")
    }

    // ------------------------------------------------------------ outils d'interface
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int = 14, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun label(text: String, size: Float = 15f, bold: Boolean = false, color: Int = Color.parseColor("#1B2430")) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(text: String, color: Int = BLUE, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 15f
            setTextColor(Color.WHITE); background = rounded(color)
            minHeight = dp(52); setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener { onClick() }
        }

    private fun card(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 16, Color.parseColor("#DDE3EC"))
        setPadding(dp(14), dp(12), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        views.forEach { addView(it) }
    }

    private fun row(vararg views: View, weights: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEach {
            addView(it, if (weights) LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
            else LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun spinner(current: Lang, onChange: (Lang) -> Unit): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, Lang.all).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(Lang.all.indexOf(current))
        var first = true
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; if (Lang.all[pos] == current) return }
                onChange(Lang.all[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setSpinner(s: Spinner, l: Lang) = s.setSelection(Lang.all.indexOf(l))

    private fun ui(block: () -> Unit) = runOnUiThread(block)
    private fun bg(block: () -> Unit) = Thread(block).start()
    private fun setStatus(t: String) { if (::status.isInitialized) status.text = t }

    /** Touche « maintenir pour parler » : appui = écoute, relâchement = résultat. */
    private fun holdKey(text: String, color: Int, mode: Mode): Button =
        Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE)
            background = rounded(color, 18); minHeight = dp(76)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        // Après un appui interrompu par la tablette, on touche de nouveau pour terminer.
                        if (curMode == mode && holdCancelled) holdCancelled = false
                        else beginHold(mode, this, text, color)
                        true
                    }
                    MotionEvent.ACTION_UP -> { endHold(mode); true }
                    MotionEvent.ACTION_CANCEL -> {
                        // La tablette a « volé » l'appui (geste système, bord d'écran…) alors que
                        // le doigt est peut-être encore posé : on continue d'écouter.
                        if (curMode == mode) {
                            holdCancelled = true
                            this.text = "● J'écoute…\ntouche pour terminer"
                            setStatus("● J'écoute toujours (appui interrompu par la tablette) : touche la touche pour terminer.")
                        }
                        true
                    }
                    else -> true
                }
            }
        }

    // ------------------------------------------------------------ construction de l'écran
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ----- écran principal
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        col.addView(label("Bronet Dictée", 26f, true))
        val rev = try { packageManager.getPackageInfo(packageName, 0).versionName?.substringBefore('-') } catch (_: Exception) { null }
        col.addView(label("Android · révision ${rev ?: "?"}", 13f, color = GREY))

        val gpt = Switch(this).apply {
            text = "Mode GPT (traduction par ChatGPT)"; isChecked = gptMode; textSize = 15f
            setOnCheckedChangeListener { _, on ->
                gptMode = on; prefs.edit().putBoolean("gpt", on).apply()
                if (on && !ChatGpt.sharing()) { setStatus("Connecte d'abord ton compte ChatGPT."); showAccount() }
            }
        }
        col.addView(card(
            row(
                button("Compte ChatGPT", PURPLE) { showAccount() },
                button("Conversation", GREEN) { showConversation(true) },
            ), gpt.also { it.setPadding(0, dp(10), 0, 0) },
        ))

        // langues
        val dictSp = spinner(dictLang) { dictLang = it; prefs.edit().putString("dict", it.key).apply() }
        val srcSp = spinner(trSrc) { trSrc = it; prefs.edit().putString("trSrc", it.key).apply() }
        val tgtSp = spinner(trTgt) { trTgt = it; prefs.edit().putString("trTgt", it.key).apply() }
        val swap = button("⇄", GREY) {
            val a = trSrc; trSrc = trTgt; trTgt = a
            setSpinner(srcSp, trSrc); setSpinner(tgtSp, trTgt)
            prefs.edit().putString("trSrc", trSrc.key).putString("trTgt", trTgt.key).apply()
        }
        col.addView(card(
            label("1 · Dictée — langue parlée", 14f, true),
            dictSp,
            label("2 · Traduction — direction", 14f, true).apply { setPadding(0, dp(10), 0, 0) },
            row(srcSp, tgtSp, swap.also { it.minWidth = dp(64) }, weights = false).also {
                (srcSp.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
                (tgtSp.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
            },
            button("Préparer les modèles locaux de cette paire", GREY) {
                LocalTranslator.prepare(trSrc, trTgt, ::setStatus) { err ->
                    ui { setStatus(err ?: "Modèles locaux prêts : ${trSrc.label} ↔ ${trTgt.label}.") }
                }
            },
        ))

        // dictée
        partial = label("", 14f, color = GREY).apply { setTypeface(typeface, Typeface.ITALIC) }
        dictBox = EditText(this).apply {
            hint = "Le texte dicté apparaît ici (tu peux aussi le taper au clavier virtuel)."
            minLines = 5; gravity = Gravity.TOP; textSize = 18f
            background = rounded(Color.parseColor("#F4F6FA"), 10, Color.parseColor("#DDE3EC"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        autoCopy = CheckBox(this).apply { text = "Copier automatiquement (pour coller ailleurs)"; isChecked = prefs.getBoolean("autocopy", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("autocopy", v).apply() } }
        col.addView(card(
            label("Dictée", 16f, true), partial, dictBox, autoCopy,
            row(
                button("Traduire ce texte", GREEN) { translateBox() },
                button("Copier", BLUE) { copy(dictBox.text.toString()) },
                button("Partager", BLUE) { share(dictBox.text.toString()) },
                button("Nouveau", GREY) { dictBox.setText(""); outBox.text = ""; lastOutput = "" },
            ),
        ))

        outLabel = label("Traduction / réponse", 16f, true)
        outBox = label("", 20f).apply { setTextIsSelectable(true); minHeight = dp(90) }
        speakResult = CheckBox(this).apply { text = "Lire à voix haute"; isChecked = prefs.getBoolean("speak", false)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("speak", v).apply() } }
        col.addView(card(
            outLabel, outBox, speakResult,
            row(
                button("Écouter", PURPLE) { speakLast() },
                button("Copier", BLUE) { copy(lastOutput) },
                button("Partager", BLUE) { share(lastOutput) },
            ),
        ))

        mainScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(FrameLayout(this@MainActivity).apply {
                addView(col, FrameLayout.LayoutParams(-1, -2))
            })
        }

        // ----- barre de touches (toujours visible, au-dessus du clavier virtuel)
        mainBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(Color.parseColor("#E6EAF2"))
        }
        // Pas de geste « retour » depuis les bords sur la barre de touches.
        if (Build.VERSION.SDK_INT >= 29) mainBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
        }
        listOf(
            Triple("1 · DICTER\nmaintenir et parler", BLUE, Mode.DICT),
            Triple("2 · TRADUIRE\nmaintenir et parler", GREEN, Mode.TRANS),
            Triple("3 · DEMANDER À L'IA\nmaintenir et parler", PURPLE, Mode.ASK),
        ).forEach { (t, c, m) ->
            mainBar.addView(holdKey(t, c, m), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        }

        // ----- conversation
        convLog = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        convLogScroll = ScrollView(this).apply { addView(convLog) }
        convSpeak = CheckBox(this).apply { text = "Lire la traduction à voix haute"; isChecked = true }
        val aSp = spinner(convA) { convA = it; prefs.edit().putString("convA", it.key).apply(); refreshConvBar() }
        val bSp = spinner(convB) { convB = it; prefs.edit().putString("convB", it.key).apply(); refreshConvBar() }
        convScroll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(8)); visibility = View.GONE
            addView(row(label("Conversation", 24f, true), button("← Retour", GREY) { showConversation(false) }))
            addView(card(
                row(label("Personne A", 13f, color = GREY), label("Personne B", 13f, color = GREY)),
                row(aSp, bSp), convSpeak,
                row(button("Effacer l'historique", GREY) { convLog.removeAllViews() },
                    button("Préparer les modèles locaux", GREY) {
                        LocalTranslator.prepare(convA, convB, ::setStatus) { e -> ui { setStatus(e ?: "Modèles locaux prêts.") } }
                    }),
            ))
            addView(convLogScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        convBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
            setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(Color.parseColor("#E6EAF2"))
        }
        refreshConvBar()

        status = label("", 14f, color = Color.WHITE).apply {
            background = rounded(Color.parseColor("#2B3544"), 0); setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        root.addView(mainScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(convScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(mainBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(convBar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun refreshConvBar() {
        convBar.removeAllViews()
        convBar.addView(holdKey("🎤 ${convA.label}\nmaintenir et parler", BLUE, Mode.CONV_A),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        convBar.addView(holdKey("🎤 ${convB.label}\nmaintenir et parler", GREEN, Mode.CONV_B),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
    }

    private fun showConversation(on: Boolean) {
        speech.cancel(); resetHoldUi()
        mainScroll.visibility = if (on) View.GONE else View.VISIBLE
        mainBar.visibility = if (on) View.GONE else View.VISIBLE
        convScroll.visibility = if (on) View.VISIBLE else View.GONE
        convBar.visibility = if (on) View.VISIBLE else View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (convScroll.visibility == View.VISIBLE) showConversation(false) else super.onBackPressed()
    }

    // ------------------------------------------------------------ touches « maintenir pour parler »
    private fun beginHold(mode: Mode, b: Button, text: String, color: Int) {
        if (curMode != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!speech.available()) { setStatus("Reconnaissance vocale absente : active « Services vocaux Google »."); return }
        if ((mode == Mode.ASK || (mode == Mode.TRANS && gptMode)) && !ChatGpt.sharing()) {
            if (mode == Mode.ASK) { setStatus("Demander à l'IA exige la connexion ChatGPT."); showAccount(); return }
        }
        voice.stop()
        curMode = mode; curButton = b; curLabel = text; curColor = color
        holdStart = System.currentTimeMillis(); buf.setLength(0)
        b.text = "● J'écoute…\nrelâche pour terminer"
        b.background = rounded(RED, 18)
        setStatus("● J'écoute… relâche la touche pour terminer.")
        val tag = when (mode) {
            Mode.DICT, Mode.ASK -> dictLang.speech
            Mode.TRANS -> trSrc.speech
            Mode.CONV_A -> convA.speech
            Mode.CONV_B -> convB.speech
        }
        holding = true
        speech.start(tag, object : Dictation.Callbacks {
            override fun onPartial(text: String) = ui {
                partial.text = if (text.isEmpty()) "" else "… $text"
                if (text.isNotEmpty() && holding) setStatus("● J'écoute… relâche la touche pour terminer.")
            }
            override fun onTrouble(message: String) = ui { if (holding && curMode == mode) setStatus("⚠ $message") }
            override fun onSegment(text: String) = ui {
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(text)
                if (mode == Mode.DICT) appendDictation(text)
            }
            override fun onEnd(error: String?) = ui {
                if (curMode != mode) return@ui
                if (holding) {
                    // Règle absolue : tant que le doigt est sur la touche, jamais de traduction.
                    // Le service vocal s'est arrêté : on relance l'écoute en gardant le texte.
                    val cb = this
                    if (error != null) setStatus("⚠ $error — j'écoute encore, relâche la touche pour terminer.")
                    mainBar.postDelayed({ if (holding && curMode == mode) speech.start(tag, cb) },
                        if (error == null) 0L else 1500L)
                } else finishHold(mode, error)
            }
        })
    }

    private fun endHold(mode: Mode) {
        if (curMode != mode) return
        holding = false
        if (System.currentTimeMillis() - holdStart < 350) {
            speech.cancel(); resetHoldUi(); setStatus("Touche relâchée trop vite : maintiens-la pendant que tu parles."); return
        }
        val secs = (System.currentTimeMillis() - holdStart) / 100 / 10.0
        Toast.makeText(this, "Fin de l'écoute : touche relâchée après ${"%.1f".format(secs)} s", Toast.LENGTH_SHORT).show()
        setStatus("Traitement…")
        if (speech.isActive()) speech.release() else finishHold(mode, null)
    }

    private fun resetHoldUi() {
        curButton?.let { it.text = curLabel; it.background = rounded(curColor, 18) }
        curMode = null; curButton = null; holding = false; holdCancelled = false
        if (::partial.isInitialized) partial.text = ""
    }

    private fun finishHold(mode: Mode, error: String?) {
        if (curMode != mode) return
        resetHoldUi()
        val text = buf.toString().trim()
        if (text.isEmpty()) { setStatus(error ?: "Aucune parole reconnue. Réessaie plus près du micro."); return }
        if (error != null) setStatus(error)
        when (mode) {
            Mode.DICT -> {
                if (autoCopy.isChecked) copy(dictBox.text.toString(), quiet = true)
                setStatus("Dictée terminée" + if (autoCopy.isChecked) " et copiée." else ".")
            }
            Mode.TRANS -> {
                appendDictation(text)
                runTranslation(text, trSrc, trTgt) { out ->
                    showOutput("Traduction · ${trTgt.label}", out, trTgt)
                    if (autoCopy.isChecked) copy(out, quiet = true)
                    if (speakResult.isChecked) speakLast()
                }
            }
            Mode.ASK -> {
                setStatus("ChatGPT réfléchit…")
                bg {
                    try {
                        val answer = ChatGpt.ask(text)
                        ui {
                            showOutput("Réponse de l'IA", answer, dictLang)
                            if (autoCopy.isChecked) copy(answer, quiet = true)
                            setStatus("Réponse reçue.")
                            if (speakResult.isChecked) speakLast()
                        }
                    } catch (e: ChatGpt.Err) { ui { setStatus(e.message ?: "Erreur ChatGPT.") } }
                }
            }
            Mode.CONV_A, Mode.CONV_B -> {
                val from = if (mode == Mode.CONV_A) convA else convB
                val to = if (mode == Mode.CONV_A) convB else convA
                runTranslation(text, from, to) { out ->
                    addBubble(text, out, mode == Mode.CONV_A)
                    if (convSpeak.isChecked && !voice.speak(out, to))
                        setStatus("Voix « ${to.label} » non installée (Réglages › Synthèse vocale).")
                    else setStatus("Prêt.")
                }
            }
        }
    }

    // ------------------------------------------------------------ traduction
    private fun runTranslation(text: String, src: Lang, tgt: Lang, done: (String) -> Unit) {
        fun finalize(s: String) = if (tgt.key == "de-CH") swissWrittenGerman(s) else s
        fun local(prefix: String = "") {
            LocalTranslator.translate(text, src, tgt, { m -> ui { setStatus(m) } }) { out, err ->
                ui {
                    if (out != null) { setStatus(prefix.ifEmpty { "Traduit (local)." }); done(finalize(out)) }
                    else setStatus(err ?: "Traduction impossible.")
                }
            }
        }
        if (gptMode && ChatGpt.sharing()) {
            setStatus("Traduction ChatGPT…")
            bg {
                try {
                    val out = ChatGpt.translate(text, src, tgt)
                    ui { setStatus("Traduit (ChatGPT)."); done(finalize(out)) }
                } catch (e: ChatGpt.Err) {
                    ui { setStatus((e.message ?: "Erreur ChatGPT.") + " Traduction locale à la place…"); local("Traduit (local, repli).") }
                }
            }
        } else local()
    }

    private fun translateBox() {
        val t = dictBox.text.toString().trim()
        if (t.isEmpty()) { setStatus("Rien à traduire : dicte ou tape un texte."); return }
        runTranslation(t, trSrc, trTgt) { out ->
            showOutput("Traduction · ${trTgt.label}", out, trTgt)
            if (autoCopy.isChecked) copy(out, quiet = true)
            if (speakResult.isChecked) speakLast()
        }
    }

    // ------------------------------------------------------------ résultats
    private fun appendDictation(text: String) {
        val cur = dictBox.text.toString()
        dictBox.setText(if (cur.isBlank()) text else cur.trimEnd() + " " + text)
        dictBox.setSelection(dictBox.text.length)
    }

    private fun showOutput(title: String, text: String, lang: Lang) {
        outLabel.text = title; outBox.text = text; lastOutput = text; lastOutputLang = lang
        mainScroll.post { mainScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun speakLast() {
        val l = lastOutputLang ?: return
        if (lastOutput.isEmpty()) return
        if (!voice.speak(lastOutput, l)) setStatus("Voix « ${l.label} » non installée (Réglages › Synthèse vocale).")
    }

    private fun addBubble(original: String, translated: String, left: Boolean) {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (left) Color.parseColor("#DCE8FB") else Color.parseColor("#DDF1DE"), 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(label(original, 14f, color = GREY))
            addView(label(translated, 20f, true))
        }
        convLog.addView(b, LinearLayout.LayoutParams(0.7f.let { (resources.displayMetrics.widthPixels * it).toInt() }, -2).apply {
            gravity = if (left) Gravity.START else Gravity.END; bottomMargin = dp(8)
        })
        convLogScroll.post { convLogScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copy(text: String, quiet: Boolean = false) {
        if (text.isBlank()) return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Bronet", text))
        if (!quiet) Toast.makeText(this, "Copié", Toast.LENGTH_SHORT).show()
    }

    private fun share(text: String) {
        if (text.isBlank()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "Partager / enregistrer"))
    }

    // ------------------------------------------------------------ compte ChatGPT
    private fun accountText(): String =
        if (!ChatGpt.connected()) "Non connecté."
        else "Connecté : ${ChatGpt.email().ifEmpty { "compte ChatGPT" }}\n" +
            "Forfait autorisé : ${if (ChatGpt.sharing()) "oui" else "non — touche « Autoriser / réparer l'accès »"}\n" +
            "Modèle : ${ChatGpt.model().ifEmpty { "automatique" }}"

    private fun showAccount() {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(6)) }
        val info = label(accountText(), 15f)
        accountInfo = info
        c.addView(info)
        fun refresh() = ui { info.text = accountText() }
        fun gptTask(start: String, work: () -> String) {
            setStatus(start)
            bg { val msg = try { work() } catch (e: ChatGpt.Err) { e.message ?: "Erreur ChatGPT." }
                ui { setStatus(msg); refresh() } }
        }
        fun signIn(force: Boolean) = gptTask("Navigateur ouvert : termine la connexion puis reviens ici…") {
            val mail = ChatGpt.signIn({ url ->
                ui { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {
                    setStatus("Aucun navigateur trouvé.") } }
            }, forceConsent = force)
            "Connecté : $mail"
        }
        fun add(t: String, color: Int, f: () -> Unit) = c.addView(button(t, color, f),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        add("Continuer avec ChatGPT", PURPLE) { signIn(false) }
        add("Autoriser / réparer l'accès", PURPLE) { signIn(true) }
        add("Actualiser les modèles", BLUE) {
            setStatus("Chargement des modèles…")
            bg {
                try {
                    val models = ChatGpt.listModels()
                    ui {
                        AlertDialog.Builder(this).setTitle("Modèle ChatGPT")
                            .setItems(models.map { it.second }.toTypedArray()) { _, i ->
                                ChatGpt.selectModel(models[i].first); refresh(); setStatus("Modèle : ${models[i].second}")
                            }.show()
                    }
                } catch (e: ChatGpt.Err) { ui { setStatus(e.message ?: "Erreur ChatGPT.") } }
            }
        }
        add("Tester ChatGPT (sans micro)", GREEN) {
            gptTask("Test ChatGPT…") { "Test réussi : " + ChatGpt.ask("Réponds seulement : OK").take(80) }
        }
        add("Gérer l'utilisation dans ChatGPT", GREY) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/#settings/Usage")))
        }
        add("Déconnecter", RED) { gptTask("Déconnexion…") { ChatGpt.logout() } }
        accountDialog = AlertDialog.Builder(this).setTitle("Compte ChatGPT")
            .setView(ScrollView(this).apply { addView(c) }).setPositiveButton("Fermer", null).show()
    }
}
