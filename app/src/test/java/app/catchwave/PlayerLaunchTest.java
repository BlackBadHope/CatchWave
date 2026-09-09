package app.catchwave;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class PlayerLaunchTest {
    private ActivityController<Activity> lifecycle;private PlayerLaunch launcher;private SessionModel model;private Track target;
    @Before public void setup(){lifecycle=Robolectric.buildActivity(Activity.class).setup();model=SessionModel.INSTANCE;target=new Track("death","death bed","Powfu","",1000,0,0);target.youtubeUrl="https://music.youtube.com/watch?v=JApegyYlvyY";model.track=target;model.running=true;model.aligned=false;model.launchTicket=100;MainActivity.visible=false;launcher=new PlayerLaunch(lifecycle.get(),model);}
    @After public void teardown(){launcher.cancel();lifecycle.destroy();model.track=null;model.running=false;model.aligned=false;model.compatibleLaunchRequested=false;MainActivity.visible=false;}
    private Intent next(){return Shadows.shadowOf(lifecycle.get()).getNextStartedActivity();}
    private void tick(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));}
    @Test public void opensOnlyYoutubeMusicAndReturnsAfterConfirmedSeek(){launcher.open(target,true);Intent open=next();assertEquals(MediaBridge.PACKAGE,open.getPackage());assertEquals(target.youtubeUrl,open.getDataString());tick();assertNull(next());model.aligned=true;tick();assertEquals(MainActivity.class.getName(),next().getComponent().getClassName());tick();assertNull(next());}
    @Test public void staleLaunchCannotReturnOverNewSession(){launcher.open(target,true);next();model.launchTicket++;model.aligned=true;tick();assertNull(next());}
    @Test public void userReturningCancelsLaterAutomaticNavigation(){launcher.open(target,true);next();launcher.cancel();model.aligned=true;tick();assertNull(next());}
    @Test public void explicitBrowsingDoesNotForceReturn(){launcher.open(target,false);next();model.aligned=true;tick();assertNull(next());}
    @Test public void failureReturnsToAppWithoutInventingAlignment(){launcher.open(target,true);next();model.running=false;tick();assertEquals(MainActivity.class.getName(),next().getComponent().getClassName());assertFalse(model.aligned);}
}
