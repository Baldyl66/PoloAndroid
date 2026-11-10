package fr.dylan.polo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Voix naturelles intégrées à l'appli (réseau de neurones Piper, via sherpa-onnx), 100 % hors ligne.
 * Rien à installer sur l'autoradio. Les voix sont ajoutées aux ressources par GitHub Actions :
 *   assets/tts-fr/espeak-ng-data/   (prononciation)
 *   assets/tts-fr/femme/model.onnx + tokens.txt
 *   assets/tts-fr/homme/model.onnx + tokens.txt
 */
object NeuralTts {
    private const val ASSET_DIR = "tts-fr"
    private const val VERSION = "piper-siwis-tom-1"
    val VOICES = listOf("femme", "homme")

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "polo-tts").apply { priority = Thread.MAX_PRIORITY } }
    private var engine: OfflineTts? = null
    private var engineVoice: String? = null
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null

    fun available(ctx: Context): Boolean = try {
        ctx.assets.list("$ASSET_DIR/femme")?.contains("model.onnx") == true
    } catch (e: Exception) { false }

    /** Prépare la voix en arrière-plan (copie + chargement), pour que la 1re phrase parte vite. */
    fun warmUp(ctx: Context, voice: String) = worker.execute { try { load(ctx, voice) } catch (e: Throwable) {} }

    /**
     * Lit [text] avec la voix « femme » ou « homme ». [rate] : 1 = normal.
     * [onDone] est appelé à la fin (ou en cas d'erreur, avec false).
     */
    fun speak(ctx: Context, text: String, voice: String, rate: Float, usage: Int, onDone: (Boolean) -> Unit) {
        val myGen = ++generation
        stopTrack()
        worker.execute {
            var ok = false
            try {
                val tts = load(ctx, voice)
                if (myGen != generation) return@execute
                // phrase fabriquée EN ENTIER avant de la jouer : en flux continu, le calcul était plus lent
                // que la lecture sur certains appareils et la voix sortait hachée (coupures)
                val t0 = android.os.SystemClock.elapsedRealtime()
                val audio = tts.generate(text, sid = 0, speed = rate.coerceIn(0.5f, 2f))
                val sr = audio.sampleRate
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                android.util.Log.i("PoloTts", "voix $voice : ${audio.samples.size * 1000L / maxOf(sr, 1)} ms d'audio en $ms ms")
                if (myGen != generation || audio.samples.isEmpty()) return@execute
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), sr * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = t
                t.play()
                // petit silence au début : évite que la 1re syllabe soit mangée par l'ouverture du son
                val lead = FloatArray(sr / 20)
                t.write(lead, 0, lead.size, AudioTrack.WRITE_BLOCKING)
                var off = 0
                val chunk = sr / 4
                while (off < audio.samples.size && myGen == generation) {
                    val n = minOf(chunk, audio.samples.size - off)
                    t.write(audio.samples, off, n, AudioTrack.WRITE_BLOCKING)
                    off += n
                }
                if (myGen == generation) {
                    // attend que le son en tampon soit vraiment joué
                    t.stop()
                    val end = System.currentTimeMillis() + 3000
                    while (t.playState == AudioTrack.PLAYSTATE_PLAYING && System.currentTimeMillis() < end) Thread.sleep(30)
                    ok = true
                }
            } catch (e: Throwable) {
                ok = false
            } finally {
                if (myGen == generation) { releaseTrack(); onDone(ok) }
            }
        }
    }

    fun stop() {
        generation++
        stopTrack()
    }

    private fun stopTrack() {
        try { track?.pause(); track?.flush() } catch (e: Exception) {}
        releaseTrack()
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        try { t.stop() } catch (e: Exception) {}
        try { t.release() } catch (e: Exception) {}
    }

    private fun load(ctx: Context, voice: String): OfflineTts {
        val v = if (voice in VOICES) voice else "femme"
        engine?.let { if (engineVoice == v) return it }
        val dir = copyModels(ctx.applicationContext)
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, "$v/model.onnx").absolutePath,
                    tokens = File(dir, "$v/tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath,
                ),
                numThreads = maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors())),
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        engine?.release()
        engine = null
        val e = OfflineTts(null, cfg)
        engine = e
        engineVoice = v
        return e
    }

    @Synchronized
    private fun copyModels(ctx: Context): File {
        val dir = File(ctx.filesDir, ASSET_DIR)
        val mark = File(dir, ".version")
        if (mark.exists() && mark.readText() == VERSION) return dir
        dir.deleteRecursively()
        copyAsset(ctx, ASSET_DIR, dir)
        mark.writeText(VERSION)
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
