package fr.dylan.polo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pont entre l'interface (JavaScript : window.PoloNative) et l'autoradio Android.
 * Toutes les méthodes renvoient du texte JSON pour rester simples côté JavaScript.
 */
class NativeBridge(private val act: MainActivity, private val web: WebView) {

    val audioCoord = AudioCoordinator(act) { state, number ->
        // pas d'écoute de « Polo » pendant un appel
        if (state == "idle") WakeWord.resume() else WakeWord.pause()
        val j = JSONObject().put("state", state)
        if (!number.isNullOrBlank()) j.put("number", number).put("name", PhoneHub.nameFor(number) ?: number)
        emit("call", j.toString())
    }.also { it.start() }

    private val main = Handler(Looper.getMainLooper())

    /** Envoie un évènement à l'interface : window.PoloNativeEvent(type, données). */
    fun emit(type: String, json: String) {
        main.post {
            web.evaluateJavascript("window.PoloNativeEvent&&window.PoloNativeEvent(${JSONObject.quote(type)},$json)", null)
        }
    }

    private fun has(p: String) = act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------- général ----------

    @JavascriptInterface
    fun version(): String = "1.1"

    @JavascriptInterface
    fun permissionsJson(): String = JSONObject()
        .put("location", has(Manifest.permission.ACCESS_FINE_LOCATION))
        .put("contacts", has(Manifest.permission.READ_CONTACTS))
        .put("calllog", has(Manifest.permission.READ_CALL_LOG))
        .put("call", has(Manifest.permission.CALL_PHONE))
        .put("media", MediaHub.hasAccess(act))
        .put("mic", has(Manifest.permission.RECORD_AUDIO))
        .put("speech", SpeechRecognizer.isRecognitionAvailable(act))
        .toString()

    @JavascriptInterface
    fun requestPermissions() {
        main.post { act.requestAppPermissions() }
    }

