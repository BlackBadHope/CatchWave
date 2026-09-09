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
    @Test public void versionIsSingleAcrossUiAndReports()throws Exception{
        assertEquals(BuildConfig.VERSION_NAME,AppIdentity.version());
        assertEquals("CatchWave "+BuildConfig.VERSION_NAME,AppIdentity.label());
        assertTrue(AppIdentity.userAgent().startsWith(AppIdentity.label()));
        assertTrue(SessionModel.INSTANCE.report().startsWith(AppIdentity.label()));
        try(var life=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=life.get();
            android.widget.TextView footer=null;
            android.view.View root=activity.getWindow().getDecorView();
            java.util.ArrayDeque<android.view.View> q=new java.util.ArrayDeque<>();q.add(root);
            while(!q.isEmpty()){
                android.view.View v=q.removeFirst();
                if(v instanceof android.widget.TextView){String t=((android.widget.TextView)v).getText().toString();if(t.contains("экспериментальная версия"))footer=(android.widget.TextView)v;}
                if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++)q.add(g.getChildAt(i));}
            }
            assertNotNull(footer);assertEquals(AppIdentity.label()+" · экспериментальная версия",footer.getText().toString());
            assertFalse(footer.getText().toString().contains("0.1.5"));
        }
    }
    @Test public void liveSyncOffCopyIsOneHandoffThenPlayerContinues()throws Exception{
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("settings",0).edit().putBoolean("live",false).commit();
        try(var life=Robolectric.buildActivity(MainActivity.class).setup()){
            android.widget.TextView note=(android.widget.TextView)field(life.get(),"modeNote");
            assertEquals("Один подхват — дальше сам",note.getText().toString());
        }
    }
    @Test public void privacyNoticeForbidsAdvertisingIdAndAnalytics(){
        String notice=Privacy.notice();
        assertTrue(notice.contains("Рекламного идентификатора нет"));
        assertTrue(notice.contains("amp.shazam.com"));
        assertTrue(notice.contains("не приложение Shazam"));
        assertTrue(RecognitionPath.explanation().contains("Приложение Shazam не нужно"));
        assertTrue(notice.contains("music.youtube.com"));
        assertFalse(notice.toLowerCase(java.util.Locale.ROOT).contains("admob"));
        assertFalse(notice.toLowerCase(java.util.Locale.ROOT).contains("firebase"));
        assertTrue(notice.contains("Текст уведомлений не читается"));
    }
    @Test public void gplLicenseStartsAtTermsNotHowToApply(){
        String license="Preamble\n\n                       TERMS AND CONDITIONS\n  0. Definitions.\nHow to Apply These Terms to Your New Programs";
        int start=MainActivity.licenseStart(license);
        assertTrue(start>0);assertTrue(license.substring(start).startsWith("TERMS AND CONDITIONS"));
        assertTrue(start<license.indexOf("How to Apply"));
        assertEquals(0,MainActivity.licenseStart("no terms here"));
    }
}
