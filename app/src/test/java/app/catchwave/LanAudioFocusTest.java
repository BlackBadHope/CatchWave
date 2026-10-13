package app.catchwave;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.IntConsumer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class LanAudioFocusTest {
    private final FakePhone phone=new FakePhone();
    private final ArrayList<Integer> output=new ArrayList<>();
    private boolean alive=true,fresh=true;
    private LanAudioFocus focus;
    @Before public void setup(){focus=new LanAudioFocus(new Handler(Looper.getMainLooper()),phone,()->alive,()->fresh,output::add);assertTrue(focus.start());}
    @After public void cleanup(){focus.close();}
    private void advance(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    @Test public void permanentLossKeepsPhonePriorityAndReturnsWhenPhoneBecomesQuiet(){
        phone.busy=true;phone.change(AudioManager.AUDIOFOCUS_LOSS);advance(12000);
        assertEquals("Must never steal focus from playing phone audio",1,phone.requests);
        phone.busy=false;phone.activityChanged.run();advance(850);
        assertEquals(2,phone.requests);assertEquals(Integer.valueOf(AudioManager.AUDIOFOCUS_GAIN),output.get(output.size()-1));
        advance(3000);assertEquals("No request loop after recovery",2,phone.requests);
    }
    @Test public void connectingUsbDuringPhoneMediaDoesNotStealInitialFocus(){
        focus.close();FakePhone playing=new FakePhone();playing.busy=true;
        focus=new LanAudioFocus(new Handler(Looper.getMainLooper()),playing,()->alive,()->fresh,output::add);
        assertTrue(focus.start());advance(5000);assertEquals(0,playing.requests);
        assertEquals(Integer.valueOf(AudioManager.AUDIOFOCUS_LOSS),output.get(output.size()-1));
        playing.busy=false;playing.activityChanged.run();advance(850);assertEquals(1,playing.requests);
        assertEquals(Integer.valueOf(AudioManager.AUDIOFOCUS_GAIN),output.get(output.size()-1));
    }
    @Test public void transientLossWaitsForAndroidGainWithoutReRequesting(){
        phone.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);advance(10000);assertEquals(1,phone.requests);
        phone.change(AudioManager.AUDIOFOCUS_GAIN);assertEquals(Integer.valueOf(AudioManager.AUDIOFOCUS_GAIN),output.get(output.size()-1));
    }
    @Test public void duckRequestLeavesPlaybackActiveAndWaitsForGainWithoutReRequest(){
        phone.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals(Integer.valueOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK),output.get(output.size()-1));
        advance(3000);assertEquals(1,phone.requests);phone.change(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(Integer.valueOf(AudioManager.AUDIOFOCUS_GAIN),output.get(output.size()-1));
    }
    @Test public void duckDuringPhoneMediaCannotResumeIt(){
        phone.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);int paused=output.size();
        phone.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals(paused,output.size());assertEquals(1,phone.requests);
        phone.change(AudioManager.AUDIOFOCUS_GAIN);assertEquals(Integer.valueOf(AudioManager.AUDIOFOCUS_GAIN),output.get(output.size()-1));
    }
    @Test public void androidRequestAllowsNativeNotificationDucking()throws Exception{
        AudioManager manager=RuntimeEnvironment.getApplication().getSystemService(AudioManager.class);
        LanAudioFocus nativeFocus=new LanAudioFocus(new Handler(Looper.getMainLooper()),manager,()->true,()->true,value->{});
        try{
            nativeFocus.start();java.lang.reflect.Field p=LanAudioFocus.class.getDeclaredField("platform");p.setAccessible(true);Object platform=p.get(nativeFocus);
            java.lang.reflect.Field r=platform.getClass().getDeclaredField("request");r.setAccessible(true);
            android.media.AudioFocusRequest request=(android.media.AudioFocusRequest)r.get(platform);
            assertFalse("A notification must duck rather than pause",request.willPauseWhenDucked());
            assertEquals(android.media.AudioAttributes.CONTENT_TYPE_MUSIC,request.getAudioAttributes().getContentType());
        }finally{nativeFocus.close();}
    }
    @Test public void briefGapInPhonePlaybackDoesNotStealFocus(){
        phone.busy=true;phone.change(AudioManager.AUDIOFOCUS_LOSS);
        phone.busy=false;phone.activityChanged.run();advance(400);
        phone.busy=true;phone.activityChanged.run();advance(3000);assertEquals(1,phone.requests);
    }
    @Test public void ownerStopCancelsPendingReturnAndLateGain(){
        phone.busy=true;phone.change(AudioManager.AUDIOFOCUS_LOSS);phone.busy=false;advance(1100);int before=output.size();
        alive=false;focus.close();phone.change(AudioManager.AUDIOFOCUS_GAIN);advance(5000);
        assertEquals(1,phone.requests);assertEquals(before,output.size());assertEquals(1,phone.abandons);
    }
    @Test public void staleNetworkCannotReacquireFocus(){
        phone.busy=true;phone.change(AudioManager.AUDIOFOCUS_LOSS);phone.busy=false;fresh=false;advance(5000);assertEquals(1,phone.requests);
        fresh=true;advance(2000);assertEquals(2,phone.requests);
    }
    @Test public void delayedReturnWaitsForAndroidWithoutRepeatedRequests(){
        phone.busy=true;phone.change(AudioManager.AUDIOFOCUS_LOSS);phone.busy=false;phone.result=AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
        advance(1800);assertEquals(2,phone.requests);advance(6000);assertEquals(2,phone.requests);
        phone.change(AudioManager.AUDIOFOCUS_GAIN);assertEquals(Integer.valueOf(AudioManager.AUDIOFOCUS_GAIN),output.get(output.size()-1));
    }
    @Test public void declinedReturnStaysPausedAndRetriesAfterCooldown(){
        phone.busy=true;phone.change(AudioManager.AUDIOFOCUS_LOSS);phone.busy=false;phone.result=AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        advance(1800);assertEquals(2,phone.requests);assertEquals(Integer.valueOf(AudioManager.AUDIOFOCUS_LOSS),output.get(output.size()-1));
        phone.result=AudioManager.AUDIOFOCUS_REQUEST_GRANTED;advance(2800);assertEquals(3,phone.requests);
    }
    @Test public void unavailablePlaybackObservationDoesNotStealFocus(){
        phone.change(AudioManager.AUDIOFOCUS_LOSS);phone.unavailable=true;advance(8000);assertEquals(1,phone.requests);
    }
    @Test public void focusOwnerStillLoadingItsFirstAudioIsNeverPreempted(){
        phone.change(AudioManager.AUDIOFOCUS_LOSS);advance(10000);assertEquals(1,phone.requests);
        phone.busy=true;phone.activityChanged.run();advance(100);
        phone.busy=false;phone.activityChanged.run();advance(850);assertEquals(2,phone.requests);
    }
    private static final class FakePhone implements LanAudioFocus.Platform {
        int result=AudioManager.AUDIOFOCUS_REQUEST_GRANTED,requests,abandons;
        boolean busy,unavailable;IntConsumer listener;Runnable activityChanged;
        public int request(IntConsumer value){listener=value;requests++;return result;}
        public boolean phoneAudioActive(){if(unavailable)throw new IllegalStateException("test observation unavailable");return busy;}
        public void watch(Runnable value){activityChanged=value;}
        public void abandon(){abandons++;}
        void change(int value){listener.accept(value);}
    }
}
