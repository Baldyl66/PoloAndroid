package fr.dylan.polo.compagnon

import android.app.Notification
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Notifications actuelles du téléphone, telles qu'elles seront envoyées à l'autoradio.
 * Les notifications permanentes (musique, GPS, téléchargements…) et silencieuses sont écartées.
 */
object NotifStore {

    class Item(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val time: Long,
        val isMsg: Boolean,
        /** Conversation : paires (expéditeur, texte), la plus ancienne en premier. */
        val conv: List<Pair<String, String>>,
        /** Réponses proposées par Android / l'appli de messagerie. */
        val replies: List<String>,
        val reply: Notification.Action?,
        /** Vrai si un nouveau contenu vient d'arriver (sert à déclencher la lecture vocale). */
        val fresh: Boolean
    ) {
        fun toJson(): JSONObject {
            val c = JSONArray()
            for ((from, t) in conv) c.put(JSONArray().put(from).put(t))
            return JSONObject()
                .put("key", key).put("pkg", pkg).put("app", app)
                .put("title", title).put("text", text).put("time", time)
                .put("msg", isMsg).put("conv", c)
                .put("replies", JSONArray(replies))
                .put("canReply", reply != null)
                .put("fresh", fresh)
        }
    }

    /** "posted" (avec la notification) ou "removed" (avec sa clé). */
    fun interface Listener {
        fun onEvent(type: String, data: JSONObject)
    }

    private val items = LinkedHashMap<String, Item>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val labels = HashMap<String, String>()

    /** Service en cours, pour effacer une notification depuis l'autoradio. */
    @Volatile var service: NotificationListenerService? = null

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    @Synchronized
    fun all(): List<Item> = items.values.sortedByDescending { it.time }

    fun allJson(): JSONArray = JSONArray().also { a -> all().forEach { a.put(it.toJson()) } }

    @Synchronized
    fun clear() = items.clear()

    // ===================== réception =====================

    private val IGNORED_CATEGORIES = setOf(
        Notification.CATEGORY_CALL, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_NAVIGATION,
        Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM
    )

    fun onPosted(ctx: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?, initial: Boolean) {
        val n = sbn.notification ?: return
        if (sbn.packageName == ctx.packageName) return
        val flags = n.flags
        if ((flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY)) != 0) return
        if (n.category in IGNORED_CATEGORIES) return
        if (ranking != null && ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT) return

        val ex = n.extras ?: return
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val reply = replyAction(n)
        val conv = conversation(ex)
        val isMsg = n.category == Notification.CATEGORY_MESSAGE || conv.isNotEmpty() || reply != null

        val replies = LinkedHashSet<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ranking?.smartReplies?.forEach { replies.add(it.toString()) }
        reply?.remoteInputs?.forEach { ri -> ri.choices?.forEach { replies.add(it.toString()) } }

        val item: Item
        synchronized(this) {
            val old = items[sbn.key]
            val lastText = conv.lastOrNull()?.second ?: text
            val oldLast = old?.let { it.conv.lastOrNull()?.second ?: it.text }
            item = Item(
                sbn.key, sbn.packageName, label(ctx, sbn.packageName), title, text, sbn.postTime, isMsg,
                if (conv.isEmpty() && isMsg && text.isNotEmpty()) listOf(title to text) else conv,
                replies.filter { it.isNotBlank() }.take(5), reply,
                fresh = !initial && lastText != oldLast
            )
            items[sbn.key] = item
        }
        if (!initial) listeners.forEach { it.onEvent("posted", item.toJson()) }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        val removed = synchronized(this) { items.remove(sbn.key) } ?: return
        listeners.forEach { it.onEvent("removed", JSONObject().put("key", removed.key)) }
    }

    /** Fin du chargement des notifications déjà présentes : prévient l'écran et l'autoradio. */
    fun loaded() = listeners.forEach { it.onEvent("all", JSONObject().put("items", allJson())) }

    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true && a.actionIntent != null }

    @Suppress("DEPRECATION")
    private fun conversation(ex: Bundle): List<Pair<String, String>> {
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (p in arr) {
            val b = p as? Bundle ?: continue
            val t = b.getCharSequence("text")?.toString() ?: continue
            val sender = b.getCharSequence("sender")?.toString()
                ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (b.getParcelable("sender_person") as? Person)?.name?.toString() else null)
                ?: "Moi"
            out.add(sender to t)
        }
        return out.takeLast(30)
    }

    private fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }
    }

    // ===================== actions demandées par l'autoradio =====================

    /** Répond avec le bouton « Répondre » de la notification. */
    fun reply(ctx: Context, key: String, text: String): Boolean {
        val a = synchronized(this) { items[key]?.reply } ?: return false
        return try {
            val intent = Intent()
            val bundle = Bundle()
            for (ri in a.remoteInputs) bundle.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(a.remoteInputs, intent, bundle)
            a.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: Exception) { false }
    }

    /** Efface la notification sur le téléphone. */
    fun dismiss(key: String): Boolean = try {
        service?.cancelNotification(key); true
    } catch (e: Exception) { false }
}
