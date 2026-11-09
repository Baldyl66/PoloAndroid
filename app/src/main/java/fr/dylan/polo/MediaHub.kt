package fr.dylan.polo

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Suit le lecteur de musique actif sur l'autoradio (Spotify, YouTube Music, Deezer,
 * musique Bluetooth du téléphone…) et permet de le piloter.
 */
object MediaHub {
    private var appCtx: Context? = null
    private var sink: ((String) -> Unit)? = null
    private var controller: MediaController? = null
    private var msm: MediaSessionManager? = null
    private var listening = false
    private val main = Handler(Looper.getMainLooper())
    private var lastArtKey: String? = null
    private var lastArt: String? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onSessionDestroyed() {
            controller = null
            appCtx?.let { refresh(it) }
        }
    }

    fun init(ctx: Context, sink: (String) -> Unit) {
        appCtx = ctx.applicationContext
        this.sink = sink
        refresh(ctx)
    }

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ctx.packageName)
    }

    fun refresh(ctx: Context) {
        val c = ctx.applicationContext
        appCtx = c
        if (!hasAccess(c)) {
            push()
            return
        }
        try {
            val m = msm ?: (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).also { msm = it }
            val comp = ComponentName(c, MediaListener::class.java)
            if (!listening) {
                m.addOnActiveSessionsChangedListener(sessionsListener, comp, main)
                listening = true
            }
            pick(m.getActiveSessions(comp))
        } catch (e: SecurityException) {
            push()
        }
    }

    private fun pick(list: List<MediaController>?) {
        val best = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        push()
    }

    fun stateJson(): String {
        val o = JSONObject()
        val ctx = appCtx
        o.put("access", ctx != null && hasAccess(ctx))
        val c = controller
        if (c == null) {
            o.put("active", false)
            return o.toString()
        }
        val md = c.metadata
        val st = c.playbackState
        o.put("active", true)
        o.put("app", c.packageName)
        o.put("appName", appLabel(c.packageName))
        o.put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
        o.put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
        o.put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
        o.put("duration", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        var pos = st?.position ?: 0L
        if (st != null && st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        o.put("position", pos)
        o.put("playing", st?.state == PlaybackState.STATE_PLAYING)
        o.put("art", artFor(md) ?: JSONObject.NULL)
        return o.toString()
    }

    private fun appLabel(pkg: String): String {
        val pm = appCtx?.packageManager ?: return pkg
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun artFor(md: MediaMetadata?): String? {
        if (md == null) return null
        val key = (md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "") + "|" + (md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
        if (key == lastArtKey) return lastArt
        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        lastArtKey = key
        lastArt = bmp?.let {
            val scaled = Bitmap.createScaledBitmap(it, 256, 256, true)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        }
        return lastArt
    }

    private fun push() {
        sink?.invoke(stateJson())
    }

    fun isPlaying(): Boolean = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun play() = main.post { controller?.transportControls?.play() }
    fun pause() = main.post { controller?.transportControls?.pause() }
    fun next() = main.post { controller?.transportControls?.skipToNext() }
    fun previous() = main.post { controller?.transportControls?.skipToPrevious() }
    fun seek(ms: Long) = main.post { controller?.transportControls?.seekTo(ms) }

    fun release() {
        try {
            msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        controller?.unregisterCallback(callback)
        controller = null
        listening = false
    }
}
