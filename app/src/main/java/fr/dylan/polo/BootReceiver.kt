package fr.dylan.polo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Lance Polo Radio au démarrage de l'autoradio si l'option est activée dans les Réglages. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!MainActivity.prefs(context).getBoolean("autostart", true)) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
        }
    }
}
