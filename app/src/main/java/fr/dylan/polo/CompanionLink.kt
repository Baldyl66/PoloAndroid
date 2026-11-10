package fr.dylan.polo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID

/**
 * Liaison avec l'appli « Polo Compagnon » du téléphone, qui envoie ses notifications.
 * Wi-Fi d'abord (le téléphone est la passerelle du partage de connexion), sinon Bluetooth.
 * En cas de coupure, nouvel essai toutes les 5 secondes.
 */
object CompanionLink {

    private const val PORT = 47800
    private val BT_UUID: UUID = UUID.fromString("6f1c2a3e-8b4d-4c7a-9e21-50b0c0de4701")

    private var app: Context? = null
    private var emit: ((String, String) -> Unit)? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    /** "off", "search", "wifi", "bt" ou "badcode". */
    @Volatile private var state = "search"
    @Volatile private var phone = ""
    @Volatile private var out: OutputStream? = null
    @Volatile private var sock: Closeable? = null

    private val items = LinkedHashMap<String, JSONObject>()

    fun init(ctx: Context, onEvent: (String, String) -> Unit) {
        app = ctx.applicationContext
        emit = onEvent
        if (running) return
        running = true
        thread = Thread({ loop() }, "polo-compagnon").apply { isDaemon = true; start() }
    }

    fun release() {
        running = false
        closeSocket()
        thread?.interrupt()
        thread = null
    }

    /** Code d'appairage à taper dans Polo Compagnon (créé une fois pour toutes). */
    fun code(): String {
        val p = app?.getSharedPreferences("polo", Context.MODE_PRIVATE) ?: return ""
        p.getString("compagnonCode", null)?.let { return it }
        val c = String.format("%06d", SecureRandom().nextInt(1_000_000))
        p.edit().putString("compagnonCode", c).apply()
        return c
    }

    fun stateJson(): String = JSONObject()
        .put("state", state).put("phone", phone).put("code", code())
        .put("count", synchronized(items) { items.size })
        .toString()

    /** Notifications actuelles du téléphone, la plus récente en premier. */
    fun itemsJson(): String {
        val list = synchronized(items) { items.values.sortedByDescending { it.optLong("time") } }
        return JSONArray(list).toString()
    }

    /** Dernières infos du téléphone : anniversaires, agenda, réveil, batterie ("{}" si pas encore reçues). */
    @Volatile private var info = "{}"
    fun infoJson(): String = info

    /** Répond à un message par le téléphone. Renvoie "ok" ou "offline". */
    fun reply(key: String, text: String): String =
        if (send(JSONObject().put("t", "reply").put("key", key).put("text", text).put("id", System.currentTimeMillis().toString()))) "ok" else "offline"

    fun dismiss(key: String): Boolean = send(JSONObject().put("t", "dismiss").put("key", key))

    /** Demande au téléphone d'envoyer un SMS à un contact. Renvoie "ok" (demande partie) ou "offline". */
    fun sendSms(name: String, text: String): String =
        if (send(JSONObject().put("t", "sms").put("name", name).put("text", text))) "ok" else "offline"

    // ===================== connexion =====================

    private fun loop() {
        var tick = 0
        while (running) {
            // Bluetooth essayé une fois sur 6 (≈ 30 s) pour ne pas gêner le Bluetooth des appels
            val ok = tryWifi() || (tick++ % 6 == 0 && tryBluetooth())
            if (!running) break
            if (!ok && state != "badcode") setState("search")
            try { Thread.sleep(if (state == "badcode") 15_000 else 5_000) } catch (e: InterruptedException) { }
        }
        setState("off")
    }

