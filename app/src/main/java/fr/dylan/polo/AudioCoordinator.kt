package fr.dylan.polo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * Gère le son autour de la musique :
 *  - pendant une annonce du GPS : la musique baisse (ou se met en pause), puis revient ;
 *  - pendant un appel : la musique se met en pause, puis reprend à la fin de l'appel.
 */
class AudioCoordinator(private val ctx: Context, private val onCall: (String, String?) -> Unit) {

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    // ---------- annonces vocales ----------

    /** mode = "duck" (baisser) ou "pause" */
    @Suppress("DEPRECATION")
    fun requestFocus(mode: String, navigation: Boolean) {
        val gain = if (mode == "duck") AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        val usage = if (navigation) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_ASSISTANT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            focusRequest = req
            audio.requestAudioFocus(req)
        } else {
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, gain)
        }
    }

    @Suppress("DEPRECATION")
    fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            audio.abandonAudioFocus(focusListener)
        }
    }

    // ---------- appels ----------

    private var inCall = false
    private var callState = "idle"
    private var phoneSaysCall = false
    private var lastNumber: String? = null
    private var pausedByCall = false
    private var telephony: TelephonyManager? = null

    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (!phoneNumber.isNullOrBlank()) lastNumber = phoneNumber
            setCall(when (state) {
                TelephonyManager.CALL_STATE_RINGING -> "ringing"
                TelephonyManager.CALL_STATE_OFFHOOK -> "active"
                else -> "idle"
            })
        }
    }

    /** Vérifie aussi le mode audio : sur beaucoup d'autoradios, un appel Bluetooth le passe en « appel ». */
    private val poll = object : Runnable {
        override fun run() {
            val m = audio.mode
            val st = when (m) {
                AudioManager.MODE_RINGTONE -> "ringing"
                AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> "active"
                else -> "idle"
            }
            // le mode audio sert de secours quand l'état téléphone ne dit rien
            if (st != callState && (st != "idle" || !phoneSaysCall)) setCall(st, fromPoll = true)
            main.postDelayed(this, 1000)
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        try {
            telephony = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephony?.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            telephony = null
        }
        main.removeCallbacks(poll)
        main.post(poll)
    }

    private fun setCall(state: String, fromPoll: Boolean = false) {
        if (!fromPoll) phoneSaysCall = state != "idle"
        if (state == callState) return
        callState = state
        val active = state != "idle"
        if (active) {
            if (!inCall && MediaHub.isPlaying()) {
                MediaHub.pause()
                pausedByCall = true
            }
            inCall = true
            onCall(state, lastNumber)
        } else {
            inCall = false
            lastNumber = null
            if (pausedByCall) main.postDelayed({ MediaHub.play() }, 1500)
            pausedByCall = false
            onCall("idle", null)
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        main.removeCallbacks(poll)
        try {
            telephony?.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
        }
        abandonFocus()
    }
}