    /** Ouvre une application installée (Spotify, Deezer…). Renvoie false si elle n'est pas installée. */
    @JavascriptInterface
    fun openApp(pkg: String): Boolean {
        val intent = act.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        main.post {
            try {
                act.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }
        return true
    }

    /**
     * Demande à une appli de musique de jouer une recherche (« Bohemian Rhapsody Queen »).
     * Marche avec Spotify et YouTube Music, même sans compte relié à Polo Radio.
     * pkg vide = première appli installée parmi Spotify puis YouTube Music.
     */
    @JavascriptInterface
    fun playFromSearch(query: String, pkg: String): Boolean {
        val pkgs = if (pkg.isNotEmpty()) listOf(pkg) else listOf("com.spotify.music", "com.google.android.apps.youtube.music")
        // 1) appli déjà ouverte : on lui passe la recherche en arrière-plan, l'écran ne change pas
        if (MediaHub.playFromSearch(act, query, pkgs) != null) return true
        // 2) sinon on la démarre avec la recherche, puis Polo Radio revient devant
        val target = pkgs.firstOrNull { act.packageManager.getLaunchIntentForPackage(it) != null } ?: return false
        val i = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(target)
            .putExtra(android.app.SearchManager.QUERY, query)
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post {
            try { act.startActivity(i) } catch (e: Exception) {
                try { act.startActivity(i.setPackage(null)) } catch (x: Exception) {}
            }
        }
        bringToFront(3500)
        return true
    }

    /**
     * Ramène Polo Radio au premier plan après un délai (le temps que l'appli de musique démarre la lecture).
     * Android l'autorise grâce à « Afficher par-dessus les autres applis » (déjà demandé pour le démarrage auto).
     */
    @JavascriptInterface
    fun bringToFront(delayMs: Int) {
        main.postDelayed({
            try {
                act.startActivity(Intent(act, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (e: Exception) {}
        }, delayMs.toLong().coerceIn(0, 15_000))
    }

    /** Ouvre une adresse dans le navigateur (connexion Spotify). */
    @JavascriptInterface
    fun openUrl(url: String) {
        main.post {
            try {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }
    }

    /** Adresse de retour Spotify reçue au lancement de l'application (si elle était fermée). */
    @Volatile var pendingAuthUrl: String? = null

    // ---------- appels et messages ----------

    @JavascriptInterface fun lastLocation(): String = LocationHub.last ?: ""
    @JavascriptInterface fun answerCall(): Boolean = PhoneHub.answer()
    @JavascriptInterface fun hangupCall(): Boolean = PhoneHub.hangup()
    @JavascriptInterface fun messages(): String = PhoneHub.conversationsJson()
    @JavascriptInterface fun markRead(key: String) = PhoneHub.markRead(key)
    @JavascriptInterface fun sendSms(key: String, text: String): String = PhoneHub.reply(key, text)

    // ---------- notifications du téléphone (appli Polo Compagnon) ----------

    @JavascriptInterface fun phoneNotifs(): String = CompanionLink.itemsJson()
    @JavascriptInterface fun phoneReply(key: String, text: String): String = CompanionLink.reply(key, text)
    @JavascriptInterface fun phoneDismiss(key: String): Boolean = CompanionLink.dismiss(key)
    @JavascriptInterface fun phoneLink(): String = CompanionLink.stateJson()
    @JavascriptInterface fun phoneInfo(): String = CompanionLink.infoJson()
    @JavascriptInterface fun phoneSms(name: String, text: String): String = CompanionLink.sendSms(name, text)

    // ---------- réveil de l'assistant en disant « Polo » ----------

    @JavascriptInterface
    fun getWake(): Boolean = MainActivity.prefs(act).getBoolean("wakeWord", true)

    @JavascriptInterface
    fun wakeAvailable(): Boolean = WakeWord.available(act)

    @JavascriptInterface
    fun setWake(on: Boolean) {
        MainActivity.prefs(act).edit().putBoolean("wakeWord", on).apply()
        main.post { if (on) startWake() else WakeWord.stop() }
    }

    /** Démarre l'écoute de « Polo » si le réglage est activé et le micro autorisé. */
    fun startWake() {
        if (!getWake() || !has(Manifest.permission.RECORD_AUDIO)) return
        WakeWord.start(act) { emit("wake", "{}") }
    }

    /** Dernier plantage enregistré (vide s'il n'y en a pas). */
    @JavascriptInterface
    fun crashLog(): String = try {
        java.io.File(act.filesDir, "crash.log").takeIf { it.exists() }?.readText()?.take(4000) ?: ""
    } catch (e: Exception) { "" }

    @JavascriptInterface
    fun clearCrashLog() {
        try { java.io.File(act.filesDir, "crash.log").delete() } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun takeAuthUrl(): String {
        val u = pendingAuthUrl ?: ""
        pendingAuthUrl = null
        return u
    }

    @JavascriptInterface
    fun isInstalled(pkg: String): Boolean = act.packageManager.getLaunchIntentForPackage(pkg) != null

    // ---------- musique ----------

    @JavascriptInterface
    fun media(): String = MediaHub.stateJson()

    @JavascriptInterface
    fun openMediaAccess() {
        main.post {
            try {
                act.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: Exception) {
            }
        }
    }

    @JavascriptInterface fun play() { MediaHub.play() }
    @JavascriptInterface fun pause() { MediaHub.pause() }
    @JavascriptInterface fun next() { MediaHub.next() }
    @JavascriptInterface fun previous() { MediaHub.previous() }
    @JavascriptInterface fun seek(ms: Double) { MediaHub.seek(ms.toLong()) }

    fun maybeAskMediaAccessOnce() {
        val prefs = MainActivity.prefs(act)
        if (!MediaHub.hasAccess(act) && !prefs.getBoolean("askedMedia", false)) {
            prefs.edit().putBoolean("askedMedia", true).apply()
            // Laisse le temps aux autorisations classiques de s'afficher d'abord.
            main.postDelayed({ openMediaAccess() }, 6000)
        }
    }

    // ---------- contacts & appels ----------

    private fun typeLabel(t: Int) = when (t) {
        Phone.TYPE_MOBILE -> "Mobile"
        Phone.TYPE_HOME -> "Domicile"
        Phone.TYPE_WORK -> "Travail"
        else -> "Autre"
    }

    @JavascriptInterface
    fun contacts(): String {
        val arr = JSONArray()
        if (!has(Manifest.permission.READ_CONTACTS)) return arr.toString()
        val cursor = try {
            act.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.STARRED, Phone.TYPE),
                null, null,
                Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )
        } catch (e: Exception) {
            null
        } ?: return arr.toString()
        val seen = HashSet<String>()
        try {
            while (cursor.moveToNext() && arr.length() < 800) {
                val name = cursor.getString(0) ?: ""
                val number = cursor.getString(1) ?: ""
                if (number.isBlank()) continue
                val key = name + "|" + number.filter { it.isDigit() }
                if (!seen.add(key)) continue
                arr.put(
                    JSONObject()
                        .put("name", name.ifBlank { number })
                        .put("number", number)
                        .put("starred", cursor.getInt(2) == 1)
                        .put("type", typeLabel(cursor.getInt(3)))
                )
            }
        } finally {
            cursor.close()
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun callLog(): String {
        val arr = JSONArray()
        if (!has(Manifest.permission.READ_CALL_LOG)) return arr.toString()
        val cursor = try {
            act.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null,
                CallLog.Calls.DATE + " DESC"
            )
        } catch (e: Exception) {
            null
        } ?: return arr.toString()
        try {
            while (cursor.moveToNext() && arr.length() < 40) {
                val number = cursor.getString(1) ?: ""
                val name = cursor.getString(0)
                val kind = when (cursor.getInt(2)) {
                    CallLog.Calls.OUTGOING_TYPE -> "out"
                    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "missed"
                    else -> "in"
                }
                arr.put(
                    JSONObject()
                        .put("name", if (name.isNullOrBlank()) number else name)
                        .put("number", number)
                        .put("kind", kind)
                        .put("date", cursor.getLong(3))
                )
            }
        } finally {
            cursor.close()
        }
        return arr.toString()
    }

    /** Lance un vrai appel ; sur l'autoradio il passe par le téléphone connecté en Bluetooth. */
    @JavascriptInterface
    fun call(number: String) {
        if (number.isBlank()) return
        main.post {
            val uri = Uri.fromParts("tel", number, null)
            val action = if (has(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
            try {
                act.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                try {
                    act.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e2: Exception) {
                }
            }
        }
    }
    // ---------- assistant vocal : reconnaissance ----------

    private var recognizer: SpeechRecognizer? = null

    @JavascriptInterface
    fun canListen(): Boolean = SpeechRecognizer.isRecognitionAvailable(act)

    /** Écoute une phrase en français. Évènements « speech » : start, rms, partial, final, error. */
    @JavascriptInterface
    fun listen() {
        main.post {
            if (!has(Manifest.permission.RECORD_AUDIO)) {
                act.requestAppPermissions()
                emit("speech", JSONObject().put("type", "error").put("code", "permission").toString())
                return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(act)) {
                emit("speech", JSONObject().put("type", "error").put("code", "unavailable").toString())
                return@post
            }
            WakeWord.pause()   // libère le micro pour la dictée
            listenRetry = 0
            // petit délai : le micro doit être vraiment rendu par l'écoute du mot « Polo »
            main.postDelayed({ startRecognizer() }, 350)
        }
    }

    private var listenRetry = 0

    private fun startRecognizer() {
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(act)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = emit("speech", "{\"type\":\"start\"}")
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) = emit("speech", "{\"type\":\"rms\",\"level\":$rmsdB}")
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    // micro encore occupé : on réessaie une fois au lieu de répondre « pas compris »
                    if (listenRetry < 1 && (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_CLIENT)) {
                        listenRetry++
                        main.postDelayed({ startRecognizer() }, 600)
                        return
                    }
                    val code = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nomatch"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
                        else -> "error"
                    }
                    emit("speech", JSONObject().put("type", "error").put("code", code).toString())
                    WakeWord.resume()
                }
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    emit("speech", JSONObject().put("type", "final").put("text", t).toString())
                    WakeWord.resume()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    emit("speech", JSONObject().put("type", "partial").put("text", t).toString())
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // laisse finir la phrase : on coupait trop tôt
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            r.startListening(intent)
    }

    @JavascriptInterface
    fun stopListening() {
        main.post { recognizer?.cancel(); WakeWord.resume() }
    }

    // ---------- assistant vocal : lecture à voix haute ----------

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private fun ensureTts() {
        if (tts != null) return
        // moteur Google en priorité (voix naturelles) ; sinon le moteur par défaut de l'appareil
        val google = "com.google.android.tts"
        val hasGoogle = try { act.packageManager.getPackageInfo(google, 0); true } catch (e: Exception) { false }
        val init = TextToSpeech.OnInitListener { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.FRANCE
                applyTtsPrefs()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = ttsFinished(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = ttsFinished(utteranceId)
                })
                pendingSpeech?.let { speakNow(it, pendingNav) }
            } else {
                emit("tts", "{\"type\":\"done\"}")
            }
            pendingSpeech = null
        }
        tts = if (hasGoogle) TextToSpeech(act, init, google) else TextToSpeech(act, init)
    }

    /** Meilleure voix française installée : qualité la plus haute, hors ligne, France d'abord. */
    private fun bestFrenchVoice(): android.speech.tts.Voice? = try {
        tts?.voices?.filter { it.locale.language == "fr" && !it.isNetworkConnectionRequired &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }
                .thenBy { it.locale.country != "FR" }.thenBy { it.latency })
            ?.firstOrNull()
    } catch (e: Exception) { null }

    /** Applique le timbre choisi (voix, vitesse, hauteur) : vaut pour les messages et pour le GPS. */
    private fun applyTtsPrefs() {
        val p = MainActivity.prefs(act)
        val id = p.getString("ttsVoice", null)
        try {
            val v = if (id != null) tts?.voices?.firstOrNull { it.name == id } else null
            val best = v ?: bestFrenchVoice()
            if (best != null) tts?.voice = best else tts?.language = Locale.FRANCE
        } catch (e: Exception) {
        }
        tts?.setSpeechRate(p.getFloat("ttsRate", 1f))
        tts?.setPitch(p.getFloat("ttsPitch", 1f))
    }

    /** Voix installées : {"ready", "voices":[{id, label}] (françaises), "ja":[{id, label}] (japonaises)}. */
    @JavascriptInterface
    fun ttsVoices(): String {
        main.post { ensureTts() }
        val out = JSONObject().put("ready", ttsReady)
        val arr = JSONArray()
        val ja = JSONArray()
        if (ttsReady) try {
            val local = tts?.voices?.filter { !it.isNetworkConnectionRequired } ?: emptyList()
            val fr = local.filter { it.locale.language == "fr" }
                .sortedWith(compareBy({ it.locale.country != "FR" }, { it.name }))
            val region = mapOf("FR" to "", "CA" to " · Québec", "BE" to " · Belgique", "CH" to " · Suisse")
            fr.forEachIndexed { i, v ->
                arr.put(JSONObject().put("id", v.name).put("label", "Voix ${i + 1}" + (region[v.locale.country] ?: " · ${v.locale.country}")))
            }
            local.filter { it.locale.language == "ja" }.sortedBy { it.name }.forEach { v ->
                ja.put(JSONObject().put("id", v.name).put("label", v.name))
            }
        } catch (e: Exception) {
        }
        return out.put("voices", arr).put("ja", ja).toString()
    }

    /** Règle le timbre : voix ("" = française par défaut), vitesse et hauteur (1 = normal). */
    @JavascriptInterface
    fun setTtsStyle(voice: String, rate: Double, pitch: Double) {
        MainActivity.prefs(act).edit()
            .putString("ttsVoice", voice.ifEmpty { null })
            .putFloat("ttsRate", rate.toFloat().coerceIn(0.4f, 2.5f))
            .putFloat("ttsPitch", pitch.toFloat().coerceIn(0.25f, 2.5f))
            .apply()
        main.post { ensureTts(); if (ttsReady) applyTtsPrefs() }
    }

    private var pendingNav = false

    private fun ttsFinished(id: String?) {
        main.post { audioCoord.abandonFocus() }
        emit("tts", JSONObject().put("type", "done").put("id", id ?: "").toString())
    }

    private fun speakNow(text: String, nav: Boolean) {
        val mode = MainActivity.prefs(act).getString("guidanceMode", "pause") ?: "pause"
        audioCoord.requestFocus(if (nav) mode else "pause", nav)
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (nav) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, if (nav) "nav" else "msg")
    }

