package app.catchwave;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Only enables access to active media sessions. Notification contents are never read. */
public final class MediaAccessService extends NotificationListenerService {
    @Override public void onListenerConnected(){SessionModel.INSTANCE.notifyChanged();}
    @Override public void onListenerDisconnected(){SessionModel.INSTANCE.notifyChanged();}
    @Override public void onNotificationPosted(StatusBarNotification notification){}
    @Override public void onNotificationRemoved(StatusBarNotification notification){}
}
