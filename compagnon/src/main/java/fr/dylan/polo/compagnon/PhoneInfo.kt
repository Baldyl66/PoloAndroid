package fr.dylan.polo.compagnon

import android.Manifest
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Ce que le téléphone sait et que l'assistant de l'autoradio peut utiliser :
 * anniversaires des contacts, agenda d'aujourd'hui et de demain, prochain réveil, batterie.
 * Seuls les noms, les dates et les rendez-vous passent à l'autoradio.
 */
object PhoneInfo {

    fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun collect(ctx: Context): JSONObject = JSONObject()
        .put("dates", dates(ctx))
        .put("events", events(ctx))
        .put("alarm", alarm(ctx))
        .put("battery", battery(ctx))

    /** Anniversaires et anniversaires de mariage des contacts : [{who, kind, d, m (0-11), y}]. */
    private fun dates(ctx: Context): JSONArray {
        val out = JSONArray()
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return out
        val seen = HashSet<String>()
        try {
            ctx.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE, ContactsContract.CommonDataKinds.Event.TYPE),
                "${ContactsContract.Data.MIMETYPE}=? AND ${ContactsContract.CommonDataKinds.Event.TYPE} IN (?,?)",
                arrayOf(
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString(),
                    ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY.toString()
                ), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val who = c.getString(0) ?: continue
                    val raw = c.getString(1) ?: continue
                    // formats possibles : 1990-03-12, --03-12, 19900312
                    val m = Regex("^(\\d{4}|-)?-?(\\d{2})-?(\\d{2})").find(raw.trim()) ?: continue
                    val month = m.groupValues[2].toInt() - 1
                    val day = m.groupValues[3].toInt()
                    val year = m.groupValues[1].toIntOrNull() ?: 0
                    val kind = if (c.getInt(2) == ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY) "anniversaire de mariage" else "anniversaire"
                    if (!seen.add("$who|$kind|$month|$day")) continue
                    out.put(JSONObject().put("who", who).put("kind", kind).put("d", day).put("m", month).put("y", year))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Rendez-vous d'aujourd'hui et de demain : [{title, start, end, allDay, loc}]. */
    private fun events(ctx: Context): JSONArray {
        val out = JSONArray()
        if (!has(ctx, Manifest.permission.READ_CALENDAR)) return out
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val begin = cal.timeInMillis
        val end = begin + 2 * 24 * 3600 * 1000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin); ContentUris.appendId(it, end)
        }.build()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME
                ), null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext() && out.length() < 30) {
                    val title = c.getString(0)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    // le calendrier « Anniversaires » de Google fait doublon avec les contacts
                    val calName = c.getString(5).orEmpty()
                    if (Regex("(?i)anniversaire|birthday|contacts").containsMatchIn(calName)) continue
                    out.put(JSONObject().put("title", title).put("start", c.getLong(1)).put("end", c.getLong(2))
                        .put("allDay", c.getInt(3) == 1).put("loc", c.getString(4)?.trim().orEmpty()))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Heure du prochain réveil du téléphone (0 s'il n'y en a pas). */
    private fun alarm(ctx: Context): Long = try {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime ?: 0L
    } catch (e: Exception) { 0L }

    /**
     * Envoie un SMS à un contact du téléphone, demandé à la voix depuis l'autoradio.
     * Renvoie "ok", "noperm", "nocontact" ou "error".
     */
    fun sendSms(ctx: Context, name: String, text: String): String {
        if (text.isBlank()) return "error"
        if (!has(ctx, Manifest.permission.SEND_SMS) || !has(ctx, Manifest.permission.READ_CONTACTS)) return "noperm"
        val number = findNumber(ctx, name) ?: return "nocontact"
        return try {
            @Suppress("DEPRECATION")
            val sms = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.telephony.SmsManager::class.java)
                else android.telephony.SmsManager.getDefault()
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            "ok"
        } catch (e: Exception) { "error" }
    }

    /** Numéro d'un contact : nom exact, sinon nom qui commence pareil, sinon prénom. */
    private fun findNumber(ctx: Context, name: String): String? {
        val wanted = name.trim().lowercase()
        if (wanted.isEmpty()) return null
        val all = ArrayList<Pair<String, String>>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY),
                null, null, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC"
            )?.use { c -> while (c.moveToNext()) { val n = c.getString(0) ?: continue; val num = c.getString(1) ?: continue; all.add(n.lowercase() to num) } }
        } catch (e: Exception) {
            return null
        }
        return (all.firstOrNull { it.first == wanted } ?: all.firstOrNull { it.first.startsWith(wanted) }
            ?: all.firstOrNull { it.first.split(" ").contains(wanted) })?.second
    }

    /** Batterie du téléphone : {pct, charging}. */
    private fun battery(ctx: Context): JSONObject {
        val i: Intent? = try { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (e: Exception) { null }
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return JSONObject().put("pct", if (level >= 0 && scale > 0) level * 100 / scale else -1).put("charging", plugged)
    }
}