    private fun speakAny(text: String, nav: Boolean) {
        main.post {
            ensureTts()
            if (ttsReady) speakNow(text, nav) else {
                pendingSpeech = text
                pendingNav = nav
            }
        }
    }

    /** Lit un message à voix haute (la musique se met en pause pendant la lecture). */
    @JavascriptInterface
    fun speak(text: String) = speakAny(text, false)

    /** Annonce du GPS (la musique baisse ou se met en pause selon le réglage). */
    @JavascriptInterface
    fun speakNav(text: String) = speakAny(text, true)

    /** "duck" = baisser la musique, "pause" = la mettre en pause pendant les annonces. */
    @JavascriptInterface
    fun setGuidanceMode(mode: String) {
        MainActivity.prefs(act).edit().putString("guidanceMode", if (mode == "duck") "duck" else "pause").apply()
    }

    @JavascriptInterface
    fun getGuidanceMode(): String = MainActivity.prefs(act).getString("guidanceMode", "pause") ?: "pause"

    // ---------- démarrage automatique ----------

    @JavascriptInterface
    fun getAutostart(): Boolean = MainActivity.prefs(act).getBoolean("autostart", true)

    /** Active le lancement au démarrage ; Android exige l'autorisation « afficher par-dessus les autres applis ». */
    @JavascriptInterface
    fun setAutostart(on: Boolean) {
        MainActivity.prefs(act).edit().putBoolean("autostart", on).apply()
        if (on && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(act)) {
            main.post {
                try {
                    act.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                }
            }
        }
    }

