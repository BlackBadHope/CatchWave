package app.catchwave;
import android.service.notification.NotificationListenerService;

/** Only enables access to active media sessions. Notification contents are never read. */
public final class MediaAccessService extends NotificationListenerService {
    @Override public void onListenerConnected(){SessionModel.INSTANCE.notifyChanged();}
    @Override public void onListenerDisconnected(){SessionModel.INSTANCE.notifyChanged();}
}
