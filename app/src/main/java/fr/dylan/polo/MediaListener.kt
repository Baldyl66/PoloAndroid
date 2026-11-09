package fr.dylan.polo

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Service vide : Android exige qu'une application soit « lecteur de notifications »
 * pour pouvoir voir les sessions de musique des autres applications
 * (Spotify, YouTube Music, Deezer, lecteur Bluetooth…).
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        MediaHub.refresh(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        MediaHub.refresh(applicationContext)
        if (sbn != null) try { PhoneHub.onNotificationPosted(sbn) } catch (e: Exception) {}
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) try { PhoneHub.onNotificationRemoved(sbn) } catch (e: Exception) {}
    }
}