    @Suppress("DEPRECATION")
    private fun tryWifi(): Boolean {
        val wifi = app?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        val gw = try { wifi.dhcpInfo?.gateway ?: 0 } catch (e: Exception) { 0 }
        if (gw == 0) return false
        val ip = InetAddress.getByAddress(byteArrayOf(
            (gw and 0xff).toByte(), (gw shr 8 and 0xff).toByte(), (gw shr 16 and 0xff).toByte(), (gw shr 24 and 0xff).toByte()))
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(ip, PORT), 2500)
            s.tcpNoDelay = true
            s.soTimeout = 45_000
            session("wifi", s.getInputStream(), s.getOutputStream(), s)
        } catch (e: Exception) {
            try { s.close() } catch (x: Exception) {}
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryBluetooth(): Boolean {
        val ctx = app ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val devices = try { adapter.bondedDevices } catch (e: Exception) { null } ?: return false
        for (d in devices) {
            if (!running) return false
            val s = try { d.createRfcommSocketToServiceRecord(BT_UUID) } catch (e: Exception) { null } ?: continue
            try {
                try { adapter.cancelDiscovery() } catch (e: Exception) {}
                s.connect()
            } catch (e: Exception) {
                try { s.close() } catch (x: Exception) {}
                continue
            }
            // le Bluetooth n'a pas de délai de lecture : le ping (toutes les 15 s) détecte les coupures
            if (session("bt", s.inputStream, s.outputStream, s)) return true
        }
        return false
    }

    /** Vrai si la liaison a été établie (même si elle s'est coupée depuis). */
    private fun session(via: String, input: InputStream, output: OutputStream, socket: Closeable): Boolean {
        sock = socket
        out = output
        var welcomed = false
        val pinger = Thread({
            try {
                while (running && sock === socket) {
                    Thread.sleep(15_000)
                    if (!send(JSONObject().put("t", "ping"))) { closeSocket(); break }
                }
            } catch (e: InterruptedException) { }
        }, "polo-ping").apply { isDaemon = true }
        try {
            writeLine(output, JSONObject().put("t", "hello").put("code", code()).put("name", "Polo Radio"))
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            pinger.start()
            while (running) {
                val line = reader.readLine() ?: break
                val m = try { JSONObject(line) } catch (e: Exception) { continue }
                when (m.optString("t")) {
                    "denied" -> { setState("badcode"); break }
                    "welcome" -> {
                        welcomed = true
                        phone = m.optString("phone")
                        setState(via)
                    }
                    "all" -> {
                        val arr = m.optJSONArray("items") ?: JSONArray()
                        synchronized(items) {
                            items.clear()
                            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items[it.optString("key")] = it }
                        }
                        emitNotif(JSONObject().put("type", "all"))
                    }
                    "posted" -> {
                        val it = m.optJSONObject("item") ?: continue
                        synchronized(items) { items[it.optString("key")] = it }
                        emitNotif(JSONObject().put("type", "posted").put("item", it))
                    }
                    "removed" -> {
                        val k = m.optString("key")
                        synchronized(items) { items.remove(k) }
                        emitNotif(JSONObject().put("type", "removed").put("key", k))
                    }
                    "smsresult" -> emit?.invoke("smsresult", m.toString())
                    "info" -> {
                        info = (m.optJSONObject("info") ?: JSONObject()).toString()
                        emit?.invoke("phoneinfo", info)
                    }
                    "result" -> emitNotif(JSONObject().put("type", "result").put("ok", m.optBoolean("ok")))
                }
            }
        } catch (e: Exception) {
        }
        pinger.interrupt()
        closeSocket()
        if (welcomed) {
            synchronized(items) { items.clear() }
            emitNotif(JSONObject().put("type", "all"))
            if (state == via) setState("search")
        }
        return welcomed
    }

    private fun send(msg: JSONObject): Boolean {
        val o = out ?: return false
        return try { writeLine(o, msg); true } catch (e: Exception) { false }
    }

    private fun writeLine(o: OutputStream, msg: JSONObject) = synchronized(this) {
        o.write((msg.toString() + "\n").toByteArray(Charsets.UTF_8))
        o.flush()
    }

    private fun closeSocket() {
        out = null
        try { sock?.close() } catch (e: Exception) {}
        sock = null
    }

    private fun setState(s: String) {
        if (state == s) return
        state = s
        emit?.invoke("link", stateJson())
    }

    private fun emitNotif(j: JSONObject) {
        emit?.invoke("notif", j.toString())
    }
}
