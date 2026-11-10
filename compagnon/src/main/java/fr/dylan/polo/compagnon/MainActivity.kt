package fr.dylan.polo.compagnon

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Écran du compagnon : état de la liaison avec l'autoradio, réglages en 3 étapes,
 * et aperçu en direct des notifications envoyées.
 */
class MainActivity : Activity() {

    // couleurs reprises de Polo Radio
    private val BG = Color.parseColor("#FBF9F5")
    private val CARD = Color.WHITE
    private val LINE = Color.parseColor("#ECE6DA")
    private val INK = Color.parseColor("#2B2A28")
    private val MUTED = Color.parseColor("#8A8478")
    private val OK = Color.parseColor("#2E9D5B")
    private val WARN = Color.parseColor("#E0533A")
    private val ACCENT = Color.parseColor("#C2417A")
    private val PALETTE = intArrayOf(
        Color.parseColor("#C2417A"), Color.parseColor("#2B1055"), Color.parseColor("#E8A87C"),
        Color.parseColor("#2E7D4F"), Color.parseColor("#3D6FB6"), Color.parseColor("#C0603F")
    )

    private lateinit var linkDot: View
    private lateinit var linkTitle: TextView
    private lateinit var linkSub: TextView
    private lateinit var steps: LinearLayout
    private lateinit var header: TextView
    private lateinit var list: LinearLayout
    private var editingCode = false

