package app.catchwave;

import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import java.time.Duration;
import static org.junit.Assert.*;

/** Regression cases originally reproduced in the 0.1.4 audit. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class RegressionTest {
    private final SessionModel m=SessionModel.INSTANCE;
    @Before public void setup(){reset();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60));}
    @After public void cleanup(){reset();}
    private void reset(){m.track=null;m.running=false;m.live=false;m.aligned=false;m.manualHold=false;m.needsOpen=false;m.compatibleLaunchRequested=false;MainActivity.visible=false;RuntimeEnvironment.getApplication().getSharedPreferences("settings",0).edit().clear().commit();}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void invoke(SyncService s,String name,Track track)throws Exception{Method f=SyncService.class.getDeclaredMethod(name,Track.class);f.setAccessible(true);f.invoke(s,track);}
    private static void tick(SyncService s)throws Exception{Method f=SyncService.class.getDeclaredMethod("syncTick");f.setAccessible(true);f.invoke(s);}
    private static void pausedPlayer(MediaController c,long now){
        Shadows.shadowOf(c).setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Song").putString(MediaMetadata.METADATA_KEY_ARTIST,"Artist").build());
        Shadows.shadowOf(c).setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SEEK_TO).setState(PlaybackState.STATE_PAUSED,10000,1,now).build());
    }
    @Test public void stalePreSilenceMatchMustNotResumePlayer()throws Exception{
        var life=Robolectric.buildService(SyncService.class).create();SyncService s=life.get();MediaSession player=new MediaSession(s,"silence");MediaController c=player.getController();long now=SystemClock.elapsedRealtime();
        try{
            pausedPlayer(c,now);set(s,"bridge",new MediaBridge(s){public MediaController controller(){return c;}});set(s,"active",true);set(s,"pausedByUs",true);set(s,"lastLoud",now-2000);
            m.running=true;m.live=true;m.aligned=true;Track old=new Track("1","Song","Artist","",7500,now-8500,0);old.sampleDurationMs=3000;m.track=old;
            Track delayed=new Track("1","Song","Artist","",10500,now-5500,0);delayed.sampleDurationMs=3000;invoke(s,"accept",delayed);
            assertNotEquals("The sample ended before source silence; its delayed response cannot restart playback",PlaybackState.ACTION_PLAY,Shadows.shadowOf(c.getTransportControls()).getLastPerformedAction());
        }finally{life.destroy();player.release();}
    }
    @Test public void liveResumeMustGetFreshAcknowledgementDeadline()throws Exception{
        var life=Robolectric.buildService(SyncService.class).create();SyncService s=life.get();MediaSession player=new MediaSession(s,"resume");MediaController c=player.getController();long now=SystemClock.elapsedRealtime();
        try{
            pausedPlayer(c,now);set(s,"bridge",new MediaBridge(s){public MediaController controller(){return c;}});set(s,"active",true);set(s,"pausedByUs",true);set(s,"acquireStarted",now-20001);set(s,"lastLoud",now);
            m.running=true;m.live=true;m.aligned=true;Track old=new Track("1","Song","Artist","",10000,now-6000,0);old.sampleDurationMs=3000;m.track=old;
            Track fresh=new Track("1","Song","Artist","",13000,now-3000,0);fresh.sampleDurationMs=3000;invoke(s,"accept",fresh);tick(s);
            assertTrue("Async play acknowledgement must not inherit an expired acquisition deadline",m.running);
        }finally{life.destroy();player.release();}
    }
    @Test public void manualHoldMustCancelPendingAutomaticLaunch(){
        m.track=new Track("manual","Song","Artist","",10000,SystemClock.elapsedRealtime(),0);m.track.youtubeUrl="https://music.youtube.com/watch?v=JApegyYlvyY";
        m.running=true;m.manualHold=true;m.compatibleLaunchRequested=true;
        RuntimeEnvironment.getApplication().getSharedPreferences("settings",0).edit().putBoolean("compatibleLaunch",true).commit();
        try(var activity=Robolectric.buildActivity(MainActivity.class).setup()){
            assertNull("A pending catalog callback must not override manual hold",Shadows.shadowOf(activity.get()).getNextStartedActivity());
        }
    }
    @Test public void manualPauseCancelsBothRequestsAndInvalidatesCapture()throws Exception{
        var life=Robolectric.buildService(SyncService.class).create();SyncService s=life.get();
        try{
            RequestScope scope=new RequestScope();java.util.concurrent.FutureTask<Void> recognition=new java.util.concurrent.FutureTask<>(()->null),resolver=new java.util.concurrent.FutureTask<>(()->null);
            set(s,"active",true);set(s,"requests",scope);set(s,"recognitionTask",recognition);set(s,"resolverTask",resolver);
            m.running=true;m.compatibleLaunchRequested=true;
            s.onStartCommand(new Intent().setAction(SyncService.USER_PAUSE),0,1);
            assertTrue(scope.isCancelled());assertTrue(recognition.isCancelled());assertTrue(resolver.isCancelled());assertTrue(m.manualHold);assertFalse(m.compatibleLaunchRequested);
        }finally{life.destroy();}
    }
    @Test public void sourceBecameLoudAgainButPrePauseSampleStillCannotResume()throws Exception{
        var life=Robolectric.buildService(SyncService.class).create();SyncService s=life.get();MediaSession player=new MediaSession(s,"freshness");MediaController c=player.getController();long now=SystemClock.elapsedRealtime();
        try{
            pausedPlayer(c,now);set(s,"bridge",new MediaBridge(s){public MediaController controller(){return c;}});set(s,"active",true);set(s,"pausedByUs",true);set(s,"lastLoud",now);set(s,"sourceLostAt",now-1500);
            m.running=true;m.live=true;m.track=new Track("1","Song","Artist","",7500,now-8500,0);
            Track delayed=new Track("1","Song","Artist","",10500,now-5500,0);delayed.sampleDurationMs=3000;invoke(s,"accept",delayed);
            assertNotEquals(PlaybackState.ACTION_PLAY,Shadows.shadowOf(c.getTransportControls()).getLastPerformedAction());
        }finally{life.destroy();player.release();}
    }
    @Test public void featuredArtistNamedLiveMustNotChangeRecordingVariant(){assertTrue(new Track("1","Hello (feat. Live)","Singer","",1,0,0).matches("Hello","Singer & Live"));}
    @Test public void genuineTitleWordLyricsMustNotBeDiscarded(){assertFalse(new Track("1","No More Lyrics","Singer","",1,0,0).matches("No More","Singer"));}
    private static JSONObject column(String text)throws Exception{return new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",new JSONObject().put("text",new JSONObject().put("simpleText",text)));}
    private static JSONObject item(String id,JSONArray columns)throws Exception{return new JSONObject().put("musicResponsiveListItemRenderer",new JSONObject().put("playlistItemData",new JSONObject().put("videoId",id)).put("flexColumns",columns));}
    @Test public void malformedCandidateMustNotHideValidLaterResult()throws Exception{
        JSONObject bad=item("AAAAAAAAAAA",new JSONArray().put(new JSONObject().put("musicResponsiveListItemFlexColumnRenderer",new JSONObject())).put(column("Artist")));
        JSONObject valid=item("BBBBBBBBBBB",new JSONArray().put(column("Song")).put(column("Artist")));
        assertEquals("https://music.youtube.com/watch?v=BBBBBBBBBBB",RecognitionClient.parseYoutubeSearch(new JSONArray().put(bad).put(valid),new Track("1","Song","Artist","",1,0,0)));
    }
    @Test public void exactMetadataControlStillMatches(){Track track=new Track("1","Song","Artist","",1,0,0);assertTrue(track.matches("Song","Artist"));assertFalse(track.matches("Song","Other Artist"));}
}
