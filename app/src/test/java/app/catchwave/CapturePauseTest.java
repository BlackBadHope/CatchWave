package app.catchwave;

import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class CapturePauseTest {
    private MediaSession session;private MediaController controller;
    @Before public void setup(){
        session=new MediaSession(RuntimeEnvironment.getApplication(),"pause-test");controller=session.getController();
        metadata("Original");state(PlaybackState.STATE_PLAYING,1000,10);
    }
    @After public void close(){session.release();}
    private void metadata(String title){Shadows.shadowOf(controller).setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putString(MediaMetadata.METADATA_KEY_ARTIST,"Artist").build());}
    private void state(int state,long pos,long update){Shadows.shadowOf(controller).setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE).setState(state,pos,1,update).build());}
    private long action(){return Shadows.shadowOf(controller.getTransportControls()).getLastPerformedAction();}
    @Test public void waitsForPauseAcknowledgementAndRestoresOnlyItsPause(){CapturePause p=new CapturePause(controller);assertEquals(PlaybackState.ACTION_PAUSE,action());assertFalse(p.ready());state(PlaybackState.STATE_PAUSED,1200,20);assertTrue(p.ready());assertTrue(p.restore(controller));assertEquals(PlaybackState.ACTION_PLAY,action());assertFalse(p.restore(controller));}
    @Test public void neverResumesAnOriginallyPausedPlayer(){state(PlaybackState.STATE_PAUSED,1000,10);CapturePause p=new CapturePause(controller);assertTrue(p.ready());assertFalse(p.restore(controller));assertEquals(0,action());}
    @Test public void userSeekPreventsRestoration(){CapturePause p=new CapturePause(controller);state(PlaybackState.STATE_PAUSED,1200,20);assertTrue(p.ready());state(PlaybackState.STATE_PAUSED,5000,30);assertFalse(p.restore(controller));assertEquals(PlaybackState.ACTION_PAUSE,action());}
    @Test public void userTrackChangePreventsRestoration(){CapturePause p=new CapturePause(controller);state(PlaybackState.STATE_PAUSED,1200,20);assertTrue(p.ready());metadata("User choice");assertFalse(p.restore(controller));assertEquals(PlaybackState.ACTION_PAUSE,action());}
    @Test public void successfulHandoffDoesNotResumeOldSong(){CapturePause p=new CapturePause(controller);state(PlaybackState.STATE_PAUSED,1200,20);assertTrue(p.ready());p.release();assertFalse(p.restore(controller));assertEquals(PlaybackState.ACTION_PAUSE,action());}
}
