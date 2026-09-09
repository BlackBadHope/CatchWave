package app.catchwave;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SessionModel {
    public static final SessionModel INSTANCE=new SessionModel();
    public volatile String status="Музыка рядом. Продолжи у себя.",detail="Нажми кнопку рядом с источником музыки.",diagnostic="";
    public volatile boolean running,live,aligned,needsOpen,manualHold,guardingTrack;
    public volatile boolean measuringAudio,audioVerified;
    public volatile double audioLagMs=Double.NaN;
    public volatile int progress;
    public volatile double level;
    public volatile long errorMs=Long.MAX_VALUE;
    public volatile long tEstMs=Long.MIN_VALUE,tSessionAfterSeekMs=Long.MIN_VALUE,seekLagMs=Long.MIN_VALUE;
    public volatile Track track;
    public volatile boolean compatibleLaunchRequested;
    public volatile long launchTicket;
    private long traceStart;
    private final java.util.ArrayDeque<String> events=new java.util.ArrayDeque<>();
    public synchronized void resetTrace(){events.clear();traceStart=android.os.SystemClock.elapsedRealtime();diagnostic="";tEstMs=tSessionAfterSeekMs=seekLagMs=Long.MIN_VALUE;}
    public synchronized void record(String event){
        if(events.size()>=80)events.removeFirst();
        events.addLast("+"+(android.os.SystemClock.elapsedRealtime()-traceStart)+" мс · "+event);
        diagnostic=String.join("\n",events);
    }
    public String report(){
        Track t=track;
        return AppIdentity.label()+"\nrunning="+running+" live="+live+" aligned="+aligned+" manualHold="+manualHold+" guardingTrack="+guardingTrack+" measuringAudio="+measuringAudio+" audioLagMs="+audioLagMs+" audioVerified="+audioVerified+"\n"+status+"\n"+detail
            +(t==null?"":"\nТрек: "+t.title+" / "+t.artist+"\nКаталог: "+t.playbackTitle+" / "+t.playbackArtist+"\nСсылка: "+t.youtubeUrl+"\noffsetMs="+t.offsetMs+" anchorMs="+t.anchorMs+" sampleMs="+t.sampleDurationMs+" skew="+t.timeSkew)
            +"\nt_est="+(tEstMs==Long.MIN_VALUE?"—":Long.toString(tEstMs))+" t_session_after_seek="+(tSessionAfterSeekMs==Long.MIN_VALUE?"—":Long.toString(tSessionAfterSeekMs))
            +" Δ="+(errorMs==Long.MAX_VALUE?"не измерена":Long.toString(errorMs))+" seek_lag="+(seekLagMs==Long.MIN_VALUE?"—":Long.toString(seekLagMs))
            +"\nРазница таймкодов, мс: "+(errorMs==Long.MAX_VALUE?"не измерена":Long.toString(errorMs))+"\n"+diagnostic;
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    public void add(Runnable r){listeners.add(r);}
    public void remove(Runnable r){listeners.remove(r);}
    public void update(String status,String detail) {if(this.status.equals(status)&&this.detail.equals(detail))return;if(!this.status.equals(status))record("Статус: "+status);this.status=status;this.detail=detail;notifyChanged();}
    public void notifyChanged(){main.post(()->{for(Runnable r:listeners)r.run();});}
}
