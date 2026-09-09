package app.catchwave;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class UiTest {
    @Test public void playerErrorWithoutMetadataStillShowsRecovery()throws Exception{
        SessionModel.INSTANCE.running=false;
        try(var life=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=life.get();android.media.session.MediaSession session=new android.media.session.MediaSession(activity,"missing metadata");
            try{
                var controller=session.getController();org.robolectric.Shadows.shadowOf(controller).setPlaybackState(new android.media.session.PlaybackState.Builder().setState(7,0,1).setErrorMessage("Track unavailable").build());
                var render=MainActivity.class.getDeclaredMethod("renderPlayer",android.media.session.MediaController.class);render.setAccessible(true);render.invoke(activity,controller);
                assertEquals(android.view.View.VISIBLE,((android.view.View)field(activity,"playerPanel")).getVisibility());
                assertTrue(((android.widget.TextView)field(activity,"playerFailure")).getText().toString().contains("Track unavailable"));
                assertEquals(android.view.View.VISIBLE,((android.view.View)field(activity,"retryPlayer")).getVisibility());
                assertFalse(((android.view.View)field(activity,"playPause")).isEnabled());
            }finally{session.release();}
        }
    }
    private static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    @Test public void compatibleLaunchRequiresExplicitSetting(){
        SessionModel m=SessionModel.INSTANCE;m.running=true;m.needsOpen=true;m.compatibleLaunchRequested=true;m.track=new Track("1","Song","Artist","",1000,0,0);m.track.youtubeUrl="https://music.youtube.com/watch?v=JApegyYlvyY";
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("settings",0).edit().putBoolean("compatibleLaunch",false).commit();
        try(var activity=Robolectric.buildActivity(MainActivity.class).setup()){assertNull(org.robolectric.Shadows.shadowOf(activity.get()).getNextStartedActivity());}
        finally{m.running=false;m.needsOpen=false;m.compatibleLaunchRequested=false;m.track=null;MainActivity.visible=false;}
    }
    @Test public void compatibleLaunchConsumesRequestOnceAndTargetsYoutubeMusic(){
        SessionModel m=SessionModel.INSTANCE;m.running=true;m.needsOpen=true;m.compatibleLaunchRequested=true;m.track=new Track("1","Song","Artist","",1000,0,0);m.track.youtubeUrl="https://music.youtube.com/watch?v=JApegyYlvyY";
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("settings",0).edit().putBoolean("compatibleLaunch",true).commit();
        try(var activity=Robolectric.buildActivity(MainActivity.class).setup()){
            android.content.Intent launched=org.robolectric.Shadows.shadowOf(activity.get()).getNextStartedActivity();assertNotNull(launched);assertEquals(MediaBridge.PACKAGE,launched.getPackage());assertFalse(m.compatibleLaunchRequested);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));assertNull(org.robolectric.Shadows.shadowOf(activity.get()).getNextStartedActivity());
        }finally{m.running=false;m.needsOpen=false;m.compatibleLaunchRequested=false;m.track=null;MainActivity.visible=false;}
    }
    @Test public void recognizedTrackNeverAutomaticallyOpensAnotherActivity(){
        SessionModel m=SessionModel.INSTANCE;m.running=true;m.needsOpen=true;m.track=new Track("1","Song","Artist","",1000,0,0);
        try(var activity=Robolectric.buildActivity(MainActivity.class).setup()){
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull(org.robolectric.Shadows.shadowOf(activity.get()).getNextStartedActivity());
        }finally{m.running=false;m.needsOpen=false;m.track=null;}
    }
    @Test public void activityLaunchesWithoutMicrophoneOrMediaAccess(){try(var activity=Robolectric.buildActivity(MainActivity.class).setup()){assertNotNull(activity.get().getWindow().getDecorView());assertFalse(SessionModel.INSTANCE.running);assertFalse(MediaBridge.allowed(activity.get()));}}
    @Test public void aCompletedSessionSurvivesActivityRecreation(){SessionModel m=SessionModel.INSTANCE;m.status="Подхват выполнен";m.track=new Track("1","Song","Artist","",1000,0,0);try(var activity=Robolectric.buildActivity(MainActivity.class).setup()){activity.recreate();assertEquals("Подхват выполнен",m.status);assertNotNull(m.track);}finally{m.track=null;}}
}
