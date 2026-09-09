package app.catchwave;

import android.content.*;
import android.media.*;
import android.media.session.*;
import android.os.SystemClock;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class AudioCalibrationTest {
    private final SessionModel model=SessionModel.INSTANCE;
    private MediaSession player;
    private AudioManager audio;
    private CalibrationTarget target;
    @Before public void setup(){
        Context context=RuntimeEnvironment.getApplication();audio=context.getSystemService(AudioManager.class);
        model.running=true;model.guardingTrack=true;model.live=false;model.manualHold=false;model.measuringAudio=false;
        model.track=new Track("one","Song","Artist","",10000,SystemClock.elapsedRealtime(),0);
        player=new MediaSession(context,"calibration");metadata("one","Song");state(PlaybackState.STATE_PLAYING,1);
        target=new CalibrationTarget(player.getController(),model.track);
    }
    @After public void teardown(){player.release();model.running=false;model.guardingTrack=false;model.measuringAudio=false;model.manualHold=false;model.live=false;model.track=null;}
    private void metadata(String id,String title){Shadows.shadowOf(player.getController()).setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID,id).putString(MediaMetadata.METADATA_KEY_TITLE,title).putString(MediaMetadata.METADATA_KEY_ARTIST,"Artist").putLong(MediaMetadata.METADATA_KEY_DURATION,100000).build());}
    private void state(int state,float speed){Shadows.shadowOf(player.getController()).setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_SEEK_TO).setState(state,10000,speed,SystemClock.elapsedRealtime()).build());}
    @Test public void playingOwnedTrackIsEligible(){assertTrue(target.valid(player.getController(),model));}
    @Test public void anotherUploadWithSameTitleIsRejected(){metadata("two","Song");assertFalse(target.valid(player.getController(),model));}
    @Test public void metadataChangesWithoutIdAreRejected(){metadata(null,"Song");target=new CalibrationTarget(player.getController(),model.track);metadata(null,"Different");assertFalse(target.valid(player.getController(),model));}
    @Test public void pausedOrBufferingPlayerCannotBeCorrected(){state(PlaybackState.STATE_PAUSED,1);assertFalse(target.valid(player.getController(),model));state(PlaybackState.STATE_BUFFERING,1);assertFalse(target.valid(player.getController(),model));}
    @Test public void NewCaptureCannotReuseOldMeasurement(){model.track=new Track("one","Song","Artist","",10000,SystemClock.elapsedRealtime(),0);assertFalse(target.valid(player.getController(),model));}
    @Test public void userControlAndLiveDisableCalibration(){model.manualHold=true;assertFalse(target.valid(player.getController(),model));model.manualHold=false;model.live=true;assertFalse(target.valid(player.getController(),model));}
    @Test public void abnormalPlaybackRateIsRejected(){state(PlaybackState.STATE_PLAYING,1.1f);assertFalse(target.valid(player.getController(),model));state(PlaybackState.STATE_PLAYING,Float.NaN);assertFalse(target.valid(player.getController(),model));}
    @Test public void ownedMuteRestoresOriginalVolumeOnlyOnce(){audio.setStreamVolume(AudioManager.STREAM_MUSIC,5,0);CaptureVolume volume=new CaptureVolume(audio);assertTrue(volume.mute());volume.restore();assertEquals(5,audio.getStreamVolume(AudioManager.STREAM_MUSIC));audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0);volume.restore();assertEquals(3,audio.getStreamVolume(AudioManager.STREAM_MUSIC));}
    @Test public void initiallyMutedPhoneStaysMuted(){audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0);CaptureVolume volume=new CaptureVolume(audio);assertTrue(volume.mute());volume.restore();assertEquals(0,audio.getStreamVolume(AudioManager.STREAM_MUSIC));}
    @Test public void userVolumeChangeWins(){audio.setStreamVolume(AudioManager.STREAM_MUSIC,5,0);CaptureVolume volume=new CaptureVolume(audio);volume.mute();audio.setStreamVolume(AudioManager.STREAM_MUSIC,2,0);assertFalse(volume.muted());volume.restore();assertEquals(2,audio.getStreamVolume(AudioManager.STREAM_MUSIC));}
    @Test public void missingConsentDoesNotTouchPlaybackOrVolume(){
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,5,0);
        var life=Robolectric.buildService(AudioCalibrationService.class).create();
        life.get().onStartCommand(new Intent().setAction(AudioCalibrationService.START),0,1);
        assertFalse(model.measuringAudio);assertEquals(5,audio.getStreamVolume(AudioManager.STREAM_MUSIC));assertTrue(model.guardingTrack);life.destroy();
    }
}
