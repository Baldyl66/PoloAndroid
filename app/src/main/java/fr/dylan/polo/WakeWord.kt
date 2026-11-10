package fr.dylan.polo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * Réveil de l'assistant quand on dit « Polo » : reconnaissance hors ligne (Vosk),
 * limitée au seul mot « polo » pour éviter les faux départs. Rien n'est enregistré ni envoyé.
 * Le modèle français est ajouté aux ressources par GitHub Actions (dossier assets/vosk-fr).
 */
object WakeWord {

    private const val ASSET_DIR = "vosk-fr"
    private const val MODEL_VERSION = "small-fr-0.22"

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var onWake: (() -> Unit)? = null
    @Volatile private var model: Model? = null
    @Volatile private var loading = false
    private var service: SpeechService? = null
    @Volatile private var enabled = false
    @Volatile private var paused = false
    private var lastWake = 0L

    /** Vrai si le modèle est présent dans l'APK. */
    fun available(ctx: Context): Boolean = try {
        ctx.assets.list(ASSET_DIR)?.isNotEmpty() == true
    } catch (e: Exception) { false }

    fun start(ctx: Context, wake: () -> Unit) {
        app = ctx.applicationContext
        onWake = wake
        enabled = true
        if (model != null) { main.post { listen() }; return }
        if (loading || !available(ctx)) return
        loading = true
        Thread({
            try {
                val dir = copyModel(ctx.applicationContext)
                model = Model(dir.absolutePath)
                main.post { listen() }
            } catch (e: Exception) {
            }
            loading = false
        }, "polo-wake-load").start()
    }

    fun stop() {
        enabled = false
        main.post { close() }
    }

    /** Libère le micro (pendant une dictée, un appel…). */
    fun pause() {
        paused = true
        main.post { close() }
    }

    /** Reprend l'écoute du mot « Polo ». */
    fun resume() {
        paused = false
        main.postDelayed({ listen() }, 600)
    }

    private fun listen() {
        val m = model ?: return
        if (!enabled || paused || service != null) return
        try {
            val rec = Recognizer(m, 16000f, "[\"polo\", \"[unk]\"]")
            val s = SpeechService(rec, 16000f)
            service = s
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
                override fun onResult(hypothesis: String?) = check(hypothesis, "text")
                override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
                override fun onError(exception: Exception?) { close(); main.postDelayed({ listen() }, 3000) }
                override fun onTimeout() { close(); listen() }
            })
        } catch (e: Exception) {
            close()
        }
    }

    private fun check(json: String?, key: String) {
        val text = try { JSONObject(json ?: return).optString(key) } catch (e: Exception) { return }
        if (!text.split(" ").contains("polo")) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWake < 3000) return
        lastWake = now
        pause()
        onWake?.invoke()
    }

    private fun close() {
        try { service?.stop(); service?.shutdown() } catch (e: Exception) {}
        service = null
    }

    /** Copie le modèle des ressources de l'APK vers la mémoire de l'appli (une seule fois). */
    private fun copyModel(ctx: Context): File {
        val dir = File(ctx.filesDir, ASSET_DIR)
        val mark = File(dir, ".version")
        if (mark.exists() && mark.readText() == MODEL_VERSION) return dir
        dir.deleteRecursively()
        copyAsset(ctx, ASSET_DIR, dir)
        mark.writeText(MODEL_VERSION)
        return dir
    }

    private fun copyAsset(ctx: Context, path: String, dest: File) {
        val children = ctx.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { inp -> dest.outputStream().use { inp.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (c in children) copyAsset(ctx, "$path/$c", File(dest, c))
        }
    }
}