    @JavascriptInterface
    fun stopSpeaking() {
        main.post { tts?.stop() }
    }

    // ---------- son & écran ----------

    private val audio get() = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun streamOf(kind: String) = if (kind == "call") AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    /** Volume en pourcentage (0–100) : kind = "media" ou "call". */
    @JavascriptInterface
    fun getVolume(kind: String): Int {
        val s = streamOf(kind)
        val max = audio.getStreamMaxVolume(s).coerceAtLeast(1)
        return Math.round(audio.getStreamVolume(s) * 100f / max)
    }

    @JavascriptInterface
    fun setVolume(kind: String, percent: Int) {
        main.post {
            val s = streamOf(kind)
            val max = audio.getStreamMaxVolume(s)
            try {
                audio.setStreamVolume(s, Math.round(percent.coerceIn(0, 100) * max / 100f), 0)
            } catch (e: Exception) {
            }
        }
    }

    /** Luminosité de l'écran pour cette application (0–100). */
    @JavascriptInterface
    fun setBrightness(percent: Int) {
        main.post {
            val lp = act.window.attributes
            lp.screenBrightness = (percent.coerceIn(5, 100)) / 100f
            act.window.attributes = lp
        }
    }

    @JavascriptInterface
    fun openSettings(which: String) {
        val action = when (which) {
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        main.post {
            try {
                val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (which == "apps") i.data = Uri.fromParts("package", act.packageName, null)
                act.startActivity(i)
            } catch (e: Exception) {
            }
        }
    }

    /** Appelle la messagerie vocale du téléphone. */
    @JavascriptInterface
    fun callVoicemail() {
        main.post {
            val uri = Uri.fromParts("voicemail", "", null)
            val action = if (has(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
            try {
                act.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
            }
        }
    }

    fun release() {
        audioCoord.stop()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }
}
