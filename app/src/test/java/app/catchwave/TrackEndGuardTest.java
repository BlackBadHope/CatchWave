package app.catchwave;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class TrackEndGuardTest {
    private MediaSession session;
    private TrackEndGuard guard;
    @Before public void setup(){
        session=new MediaSession(RuntimeEnvironment.getApplication(),"boundary");metadata("Song","one",10000);state(PlaybackState.STATE_PLAYING,1000,1000);
        guard=new TrackEndGuard(session.getController(),new Track("song","Song","Artist","",0,0,0),new Handler(Looper.getMainLooper()),()->{});
    }
    @After public void cleanup(){guard.close();session.release();}
    private void metadata(String title,String id,long duration){Shadows.shadowOf(session.getController()).setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putString(MediaMetadata.METADATA_KEY_ARTIST,"Artist").putString(MediaMetadata.METADATA_KEY_MEDIA_ID,id).putLong(MediaMetadata.METADATA_KEY_DURATION,duration).build());}
    private void state(int state,long position,long at){Shadows.shadowOf(session.getController()).setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY|PlaybackState.ACTION_SEEK_TO).setState(state,position,1,at).build());}
    private long action(){return Shadows.shadowOf(session.getController().getTransportControls()).getLastPerformedAction();}
    @Test public void middleOfSelectedSongDoesNotPause(){assertEquals(TrackEndGuard.State.PLAYING,guard.check(2000));assertNotEquals(PlaybackState.ACTION_PAUSE,action());}
    @Test public void endOfSongRequestsPauseBeforeNextItem(){state(PlaybackState.STATE_PLAYING,9900,1000);assertEquals(TrackEndGuard.State.PAUSING,guard.check(1000));assertEquals(PlaybackState.ACTION_PAUSE,action());}
    @Test public void automaticNextSongIsPausedEvenThoughItNoLongerMatches(){metadata("Next song","two",12000);state(PlaybackState.STATE_PLAYING,0,1000);assertEquals(TrackEndGuard.State.PAUSING,guard.check(1000));assertEquals(PlaybackState.ACTION_PAUSE,action());}
    @Test public void differentMediaIdWithSameTitleIsStillAnotherQueueItem(){metadata("Song","two",10000);assertEquals(TrackEndGuard.State.PAUSING,guard.check(1000));}
    @Test public void pauseMustRemainStableAfterTheCommand(){
        state(PlaybackState.STATE_PLAYING,9990,1000);guard.check(1000);
        state(PlaybackState.STATE_PAUSED,9990,999);assertEquals(TrackEndGuard.State.PAUSING,guard.check(1100));
        state(PlaybackState.STATE_PAUSED,9990,1100);assertEquals(TrackEndGuard.State.PAUSING,guard.check(1100));
        assertEquals(TrackEndGuard.State.STOPPED,guard.check(1800));
    }
    @Test public void alreadyPausedNextItemDoesNotNeedAFabricatedPositionTimestamp(){
        metadata("Next song","two",12000);state(PlaybackState.STATE_PAUSED,0,900);assertEquals(TrackEndGuard.State.PAUSING,guard.check(1000));
        assertEquals(TrackEndGuard.State.PAUSING,guard.check(1100));assertEquals(TrackEndGuard.State.STOPPED,guard.check(1800));
    }
    @Test public void pausedPositionThatKeepsChangingCannotCompleteTheGuard(){
        metadata("Next song","two",12000);state(PlaybackState.STATE_PAUSED,0,900);guard.check(1000);guard.check(1100);
        state(PlaybackState.STATE_PAUSED,100,1300);assertEquals(TrackEndGuard.State.PAUSING,guard.check(1800));
    }
    @Test public void resumedQueueDuringPauseConfirmationIsNotReportedStopped(){
        state(PlaybackState.STATE_PLAYING,9990,1000);guard.check(1000);state(PlaybackState.STATE_PAUSED,9990,1100);guard.check(1100);
        metadata("Next song","two",12000);state(PlaybackState.STATE_PLAYING,0,1400);assertEquals(TrackEndGuard.State.PAUSING,guard.check(1600));
        assertEquals(TrackEndGuard.State.FAILED,guard.check(3600));
    }
    @Test public void missingDurationStillCatchesNextMetadata(){metadata("Song","one",0);assertEquals(TrackEndGuard.State.PLAYING,guard.check(1000));metadata("Next song","two",0);assertEquals(TrackEndGuard.State.PAUSING,guard.check(1000));}
    @Test public void unsupportedPauseFailsWithoutClaimingStop(){Shadows.shadowOf(session.getController()).setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING,9990,1,1000).build());assertEquals(TrackEndGuard.State.FAILED,guard.check(1000));}
}
