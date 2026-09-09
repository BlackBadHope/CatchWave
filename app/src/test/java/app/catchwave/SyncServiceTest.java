package app.catchwave;

import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.MediaController;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class SyncServiceTest {
    @Test public void stalePlayerErrorDoesNotTriggerCompetingLaunch() throws Exception {
        var lifecycle=Robolectric.buildService(SyncService.class).create();SyncService s=lifecycle.get();MediaSession player=new MediaSession(s,"stale");long now=android.os.SystemClock.elapsedRealtime();
        org.robolectric.Shadows.shadowOf(player.getController()).setPlaybackState(new android.media.session.PlaybackState.Builder().setState(7,0,1,now-1000).setErrorMessage("Old failure").build());
        set(s,"bridge",new MediaBridge(s){public MediaController controller(){return player.getController();}});set(s,"active",true);set(s,"lastLaunchRequest",now);set(s,"nativeDeadline",now+4000);set(s,"acquireStarted",now);
        SessionModel.INSTANCE.track=new Track("1","Song","Artist","",10000,1000,0);SessionModel.INSTANCE.manualHold=false;
        Method tick=SyncService.class.getDeclaredMethod("syncTick");tick.setAccessible(true);tick.invoke(s);assertEquals(false,get(s,"resolveRequested"));assertEquals("",get(s,"playerError"));lifecycle.destroy();player.release();SessionModel.INSTANCE.track=null;
    }
    @Test public void rejectingWrongVersionStopsSyncAndClearsConfirmedState(){
        var lifecycle=Robolectric.buildService(SyncService.class).create();SessionModel.INSTANCE.running=true;SessionModel.INSTANCE.aligned=true;
        lifecycle.get().onStartCommand(new Intent().setAction(SyncService.REJECT),0,1);assertFalse(SessionModel.INSTANCE.running);assertFalse(SessionModel.INSTANCE.aligned);assertEquals("Это другая запись",SessionModel.INSTANCE.status);lifecycle.destroy();
    }
    @Test public void freshLiveMatchesDoNotResetUnconfirmedSeekBudget() throws Exception {
        var lifecycle=Robolectric.buildService(SyncService.class).create();SyncService s=lifecycle.get();set(s,"active",true);set(s,"bridge",new NativeBridge(s));set(s,"seekAttempts",3);
        SessionModel.INSTANCE.live=true;SessionModel.INSTANCE.aligned=false;SessionModel.INSTANCE.manualHold=false;SessionModel.INSTANCE.track=new Track("1","Song","Artist","",10000,1000,0);
        accept(s,new Track("1","Song","Artist","",13000,4000,0));assertEquals(3,get(s,"seekAttempts"));lifecycle.destroy();SessionModel.INSTANCE.track=null;SessionModel.INSTANCE.live=false;
    }
    @Test public void unreliableOffsetNeverLaunchesPlayer() throws Exception {
        var lifecycle=Robolectric.buildService(SyncService.class).create();SyncService s=lifecycle.get();NativeBridge b=new NativeBridge(s);set(s,"bridge",b);set(s,"active",true);
        accept(s,new Track("bad","Song","Artist","",10000,1000,.03));assertEquals(0,b.requests);assertFalse(SessionModel.INSTANCE.running);assertEquals("Запись распознана, таймкод ненадёжен",SessionModel.INSTANCE.status);lifecycle.destroy();SessionModel.INSTANCE.track=null;
    }
    @Test public void oneShotResyncDiscardsOldMatchAndInvalidatesOutstandingGeneration() throws Exception {
        var lifecycle=Robolectric.buildService(SyncService.class).create();SyncService s=lifecycle.get();NativeBridge b=new NativeBridge(s);set(s,"bridge",b);set(s,"active",true);set(s,"captureGeneration",4);
        SessionModel.INSTANCE.live=false;SessionModel.INSTANCE.track=new Track("old","Song","Artist","",10000,1000,0);
        s.onStartCommand(new Intent().setAction(SyncService.USER_RESUME),0,1);assertNull(SessionModel.INSTANCE.track);assertEquals(5,get(s,"captureGeneration"));assertEquals(true,get(s,"resumeFresh"));assertEquals(0,b.requests);lifecycle.destroy();
    }
    @Test public void liveIgnoresSingleHalfSecondOutlierButAcceptsRepeatedSourceJump() throws Exception {
        var lifecycle=Robolectric.buildService(SyncService.class).create();SyncService s=lifecycle.get();set(s,"bridge",new NativeBridge(s));set(s,"active",true);
        SessionModel.INSTANCE.live=true;SessionModel.INSTANCE.manualHold=false;Track old=new Track("1","Song","Artist","",10000,1000,0);SessionModel.INSTANCE.track=old;
        accept(s,new Track("1","Song","Artist","",13500,4000,0));assertSame(old,SessionModel.INSTANCE.track);
        Track confirmed=new Track("1","Song","Artist","",16510,7000,0);accept(s,confirmed);assertSame(confirmed,SessionModel.INSTANCE.track);lifecycle.destroy();SessionModel.INSTANCE.track=null;SessionModel.INSTANCE.live=false;
    }
    @Test public void playerErrorHasBoundedExitAlsoInLiveMode() throws Exception {
        var lifecycle=Robolectric.buildService(SyncService.class).create();SyncService s=lifecycle.get();MediaSession player=new MediaSession(s,"error");
        org.robolectric.Shadows.shadowOf(player.getController()).setPlaybackState(new android.media.session.PlaybackState.Builder().setState(7,0,1).setErrorMessage("Cannot play").build());
        set(s,"bridge",new MediaBridge(s){public MediaController controller(){return player.getController();}});set(s,"active",true);set(s,"resolveRequested",true);set(s,"acquireStarted",android.os.SystemClock.elapsedRealtime()-20001);
        SessionModel.INSTANCE.track=new Track("1","Song","Artist","",10000,1000,0);SessionModel.INSTANCE.live=true;SessionModel.INSTANCE.manualHold=false;
        Method tick=SyncService.class.getDeclaredMethod("syncTick");tick.setAccessible(true);tick.invoke(s);assertFalse(SessionModel.INSTANCE.running);assertTrue(SessionModel.INSTANCE.detail.contains("Cannot play"));lifecycle.destroy();player.release();SessionModel.INSTANCE.track=null;SessionModel.INSTANCE.live=false;
    }
    @Test public void serviceWithoutPermissionsNeverStartsListening(){var lifecycle=Robolectric.buildService(SyncService.class).create();SyncService service=lifecycle.get();service.onStartCommand(new Intent(service,SyncService.class).setAction(SyncService.START),0,1);assertFalse(SessionModel.INSTANCE.running);assertEquals("Нужны разрешения",SessionModel.INSTANCE.status);lifecycle.destroy();}
    @Test public void stopClearsPendingLaunchAndRunningState(){var lifecycle=Robolectric.buildService(SyncService.class).create();SessionModel m=SessionModel.INSTANCE;m.running=true;m.needsOpen=true;lifecycle.get().onStartCommand(new Intent().setAction(SyncService.STOP),0,1);assertFalse(m.running);assertFalse(m.needsOpen);lifecycle.destroy();}
    @Test public void newTrackDoesNotInheritPausedFlag() throws Exception {
        var lifecycle=Robolectric.buildService(SyncService.class).create();SyncService s=lifecycle.get();
        set(s,"active",true);set(s,"pausedByUs",true);set(s,"bridge",new NativeBridge(s));SessionModel.INSTANCE.track=null;SessionModel.INSTANCE.manualHold=false;MainActivity.visible=true;
        Method accept=SyncService.class.getDeclaredMethod("accept",Track.class);accept.setAccessible(true);accept.invoke(s,new Track("new","New song","Artist","",1000,0,0));
        assertEquals(false,get(s,"pausedByUs"));assertFalse(SessionModel.INSTANCE.needsOpen);lifecycle.destroy();MainActivity.visible=false;SessionModel.INSTANCE.track=null;
    }
    @Test public void closingServiceReleasesItsSessionState(){var lifecycle=Robolectric.buildService(SyncService.class).create();SessionModel.INSTANCE.running=true;lifecycle.destroy();assertFalse(SessionModel.INSTANCE.running);}
    @Test public void foregroundRecognitionRequestsNativePlayerWithoutLeavingApp() throws Exception {
        var lifecycle=Robolectric.buildService(SyncService.class).create();SyncService s=lifecycle.get();NativeBridge bridge=new NativeBridge(s);
        set(s,"active",true);set(s,"bridge",bridge);SessionModel.INSTANCE.track=null;SessionModel.INSTANCE.manualHold=false;MainActivity.visible=true;
        Track track=new Track("native","Song","Artist","",10000,1000,0);track.sampleDurationMs=3000;
        accept(s,track);assertEquals(1,bridge.requests);assertFalse(SessionModel.INSTANCE.needsOpen);assertEquals(4000L,get(s,"lastMatch"));assertEquals(false,get(s,"resolveRequested"));
        lifecycle.destroy();SessionModel.INSTANCE.track=null;MainActivity.visible=false;
    }
    @Test public void manualPauseSurvivesRecognitionAndResumeWaitsForFreshMatch() throws Exception {
        var lifecycle=Robolectric.buildService(SyncService.class).create();SyncService s=lifecycle.get();NativeBridge bridge=new NativeBridge(s);
        set(s,"active",true);set(s,"bridge",bridge);SessionModel.INSTANCE.live=true;SessionModel.INSTANCE.track=null;
        s.onStartCommand(new Intent().setAction(SyncService.USER_PAUSE),0,1);
        accept(s,new Track("held","Song","Artist","",10000,1000,0));assertTrue(SessionModel.INSTANCE.manualHold);assertEquals(0,bridge.requests);
        s.onStartCommand(new Intent().setAction(SyncService.USER_RESUME),0,2);assertFalse(SessionModel.INSTANCE.manualHold);assertEquals(true,get(s,"resumeFresh"));assertEquals(0,bridge.requests);
        accept(s,new Track("held","Song","Artist","",16000,7000,0));assertEquals(false,get(s,"resumeFresh"));assertEquals(1,bridge.requests);
        lifecycle.destroy();SessionModel.INSTANCE.track=null;SessionModel.INSTANCE.live=false;
    }
    private static void accept(SyncService s,Track t) throws Exception {Method m=SyncService.class.getDeclaredMethod("accept",Track.class);m.setAccessible(true);m.invoke(s,t);}
    private static final class NativeBridge extends MediaBridge {
        int requests;NativeBridge(android.content.Context c){super(c);}
        @Override public MediaController controller(){return null;}
        @Override public boolean requestTrack(Track target){requests++;return true;}
    }
    private static void set(Object o,String name,Object value) throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object get(Object o,String name) throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
}