    private val notifListener = NotifStore.Listener { _, _ -> runOnUiThread { refreshList() } }
    private val statusListener = { runOnUiThread { refreshStatus() }; Unit }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // bord à bord : le dégradé passe sous la barre d'état
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(0, 0, 0, dp(28))
            clipChildren = false
        }
        val heroView = hero()
        root.addView(heroView)
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            heroView.setPadding(dp(22), ins.systemWindowInsetTop + dp(20), dp(22), dp(48))
            @Suppress("DEPRECATION")
            root.setPadding(0, 0, 0, ins.systemWindowInsetBottom + dp(24))
            ins
        }

        // colonne centrée, largeur limitée (lisible aussi en paysage / tablette)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(560)), -2).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(body)

        body.addView(linkCard())
        body.addView(sectionTitle("Réglages"))
        steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(card(steps, pad = 0))
        header = sectionTitle("")
        body.addView(header)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(BG) })
    }

    override fun onResume() {
        super.onResume()
        NotifStore.addListener(notifListener)
        LinkServer.addStatusListener(statusListener)
        LinkServer.startBluetooth(this)
        refreshStatus()
        refreshList()
    }

    override fun onPause() {
        NotifStore.removeListener(notifListener)
        LinkServer.removeStatusListener(statusListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LinkServer.startBluetooth(this)
        if (requestCode == 8) Thread { try { LinkServer.sendInfo() } catch (e: Exception) {} }.start()
        refreshStatus()
    }

    // ===================== en-tête =====================

    private fun hero(): View {
        // bandeau compact : icône à gauche, titre + sous-titre à droite
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(48), dp(22), dp(48))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#2B1055"), Color.parseColor("#C2417A"), Color.parseColor("#FFB27B"))
            ).apply { cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat()) }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels - dp(44), dp(528)), -2)
        }
        inner.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(14) }
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        col.addView(text("Polo Compagnon", 24f, Color.WHITE, bold = true))
        col.addView(text("Tes notifications sur l'autoradio de la Polo", 14f, Color.parseColor("#F3E6F0")).apply { setPadding(0, dp(2), 0, 0) })
        inner.addView(col)
        box.addView(inner)
        return box
    }

    // ===================== liaison =====================

    private fun linkCard(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        linkDot = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(14) } }
        row.addView(linkDot)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        linkTitle = text("", 18f, INK, bold = true)
        linkSub = text("", 14f, MUTED)
        col.addView(linkTitle); col.addView(linkSub)
        row.addView(col)
        return card(row).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = -dp(30)
            elevation = dp(4).toFloat()
        }
    }

    private fun refreshStatus() {
        val via = LinkServer.via
        val code = LinkServer.code(this)
        val (color, title, sub) = when {
            !hasAccess() -> Triple(WARN, "Pas encore prêt", "Active l'accès aux notifications ci-dessous")
            code.length != 6 -> Triple(WARN, "Code d'appairage manquant", "Tape le code affiché sur l'autoradio")
            via != null -> Triple(OK, "Connecté à l'autoradio", (if (via == "wifi") "📶 Wi-Fi" else "🔵 Bluetooth") + " · " + (LinkServer.radioName ?: "Polo Radio"))
            System.currentTimeMillis() - LinkServer.lastDenied < 120_000 -> Triple(WARN, "Code refusé", "Vérifie le code dans Réglages → Bluetooth de l'autoradio")
            else -> Triple(Color.parseColor("#E8A87C"), "En attente de l'autoradio…", "Allume la voiture : la liaison se fait toute seule")
        }
        linkDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        linkTitle.text = title
        linkSub.text = sub
        buildSteps()
    }

    // ===================== réglages en 3 étapes =====================

    private fun buildSteps() {
        steps.removeAllViews()
        val access = hasAccess()
        steps.addView(step(1, "Accès aux notifications", if (access) "Activé" else "Pour lire les notifications du téléphone",
            access, if (access) null else "Activer" to { openAccessSettings() }))
        divider()
        val btOk = LinkServer.hasBtPermission(this)
        steps.addView(step(2, "Bluetooth", if (btOk) "Autorisé · utilisé si le partage de connexion est coupé" else "Pour la liaison sans partage de connexion",
            btOk, if (btOk) null else "Autoriser" to {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 7)
            }))
        divider()
        val code = LinkServer.code(this)
        val has = code.length == 6
        steps.addView(step(3, "Code d'appairage",
            if (has) "Enregistré · ••••" + code.takeLast(2) else "Affiché sur l'autoradio : Réglages → Bluetooth",
            has && !editingCode, if (has && !editingCode) "Modifier" to { editingCode = true; buildSteps() } else null))
        if (!has || editingCode) steps.addView(codeEntry())
        divider()
        val wanted = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR, Manifest.permission.SEND_SMS)
        val infoOk = wanted.all { PhoneInfo.has(this, it) }
        steps.addView(step(4, "Contacts, agenda et messages",
            if (infoOk) "Autorisé · anniversaires, rendez-vous et messages dictés" else "Pour les anniversaires, ton agenda et envoyer un message à la voix",
            infoOk, if (infoOk) null else "Autoriser" to { requestPermissions(wanted, 8) }))
    }

    private fun divider() = steps.addView(View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(64) }
    })

    private fun step(n: Int, title: String, sub: String, done: Boolean, action: Pair<String, () -> Unit>?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        row.addView(text(if (done) "✓" else n.toString(), 16f, if (done) Color.WHITE else ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { rightMargin = dp(14) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (done) setColor(OK) else { setColor(Color.parseColor("#FBEFF4")); setStroke(dp(2), ACCENT) }
            }
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        col.addView(text(title, 16f, INK, bold = true))
        col.addView(text(sub, 13f, MUTED))
        row.addView(col)
        if (action != null) row.addView(pill(action.first, filled = !done) { action.second() })
        return row
    }

    private fun codeEntry(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(64), 0, dp(16), dp(16))
        }
        val field = EditText(this).apply {
            hint = "123456"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            textSize = 22f
            letterSpacing = 0.35f
            gravity = Gravity.CENTER
            setTextColor(INK)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(BG); setStroke(dp(2), LINE) }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) }
        }
        row.addView(field)
        row.addView(pill("Enregistrer", filled = true) {
            val c = field.text.toString().trim()
            if (c.length != 6) { field.error = "6 chiffres"; return@pill }
            LinkServer.setCode(this, c)
            editingCode = false
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
            refreshStatus()
        })
        return row
    }

    // ===================== aperçu des notifications =====================

    private fun refreshList() {
        val items = NotifStore.all()
        header.text = if (items.isEmpty()) "Notifications" else "Notifications · ${items.size}"
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(card(text(if (hasAccess()) "🔕  Rien pour l'instant. Les nouvelles notifications apparaîtront ici."
                else "Active l'accès aux notifications pour les voir ici.", 15f, MUTED)))
            return
        }
        for (item in items) list.addView(notifCard(item))
    }

    private fun notifCard(n: NotifStore.Item): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val color = PALETTE[Math.abs(n.pkg.hashCode()) % PALETTE.size]
        top.addView(text(n.app.take(1).uppercase(), 17f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        col.addView(text(n.app + (if (n.isMsg) "  ·  💬 message" else ""), 13f, MUTED))
        col.addView(text(n.title, 16f, INK, bold = true).apply { maxLines = 1 })
        top.addView(col)
        top.addView(text(DateFormat.getTimeFormat(this).format(n.time), 13f, MUTED))
        box.addView(top)

        val lines = if (n.isMsg && n.conv.isNotEmpty()) n.conv.takeLast(3).map { (f, t) -> if (f == n.title) t else "$f : $t" } else listOf(n.text)
        val body = lines.filter { it.isNotBlank() }.joinToString("\n")
        if (body.isNotEmpty()) box.addView(text(body, 15f, INK).apply { setPadding(dp(50), dp(6), 0, 0); maxLines = 5 })

        if (n.isMsg) {
            if (n.reply != null && n.replies.isNotEmpty()) {
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(50), dp(10), 0, 0) }
                for (r in n.replies) chips.addView(chip(r))
                box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
            } else {
                box.addView(text(if (n.reply != null) "🎤  Réponse vocale possible" else "Pas de réponse possible pour cette appli", 13f,
                    if (n.reply != null) OK else MUTED).apply { setPadding(dp(50), dp(8), 0, 0) })
            }
        }
        return card(box)
    }

    // ===================== briques =====================

    private fun sectionTitle(t: String) = text(t, 13f, MUTED, bold = true).apply {
        letterSpacing = 0.08f
        isAllCaps = true
        setPadding(dp(6), dp(22), 0, dp(8))
    }

    private fun card(content: View, pad: Int = 16): FrameLayout = FrameLayout(this).apply {
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(CARD); setStroke(dp(1), LINE) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        addView(content)
    }

    private fun pill(label: String, filled: Boolean, onTap: () -> Unit) = text(label, 14f, if (filled) Color.WHITE else ACCENT, bold = true).apply {
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (filled) setColor(ACCENT) else { setColor(Color.TRANSPARENT); setStroke(dp(2), ACCENT) }
        }
        isClickable = true
        setOnClickListener { onTap() }
    }

    private fun chip(label: String) = text(label, 14f, INK).apply {
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor("#F1EDE4")) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun openAccessSettings() {
        val i = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, NotifListener::class.java).flattenToString())
        else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        try { startActivity(i) } catch (e: Exception) { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
