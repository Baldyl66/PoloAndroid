package fr.dylan.polo.compagnon

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Reçoit les notifications du téléphone (accès à activer dans les réglages d'Android). */
class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        NotifStore.service = this
        NotifStore.clear()
        val r = currentRanking
        try {
            activeNotifications?.forEach { sbn -> NotifStore.onPosted(this, sbn, ranking(r, sbn), initial = true) }
        } catch (e: Exception) {
        }
        NotifStore.loaded()
        LinkServer.start(this)
    }

    override fun onListenerDisconnected() {
        NotifStore.service = null
        LinkServer.stop()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn != null) try { NotifStore.onPosted(this, sbn, ranking(rankingMap, sbn), initial = false) } catch (e: Exception) {}
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) NotifStore.onRemoved(sbn)
    }

    private fun ranking(map: RankingMap?, sbn: StatusBarNotification): Ranking? {
        val r = Ranking()
        return if (map?.getRanking(sbn.key, r) == true) r else null
    }
}
