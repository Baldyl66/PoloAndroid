package fr.dylan.polo.compagnon

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Liaison avec l'autoradio : le téléphone écoute en Wi-Fi (partage de connexion) et en Bluetooth,
 * l'autoradio s'y connecte. Une ligne JSON par message, dans les deux sens.
 * L'autoradio doit d'abord donner le bon code d'appairage, sinon rien n'est envoyé.
 */
object LinkServer {

    const val PORT = 47800
    val BT_UUID: UUID = UUID.fromString("6f1c2a3e-8b4d-4c7a-9e21-50b0c0de4701")

    /** Liaison en cours : "wifi", "bt" ou null, et nom de l'autoradio. */
    @Volatile var via: String? = null; private set
    @Volatile var radioName: String? = null; private set
    /** Dernière tentative refusée pour mauvais code (pour l'afficher sur l'écran). */
    @Volatile var lastDenied = 0L; private set

    private val statusListeners = CopyOnWriteArrayList<() -> Unit>()
    fun addStatusListener(l: () -> Unit) = statusListeners.add(l)
    fun removeStatusListener(l: () -> Unit) = statusListeners.remove(l)
    private fun statusChanged() = statusListeners.forEach { it() }

    private var app: Context? = null
    private var tcp: ServerSocket? = null
    private var bt: BluetoothServerSocket? = null
    private val sessions = CopyOnWriteArrayList<Session>()

    // ---------- code d'appairage ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("liaison", Context.MODE_PRIVATE)
    fun code(ctx: Context): String = prefs(ctx).getString("code", "") ?: ""
    fun setCode(ctx: Context, code: String) {
        prefs(ctx).edit().putString("code", code.trim()).apply()
        sessions.forEach { it.close() }
    }

    // anti-essais : 5 mauvais codes = 1 minute de blocage
    private var fails = 0
    private var lockedUntil = 0L

    // ---------- démarrage ----------

    private val forward = NotifStore.Listener { type, data ->
        val msg = when (type) {
            "posted" -> JSONObject().put("t", "posted").put("item", data)
            "removed" -> JSONObject().put("t", "removed").put("key", data.optString("key"))
            else -> JSONObject().put("t", "all").put("items", data.optJSONArray("items"))
        }
        sessions.forEach { it.send(msg) }
    }

    // anniversaires, agenda, réveil et batterie : envoyés à la connexion puis toutes les 10 minutes
    private var infoTimer: java.util.concurrent.ScheduledExecutorService? = null
    private fun infoMsg(): JSONObject? = app?.let { JSONObject().put("t", "info").put("info", PhoneInfo.collect(it)) }
    fun sendInfo() { val m = infoMsg() ?: return; sessions.forEach { it.send(m) } }

    @Synchronized
    fun start(ctx: Context) {
        app = ctx.applicationContext
        NotifStore.removeListener(forward)
        NotifStore.addListener(forward)
        if (tcp == null) Thread({ runTcp() }, "polo-wifi").start()
        startBluetooth(ctx)
        if (infoTimer == null) infoTimer = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ try { sendInfo() } catch (e: Exception) {} }, 10, 10, java.util.concurrent.TimeUnit.MINUTES)
        }
    }

    @Synchronized
    fun stop() {
        infoTimer?.shutdownNow(); infoTimer = null
        NotifStore.removeListener(forward)
        quietly(tcp); tcp = null
        quietly(bt); bt = null
        sessions.forEach { it.close() }
    }

    fun hasBtPermission(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Démarre l'écoute Bluetooth (à rappeler après avoir accordé l'autorisation). */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startBluetooth(ctx: Context) {
        if (app == null || bt != null || !hasBtPermission(ctx)) return
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        if (!adapter.isEnabled) return
        val server = try { adapter.listenUsingRfcommWithServiceRecord("Polo Compagnon", BT_UUID) } catch (e: Exception) { null } ?: return
        bt = server
        Thread({
            while (bt === server) {
                val s = try { server.accept() } catch (e: Exception) { null } ?: break
                Session("bt", s.inputStream, s.outputStream, s).start()
            }
            synchronized(this) { if (bt === server) bt = null }
        }, "polo-bt").start()
    }

    private fun runTcp() {
        val server = try { ServerSocket(PORT) } catch (e: Exception) { null } ?: return
        synchronized(this) { tcp = server }
        while (tcp === server) {
            val s = try { server.accept() } catch (e: Exception) { null } ?: break
            s.tcpNoDelay = true
            Session("wifi", s.getInputStream(), s.getOutputStream(), s).start()
        }
        synchronized(this) { if (tcp === server) tcp = null }
    }

    private fun quietly(c: Closeable?) = try { c?.close() } catch (e: Exception) {}

    // ---------- une connexion avec l'autoradio ----------

    private class Session(val via: String, val input: InputStream, val output: OutputStream, val sock: Closeable) {
        private val writer = Executors.newSingleThreadExecutor()
        @Volatile private var ready = false

        fun start() = Thread({ run() }, "polo-$via").start()

        fun send(msg: JSONObject) {
            if (!ready) return
            write(msg)
        }

        private fun write(msg: JSONObject) {
            try {
                writer.execute {
                    try {
                        output.write((msg.toString() + "\n").toByteArray(Charsets.UTF_8)); output.flush()
                    } catch (e: Exception) { close() }
                }
            } catch (e: Exception) {
            }
        }

        fun close() {
            ready = false
            try { sock.close() } catch (e: Exception) {}
            writer.shutdown()
            if (sessions.remove(this)) refreshStatus()
        }

        private fun run() {
            val ctx = app ?: return close()
            try {
                if (sock is Socket) sock.soTimeout = 60_000
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                val hello = JSONObject(reader.readLine() ?: return close())
                if (!checkCode(ctx, hello.optString("code"))) {
                    write(JSONObject().put("t", "denied"))
                    lastDenied = System.currentTimeMillis()
                    statusChanged()
                    Thread.sleep(300)
                    return close()
                }
                // une seule liaison à la fois : la nouvelle remplace l'ancienne
                sessions.forEach { it.close() }
                sessions.add(this)
                radioName = hello.optString("name", "Autoradio")
                ready = true
                refreshStatus()
                write(JSONObject().put("t", "welcome").put("phone", Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL))
                write(JSONObject().put("t", "all").put("items", NotifStore.allJson()))
                infoMsg()?.let { write(it) }
                while (true) {
                    val line = reader.readLine() ?: break
                    val m = try { JSONObject(line) } catch (e: Exception) { continue }
                    when (m.optString("t")) {
                        "ping" -> write(JSONObject().put("t", "pong"))
                        "reply" -> {
                            val ok = NotifStore.reply(ctx, m.optString("key"), m.optString("text"))
                            write(JSONObject().put("t", "result").put("id", m.optString("id")).put("ok", ok))
                        }
                        "dismiss" -> NotifStore.dismiss(m.optString("key"))
                    }
                }
            } catch (e: Exception) {
            }
            close()
        }
    }

    @Synchronized
    private fun checkCode(ctx: Context, given: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < lockedUntil) return false
        val expected = code(ctx)
        if (expected.length == 6 && given == expected) { fails = 0; return true }
        if (++fails >= 5) { fails = 0; lockedUntil = now + 60_000 }
        return false
    }

    private fun refreshStatus() {
        val s = sessions.firstOrNull()
        via = s?.via
        if (s == null) radioName = null
        statusChanged()
    }
}
