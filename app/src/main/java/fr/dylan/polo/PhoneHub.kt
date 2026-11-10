package fr.dylan.polo

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.view.KeyEvent
import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Appels et messages :
 *  - décrocher / raccrocher (bouton de la notification d'appel, sinon Android, sinon touche « décrocher ») ;
 *  - conversations vues dans les notifications des applis de messagerie / Bluetooth de l'autoradio ;
 *  - réponse via le bouton « Répondre » de la notification (aucun accès aux SMS).
 */
object PhoneHub {

    private var app: Context? = null
    private var emit: ((String, String) -> Unit)? = null

    /** Notification d'appel en cours (appli Téléphone ou Bluetooth de l'autoradio). */
    @Volatile private var callNotif: Notification? = null

    private class Msg(val incoming: Boolean, val text: String, val time: Long)
    private class Conv(val key: String, var name: String, var number: String?) {
        val msgs = ArrayList<Msg>()
        var unread = 0
        var reply: Notification.Action? = null
    }

    private val notifConvs = LinkedHashMap<String, Conv>()

    fun init(ctx: Context, onEvent: (String, String) -> Unit) {
        app = ctx.applicationContext
        emit = onEvent
    }

    private fun has(p: String) = app?.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ===================== notifications =====================

    private val ANSWER = Regex("(?i)answer|accept|répondre|repondre|décrocher|decrocher|prendre")
    private val HANGUP = Regex("(?i)hang|decline|reject|end|refuser|raccrocher|terminer|rejeter")

    fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == app?.packageName) return
        val isCall = n.category == Notification.CATEGORY_CALL ||
            (n.actions?.any { HANGUP.containsMatchIn(it.title ?: "") } == true &&
                n.actions?.any { ANSWER.containsMatchIn(it.title ?: "") } == true)
        if (isCall) {
            callNotif = n
            return
        }
        val isMsg = n.category == Notification.CATEGORY_MESSAGE || replyAction(n) != null ||
            n.extras?.containsKey(Notification.EXTRA_MESSAGES) == true
        if (isMsg && (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0) addFromNotification(sbn, n)
    }

    fun onNotificationRemoved(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n === callNotif || n.category == Notification.CATEGORY_CALL) callNotif = null
    }

    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true && a.actionIntent != null }

    private fun addFromNotification(sbn: StatusBarNotification, n: Notification) {
        val ex = n.extras ?: return
        val title = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim() ?: return
        val texts = ArrayList<Pair<String, Long>>()
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (arr != null && arr.isNotEmpty()) {
            for (p in arr) {
                val b = p as? Bundle ?: continue
                val t = b.getCharSequence("text")?.toString() ?: continue
                texts.add(t to b.getLong("time", sbn.postTime))
            }
        } else {
            val t = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            if (!t.isNullOrBlank()) texts.add(t to sbn.postTime)
        }
        if (texts.isEmpty()) return
        val key = "notif:" + sbn.packageName + ":" + title
        val c = notifConvs.getOrPut(key) { Conv(key, title, lookupNumber(title)) }
        c.reply = replyAction(n) ?: c.reply
        var added: Msg? = null
        for ((t, time) in texts) {
            if (c.msgs.any { it.text == t && Math.abs(it.time - time) < 120000 }) continue
            val m = Msg(true, t, time)
            c.msgs.add(m); c.unread++; added = m
        }
        while (c.msgs.size > 40) c.msgs.removeAt(0)
        if (added != null) {
            val ev = JSONObject().put("name", c.name).put("text", added.text).put("key", key).put("in", true)
            emit?.invoke("sms", JSONObject().put("new", ev).toString())
        }
    }

    // ===================== appels =====================

    fun answer(): Boolean {
        if (fire(callNotif, ANSWER)) return true
        val ctx = app ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            try {
                (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).acceptRingingCall()
                return true
            } catch (e: Exception) {
            }
        }
        return key(KeyEvent.KEYCODE_HEADSETHOOK)
    }

    fun hangup(): Boolean {
        if (fire(callNotif, HANGUP)) return true
        val ctx = app ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            try {
                if ((ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()) return true
            } catch (e: Exception) {
            }
        }
        return key(KeyEvent.KEYCODE_ENDCALL) || key(KeyEvent.KEYCODE_HEADSETHOOK)
    }

    private fun fire(n: Notification?, re: Regex): Boolean {
        val a = n?.actions?.firstOrNull { re.containsMatchIn(it.title ?: "") } ?: return false
        return try { a.actionIntent.send(); true } catch (e: Exception) { false }
    }

    private fun key(code: Int): Boolean = try {
        val am = app!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        true
    } catch (e: Exception) { false }

    /** Nom du contact pour un numéro (ou null). */
    fun nameFor(number: String?): String? {
        if (number.isNullOrBlank() || !has(Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            app!!.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    private fun lookupNumber(name: String): String? {
        if (!has(Manifest.permission.READ_CONTACTS)) return null
        return try {
            app!!.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + "=?", arrayOf(name), null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }

    // ===================== messages =====================

    /** Toutes les conversations, la plus récente en premier. */
    fun conversationsJson(): String {
        val list = notifConvs.values.sortedByDescending { c -> c.msgs.lastOrNull()?.time ?: 0L }
        val out = JSONArray()
        for (c in list) {
            if (c.msgs.isEmpty()) continue
            val msgs = JSONArray()
            for (m in c.msgs) msgs.put(JSONArray().put(if (m.incoming) "in" else "out").put(m.text))
            out.put(JSONObject()
                .put("name", c.name).put("number", c.key).put("phone", c.number ?: "")
                .put("time", c.msgs.last().time).put("un", c.unread).put("msgs", msgs)
                .put("canReply", c.reply != null))
        }
        return out.toString()
    }

    fun markRead(key: String) {
        notifConvs[key]?.unread = 0
    }

    /** Répond à une conversation. Renvoie "ok", ou la raison de l'échec. */
    fun reply(key: String, text: String): String {
        val ctx = app ?: return "error"
        // par le bouton « Répondre » de la notification de l'appli de messagerie
        val viaNotif = notifConvs[key]?.reply ?: notifConvs.values.firstOrNull { it.name == (nameFor(key) ?: key) }?.reply
            ?: return "noreply"
        return try {
            val intent = Intent()
            val bundle = Bundle()
            for (ri in viaNotif.remoteInputs) bundle.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(viaNotif.remoteInputs, intent, bundle)
            viaNotif.actionIntent.send(ctx, 0, intent)
            "ok"
        } catch (e: PendingIntent.CanceledException) { "noreply" } catch (e: Exception) { "noreply" }
    }
}
