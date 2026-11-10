package fr.dylan.polo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Écran principal : affiche l'interface (assets/www/index.html) en plein écran
 * et lui donne accès au matériel de l'autoradio via [NativeBridge].
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var bridge: NativeBridge
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private var spotifyCallbackServer: ServerSocket? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // L'interface est servie depuis une adresse https locale : la géolocalisation
        // et les requêtes vers la carte en ligne fonctionnent comme sur un vrai site.
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/photo/") { path -> contactPhoto(android.net.Uri.decode(path)) }
            .build()

        webView = WebView(this)
        setContentView(webView)
        webView.setBackgroundColor(0xFF000000.toInt())
        // Pas de sélection de texte ni de menu « copier » à l'appui long
        webView.setOnLongClickListener { true }
        webView.isLongClickable = false
        webView.isHapticFeedbackEnabled = false

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    callback.invoke(origin, true, true)
                } else {
                    pendingGeo = origin to callback
                    requestAppPermissions()
                }
            }
        }

        bridge = NativeBridge(this, webView)
        webView.addJavascriptInterface(bridge, "PoloNative")
        startSpotifyCallbackServer()
        MediaHub.init(this) { json -> bridge.emit("media", json) }
        PhoneHub.init(this) { type, json -> bridge.emit(type, json) }
        CompanionLink.init(this) { type, json -> bridge.emit(type, json) }

        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        requestAppPermissions()
        bridge.maybeAskMediaAccessOnce()
    }

    /**
     * En cas de plantage : on écrit l'erreur dans crash.log (visible dans Réglages → À propos)
     * puis on relance l'application 2 secondes plus tard.
     */
    private fun installCrashHandler() {
        val app = applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val sw = java.io.StringWriter()
                err.printStackTrace(java.io.PrintWriter(sw))
                val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE).format(java.util.Date())
                java.io.File(app.filesDir, "crash.log").writeText("$date (fil : ${thread.name})\n$sw")
            } catch (e: Exception) {
            }
            try {
                val restart = android.app.PendingIntent.getActivity(
                    app, 0,
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val am = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 2000, restart)
            } catch (e: Exception) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    /** Photo d'un contact (miniature) à partir de son numéro : https://appassets.androidplatform.net/photo/<numéro> */
    private fun contactPhoto(number: String): WebResourceResponse {
        val none = WebResourceResponse("image/png", null, 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
        if (number.isBlank() || !hasPerm(Manifest.permission.READ_CONTACTS)) return none
        return try {
            val lookup = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
            val photo = contentResolver.query(lookup, arrayOf(android.provider.ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return none
            val stream = contentResolver.openInputStream(android.net.Uri.parse(photo)) ?: return none
            WebResourceResponse("image/jpeg", null, 200, "OK", mapOf("Cache-Control" to "max-age=3600"), stream)
        } catch (e: Exception) {
            none
        }
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun startSpotifyCallbackServer() {
        try {
            spotifyCallbackServer = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 8765))
            }
            Thread {
                while (spotifyCallbackServer != null) {
                    try {
                        // Une connexion par fil : une connexion vide (préconnexion du navigateur)
                        // ne doit pas bloquer la vraie réponse de Spotify.
                        val socket = spotifyCallbackServer?.accept() ?: break
                        Thread {
                            try {
                                socket.use { handleSpotifyCallback(it) }
                            } catch (e: Exception) {
                                android.util.Log.w("PoloRadio", "Spotify callback connection failed", e)
                            }
                        }.apply { isDaemon = true; start() }
                    } catch (e: Exception) {
                        if (spotifyCallbackServer != null) {
                            android.util.Log.e("PoloRadio", "Spotify callback server failed", e)
                        }
                    }
                }
            }.apply {
                name = "spotify-callback"
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            android.util.Log.e("PoloRadio", "Unable to start Spotify callback server", e)
        }
    }

    private fun handleSpotifyCallback(socket: Socket) {
        socket.soTimeout = 5000
        val request = BufferedReader(InputStreamReader(socket.getInputStream())).readLine() ?: return
        val target = request.split(' ').getOrNull(1) ?: return
        val writer = PrintWriter(socket.getOutputStream())
        if (!target.startsWith("/callback")) {
            // favicon.ico, etc. : on ignore
            writer.print("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            writer.flush()
            return
        }
        writer.print("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n")
        writer.print("<html><head><meta name='viewport' content='width=device-width'></head>" +
            "<body style='font-family:sans-serif;text-align:center;padding:40px'>" +
            "<p>Connexion terminée.</p>" +
            "<p><a href='polo-radio://spotify' style='font-size:22px'>Revenir dans Polo Radio</a></p>" +
            "<script>setTimeout(function(){location.href='polo-radio://spotify'},300)</script>" +
            "</body></html>")
        writer.flush()

        val callbackUrl = "http://127.0.0.1:8765$target"
        // Gardée aussi en réserve au cas où l'interface n'était pas prête.
        bridge.pendingAuthUrl = callbackUrl
        bridge.emit("spotifyAuth", org.json.JSONObject().put("url", callbackUrl).toString())
        bringToFront()
    }

    /** Ramène Polo Radio au premier plan après la connexion dans le navigateur. */
    private fun bringToFront() {
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
        }
    }

    fun requestAppPermissions() {
        val wanted = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO
        ).plus(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) arrayOf(Manifest.permission.ANSWER_PHONE_CALLS) else emptyArray())
            .plus(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray())
            .filter { !hasPerm(it) }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pendingGeo?.let { (origin, cb) ->
            cb.invoke(origin, hasPerm(Manifest.permission.ACCESS_FINE_LOCATION), true)
            pendingGeo = null
        }
        bridge.emit("permissions", bridge.permissionsJson())
    }

    /** Retour depuis le navigateur après la connexion à Spotify. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        MediaHub.refresh(this)
        bridge.emit("resume", "{}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    /** Le bouton Retour de l'autoradio : on laisse l'interface décider (elle a son propre historique). */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript("window.PoloNativeBack ? window.PoloNativeBack() : false", null)
    }

    override fun onDestroy() {
        spotifyCallbackServer?.close()
        spotifyCallbackServer = null
        bridge.release()
        MediaHub.release()
        CompanionLink.release()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        fun prefs(ctx: Context) = ctx.getSharedPreferences("polo", Context.MODE_PRIVATE)
    }
}
