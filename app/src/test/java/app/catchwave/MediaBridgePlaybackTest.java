package app.catchwave;

import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class MediaBridgePlaybackTest {
    private ServiceController<SyncService> life;
    private MediaSession session;
    @Before public void setup(){
        life=Robolectric.buildService(SyncService.class).create();
        session=new MediaSession(life.get(),"queue");
    }
    @After public void cleanup(){session.release();life.destroy();}
    private MediaController controller(){return session.getController();}
    private void state(int state){
        Shadows.shadowOf(controller()).setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY)
            .setState(state,0,1,1000).build());
    }
    private long action(){return Shadows.shadowOf(controller().getTransportControls()).getLastPerformedAction();}
    @Test public void pausePlayerStopsSkippingToNextQueueItem(){
        state(PlaybackState.STATE_SKIPPING_TO_NEXT);
        assertTrue(MediaBridge.pausePlayer(controller()));
        assertEquals(PlaybackState.ACTION_PAUSE,action());
    }
    @Test public void pausePlayerIgnoresAlreadyPausedSession(){
        state(PlaybackState.STATE_PAUSED);
        assertFalse(MediaBridge.pausePlayer(controller()));
        assertEquals(0,action());
    }
    @Test public void holdQueueDoesNotThrowOnLiveController(){
        state(PlaybackState.STATE_PLAYING);
        MediaBridge.holdQueue(controller());
        MediaBridge.holdQueue(null);
    }
    @Test public void advancingIncludesBufferingAndQueueSkip(){
        assertTrue(MediaBridge.advancing(PlaybackState.STATE_PLAYING));
        assertTrue(MediaBridge.advancing(PlaybackState.STATE_BUFFERING));
        assertTrue(MediaBridge.advancing(PlaybackState.STATE_SKIPPING_TO_NEXT));
        assertTrue(MediaBridge.advancing(PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM));
        assertFalse(MediaBridge.advancing(PlaybackState.STATE_PAUSED));
        assertFalse(MediaBridge.advancing(PlaybackState.STATE_STOPPED));
    }
}
