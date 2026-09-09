package app.catchwave;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Returns only after this launch's own track has a confirmed seek. */
final class PlayerLaunch {
    private final Activity activity;
    private final SessionModel model;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long deadline,ticket;
    private String key;
    private boolean pending;
    PlayerLaunch(Activity activity,SessionModel model){this.activity=activity;this.model=model;}
    void open(Track track,boolean returnAfterSync){
        cancel();
        key=track.key;ticket=model.launchTicket;deadline=SystemClock.elapsedRealtime()+20000;pending=returnAfterSync;
        model.compatibleLaunchRequested=false;
        activity.startActivity(MediaBridge.openIntent(track).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
        if(pending)handler.postDelayed(check,300);
    }
    private final Runnable check=new Runnable(){public void run(){
        if(!pending||activity.isFinishing()||activity.isDestroyed())return;
        if(model.manualHold||model.launchTicket!=ticket||model.track==null||!key.equals(model.track.key)){cancel();return;}
        if(model.aligned||!model.running||SystemClock.elapsedRealtime()>=deadline){
            cancel();
            if(!MainActivity.visible)activity.startActivity(new Intent(activity,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_NO_ANIMATION));
            return;
        }
        handler.postDelayed(this,200);
    }};
    void cancel(){pending=false;handler.removeCallbacksAndMessages(null);}
}
