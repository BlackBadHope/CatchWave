package app.catchwave;

import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import java.lang.reflect.*;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class ServiceBoundaryTest {
    private final SessionModel m=SessionModel.INSTANCE;
    private ServiceController<SyncService> life;
    private SyncService service;
    private MediaSession player;
    private long now;
    @Before public void setup()throws Exception{
        m.track=null;m.live=false;m.manualHold=false;m.aligned=false;m.guardingTrack=false;m.running=true;m.needsAudioRefine=false;
        life=Robolectric.buildService(SyncService.class).create();service=life.get();player=new MediaSession(service,"end");
        now=SystemClock.elapsedRealtime();m.track=new Track("one","Song","Artist","",10000,now,0);
        metadata("Song","one");state(PlaybackState.STATE_PLAYING,10000,now);
        set("bridge",new MediaBridge(service){public MediaController controller(){return player.getController();}});
        set("active",true);set("started",now);set("initialSeek",true);set("lastSeek",now-600);set("lastMatch",now);set("acquireStarted",now);
    }
    @After public void cleanup(){life.destroy();player.release();m.track=null;m.running=false;m.live=false;m.guardingTrack=false;m.manualHold=false;m.needsAudioRefine=false;}
    private void set(String key,Object value)throws Exception{Field f=SyncService.class.getDeclaredField(key);f.setAccessible(true);f.set(service,value);}
    private Object get(String key)throws Exception{Field f=SyncService.class.getDeclaredField(key);f.setAccessible(true);return f.get(service);}
    private void tick()throws Exception{Method f=SyncService.class.getDeclaredMethod("syncTick");f.setAccessible(true);f.invoke(service);}
    private void metadata(String title,String id){Shadows.shadowOf(player.getController()).setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putString(MediaMetadata.METADATA_KEY_ARTIST,"Artist").putString(MediaMetadata.METADATA_KEY_MEDIA_ID,id).putLong(MediaMetadata.METADATA_KEY_DURATION,100000).build());}
    private void state(int state,long position,long at){Shadows.shadowOf(player.getController()).setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SEEK_TO).setState(state,position,1,at).build());}
    @Test public void oneShotRetainsEndGuardAfterSuccessfulSeek()throws Exception{tick();assertTrue(m.running);assertTrue(m.guardingTrack);assertNotNull(get("endGuard"));assertTrue(m.status.contains("один трек"));assertTrue(m.needsAudioRefine);}
    @Test public void oneShotStopsNextQueueItemAndExitsAfterAcknowledgement()throws Exception{
        tick();metadata("Next","two");state(PlaybackState.STATE_PLAYING,0,now);tick();assertEquals(PlaybackState.ACTION_PAUSE,Shadows.shadowOf(player.getController().getTransportControls()).getLastPerformedAction());
        state(PlaybackState.STATE_PAUSED,0,SystemClock.elapsedRealtime());tick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(800));tick();
        assertFalse(m.running);assertFalse(m.guardingTrack);assertTrue(m.status.contains("музыка на паузе"));
    }
    @Test public void liveWaitsForNewSourceAfterPausingAutoplay()throws Exception{
        m.live=true;tick();metadata("Next","two");state(PlaybackState.STATE_PLAYING,0,now);tick();state(PlaybackState.STATE_PAUSED,0,SystemClock.elapsedRealtime());tick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(800));tick();
        assertTrue(m.running);assertEquals(true,get("waitingNextSource"));assertTrue(m.status.contains("Жду следующий"));
        tick();assertEquals(PlaybackState.ACTION_PAUSE,Shadows.shadowOf(player.getController().getTransportControls()).getLastPerformedAction());
    }
    @Test public void explicitStopReleasesEndGuard()throws Exception{tick();service.onStartCommand(new Intent().setAction(SyncService.STOP),0,1);assertNull(get("endGuard"));assertFalse(m.guardingTrack);assertFalse(m.running);}
    @Test public void oneShotPausesPlayerWhileListeningSoQueueCannotAdvance()throws Exception{
        m.track=null;set("ownsQueue",false);set("resumeFresh",true);
        tick();assertEquals(PlaybackState.ACTION_PAUSE,Shadows.shadowOf(player.getController().getTransportControls()).getLastPerformedAction());
        assertEquals(true,get("ownsQueue"));
    }
    @Test public void oneShotPausesForeignQueueItemDuringHandoff()throws Exception{
        set("ownsQueue",false);set("initialSeek",false);set("lastSeek",0L);set("resumeFresh",false);
        metadata("Wake Up The President","other");state(PlaybackState.STATE_PLAYING,1000,now);
        tick();assertEquals(PlaybackState.ACTION_PAUSE,Shadows.shadowOf(player.getController().getTransportControls()).getLastPerformedAction());
        assertFalse(m.aligned);
        assertEquals(true,get("ownsQueue"));
    }
    @Test public void failedSyncHaltsQueueInsteadOfLeavingAutoplay()throws Exception{
        set("ownsQueue",true);set("keepPlayerOnExit",false);set("active",true);
        metadata("Wake Up The President","other");state(PlaybackState.STATE_PLAYING,1000,now);
        Method finish=SyncService.class.getDeclaredMethod("finish",String.class,String.class);finish.setAccessible(true);
        finish.invoke(service,"Не удалось подтвердить синхронизацию","очередь");
        assertEquals(PlaybackState.ACTION_PAUSE,Shadows.shadowOf(player.getController().getTransportControls()).getLastPerformedAction());
        assertFalse(m.diagnostic.contains("Возвращено прежнее воспроизведение"));
    }
    @Test public void failedSyncDoesNotMuteTheMatchedRecording()throws Exception{
        set("ownsQueue",true);set("keepPlayerOnExit",false);set("active",true);
        Method finish=SyncService.class.getDeclaredMethod("finish",String.class,String.class);finish.setAccessible(true);
        finish.invoke(service,"Не удалось подтвердить синхронизацию","очередь");
        assertNotEquals(PlaybackState.ACTION_PAUSE,Shadows.shadowOf(player.getController().getTransportControls()).getLastPerformedAction());
    }
    @Test public void failedListenDoesNotResumePreviousPlaylist()throws Exception{
        m.track=null;set("ownsQueue",true);set("keepPlayerOnExit",false);set("active",true);set("resumeFresh",true);
        CapturePause pause=new CapturePause(player.getController());
        state(PlaybackState.STATE_PAUSED,10000,now);
        assertTrue(pause.ready());
        set("capturePause",pause);
        Method finish=SyncService.class.getDeclaredMethod("finish",String.class,String.class);finish.setAccessible(true);
        finish.invoke(service,"Не получилось распознать","очередь");
        assertNotEquals(PlaybackState.ACTION_PLAY,Shadows.shadowOf(player.getController().getTransportControls()).getLastPerformedAction());
        assertFalse(m.diagnostic.contains("Возвращено прежнее воспроизведение"));
    }
    @Test public void explicitStopMayRestoreListenPause()throws Exception{
        m.track=null;set("keepPlayerOnExit",false);set("active",true);set("resumeFresh",true);
        CapturePause pause=new CapturePause(player.getController());
        state(PlaybackState.STATE_PAUSED,10000,now);
        assertTrue(pause.ready());
        set("capturePause",pause);
        service.onStartCommand(new Intent().setAction(SyncService.STOP),0,1);
        assertEquals(PlaybackState.ACTION_PLAY,Shadows.shadowOf(player.getController().getTransportControls()).getLastPerformedAction());
        assertFalse(m.running);
    }
    @Test public void seekSettleLogsEstimateSessionDeltaAndLag()throws Exception{
        set("initialSeek",false);set("lastSeek",0L);set("seekAttempts",0);set("awaitingSeekSettle",false);set("learnedSeekLag",-1L);
        m.resetTrace();tick();
        assertEquals(true,get("awaitingSeekSettle"));
        assertTrue(m.diagnostic.contains("t_est="));
        assertTrue(m.diagnostic.contains("commanded="));
        long sent=(Long)get("seekSentAt");long commanded=(Long)get("seekCommanded");
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(250));
        long later=SystemClock.elapsedRealtime();
        state(PlaybackState.STATE_PLAYING,commanded,later);
        tick();
        assertTrue(m.diagnostic.contains("t_session_after_seek="));
        assertTrue(m.diagnostic.contains("Δ="));
        assertTrue(m.diagnostic.contains("seek_lag="));
        assertTrue(m.diagnostic.contains("Уточнить по звуку:"));
        assertTrue(m.seekLagMs>=0);
    }
}
