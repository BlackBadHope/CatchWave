package app.catchwave;

import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.*;
import android.os.*;
import android.view.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

/** Real service callback ordering on fake sensors/clock/transport, never a physical phone. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class SensorIntegrationTest {
    ServiceController<LanAudioService> life;LanAudioService service;LanSettings settings;LanReceiver receiver;
    GestureArming gate;GestureEngine engine;double[] pose={0,1,0,0};double acceleration=-9.81,gyro=0;long latency=20;
    static final double C=Math.sqrt(.5);
    static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static Object get(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    @Before public void setup()throws Exception{L.set(org.robolectric.RuntimeEnvironment.getApplication(),true);
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000));
        life=Robolectric.buildService(LanAudioService.class).create();service=life.get();settings=new LanSettings(service);
        settings.prefs.edit().clear().putBoolean("explicitControlUi21",true).putBoolean("gestures",true).putBoolean("activationSound",false).putBoolean("screenOff",false).putBoolean("resume",true).commit();settings.hostControl(true);
        gate=(GestureArming)get(service,"arming");engine=(GestureEngine)get(service,"engine");
        receiver=new LanReceiver(settings,new LanReceiver.Listener(){public boolean ready(){return false;}public void connected(){}public void finished(){}public void ack(boolean a){}},()->gate.commands(SystemClock.elapsedRealtime()),gate::revision);
        byte[] format=new byte[24];ByteBuffer b=ByteBuffer.wrap(format);b.position(16);b.putInt(48000).put((byte)2).put((byte)16).putShort((short)960);
        set(receiver,"format",new LanProtocol.Format(format));set(receiver,"latestSequence",0L);fresh();receiver.policy(ByteBuffer.allocate(9).put((byte)1).putLong(0).array());
        set(service,"receiver",receiver);set(service,"registered",true);set(service,"orientationRegistered",true);
        LanState.INSTANCE.connected=true;LanState.INSTANCE.hostAllowed=true;LanState.INSTANCE.error=LanConnection.Error.NONE;LanAudioService.uiVisible=true;
        engine.setEnabled(true,SystemClock.elapsedRealtime());Shadows.shadowOf(service.getSystemService(WindowManager.class).getDefaultDisplay()).setRotation(0);
    }
    @After public void cleanup(){life.destroy();settings.prefs.edit().clear().commit();LanAudioService.uiVisible=false;LanState.INSTANCE.connected=false;}
    void fresh()throws Exception{((LanFreshStream)get(receiver,"stream")).received(SystemClock.elapsedRealtime());}
    void sample(int type)throws Exception{
        Constructor<SensorEvent> ctor=SensorEvent.class.getDeclaredConstructor(int.class);ctor.setAccessible(true);SensorEvent event=ctor.newInstance(type==Sensor.TYPE_GAME_ROTATION_VECTOR?4:3);
        event.sensor=ShadowSensor.newInstance(type);event.timestamp=(SystemClock.elapsedRealtime()-latency)*1000000;
        if(type==Sensor.TYPE_GAME_ROTATION_VECTOR){event.values[0]=(float)pose[1];event.values[1]=(float)pose[2];event.values[2]=(float)pose[3];event.values[3]=(float)pose[0];}
        else if(type==Sensor.TYPE_ACCELEROMETER)event.values[2]=(float)acceleration;
        else event.values[0]=(float)gyro;
        service.onSensorChanged(event);
    }
    void frame(int... order)throws Exception{ShadowSystemClock.advanceBy(Duration.ofMillis(10));fresh();for(int type:order)sample(type);}
    void frame()throws Exception{frame(Sensor.TYPE_GYROSCOPE,Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GAME_ROTATION_VECTOR);}
    void quiet(int ms)throws Exception{for(int n=0;n<ms;n+=10)frame();}
    void arm()throws Exception{
        quiet(100);assertTrue(gate.activationReady());assertFalse(gate.active(SystemClock.elapsedRealtime()));manualStart();assertTrue(gate.active(SystemClock.elapsedRealtime()));
        quiet(1700);assertTrue(engine.neutralReady(SystemClock.elapsedRealtime()));assertTrue((Boolean)get(service,"commandReady"));
    }
    @Test public void firstDependenciesInEitherOrderWaitWithoutLocking()throws Exception{
        frame(Sensor.TYPE_GAME_ROTATION_VECTOR,Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GYROSCOPE);quiet(900);assertTrue(gate.activationReady());assertFalse(gate.active(SystemClock.elapsedRealtime()));assertFalse(gate.reason().contains("STALE"));
    }
    @Test public void displayRotationKeepsNeutralButDropsOldQueuedCommands()throws Exception{
        arm();long revision=gate.revision();
        Shadows.shadowOf(service.getSystemService(WindowManager.class).getDefaultDisplay()).setRotation(1);
        frame(Sensor.TYPE_GAME_ROTATION_VECTOR,Sensor.TYPE_GYROSCOPE,Sensor.TYPE_ACCELEROMETER);
        assertTrue(gate.reason(),gate.active(SystemClock.elapsedRealtime()));assertTrue(engine.neutralReady(SystemClock.elapsedRealtime()));assertFalse(receiver.commandArmed(revision));
        quiet(1700);assertTrue(gate.active(SystemClock.elapsedRealtime()));assertTrue((Boolean)get(service,"commandReady"));
    }
    @Test public void delayedFirstAccelerationDoesNotEraseOrientationOrResetAgain()throws Exception{
        arm();engine.reset(SystemClock.elapsedRealtime(),"TEST_PROCESSING_RESET");
        frame(Sensor.TYPE_GYROSCOPE,Sensor.TYPE_GAME_ROTATION_VECTOR,Sensor.TYPE_ACCELEROMETER);
        assertEquals("TEST_PROCESSING_RESET",get(engine,"resetCause"));quiet(1600);assertTrue(engine.neutralReady(SystemClock.elapsedRealtime()));
    }
    @Test public void serviceRecognizesThreeTapsAfterRotationNeutral()throws Exception{
        arm();Shadows.shadowOf(service.getSystemService(WindowManager.class).getDefaultDisplay()).setRotation(1);frame(Sensor.TYPE_GAME_ROTATION_VECTOR,Sensor.TYPE_GYROSCOPE,Sensor.TYPE_ACCELEROMETER);quiet(1700);
        for(int i=0;i<3;i++){acceleration=-19.81;frame();frame();acceleration=-9.81;quiet(220);}quiet(750);
        assertEquals(1L,get(service,"gestureActions"));assertEquals(GestureEngine.Action.PLAY_PAUSE,get(engine,"lastAction"));
    }
    @Test public void genuineGapAccuracyAndPermissionRevocationStillClose()throws Exception{
        arm();ShadowSystemClock.advanceBy(Duration.ofMillis(160));fresh();sample(Sensor.TYPE_GAME_ROTATION_VECTOR);assertFalse(gate.active(SystemClock.elapsedRealtime()));
        pose=new double[]{0,1,0,0};arm();service.onAccuracyChanged(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE),SensorManager.SENSOR_STATUS_UNRELIABLE);assertFalse(gate.active(SystemClock.elapsedRealtime()));
        pose=new double[]{0,1,0,0};arm();settings.hostControl(false);frame();assertFalse(gate.active(SystemClock.elapsedRealtime()));assertFalse((Boolean)get(service,"commandReady"));
    }
    @Test public void screenViewingAndTableReturnKeepActivationUntilButtonStop()throws Exception{
        arm();pose=new double[]{1,0,0,0};quiet(12000);assertTrue(gate.active(SystemClock.elapsedRealtime()));assertTrue((Boolean)get(service,"commandReady"));
        pose=new double[]{0,1,0,0};quiet(200);assertTrue(gate.active(SystemClock.elapsedRealtime()));assertTrue((Boolean)get(service,"commandReady"));assertFalse(engine.tiltAvailable(SystemClock.elapsedRealtime()));
        quiet(1700);assertTrue(gate.active(SystemClock.elapsedRealtime()));assertTrue((Boolean)get(service,"commandReady"));
        service.onStartCommand(new Intent().setAction(LanAudioService.DEACTIVATE),0,9);assertFalse(gate.active(SystemClock.elapsedRealtime()));assertFalse(LanState.INSTANCE.armed);
        quiet(900);pose=new double[]{0,C,-C,0};quiet(300);assertFalse(gate.active(SystemClock.elapsedRealtime()));
    }
    @Test public void uprightPickupKeepsCommandsWhileOnlyTiltRebases()throws Exception{
        arm();pose=new double[]{C,C,0,0};frame();
        assertTrue(gate.active(SystemClock.elapsedRealtime()));assertTrue((Boolean)get(service,"commandReady"));assertEquals("ARMED",gate.reason());assertTrue((Boolean)get(engine,"postureRebasing"));
        quiet(1700);assertTrue(gate.active(SystemClock.elapsedRealtime()));assertTrue((Boolean)get(service,"commandReady"));assertTrue(engine.tiltAvailable(SystemClock.elapsedRealtime()));
    }
    @Test public void rotationDoesNotRecreateForegroundActivity()throws Exception{
        for(Class<?> page:new Class<?>[]{LanAudioActivity.class,MainActivity.class}){
            ActivityInfo info=service.getPackageManager().getActivityInfo(new ComponentName(service,page),0);
            assertEquals(ActivityInfo.CONFIG_ORIENTATION|ActivityInfo.CONFIG_SCREEN_SIZE,info.configChanges&(ActivityInfo.CONFIG_ORIENTATION|ActivityInfo.CONFIG_SCREEN_SIZE));
        }
    }
    @Test public void foregroundPagesRetainInstanceAndSensorsOnOrientationConfiguration()throws Exception{
        arm();
        var main=Robolectric.buildActivity(MainActivity.class).create().start().resume();var original=main.get();
        Configuration landscape=new Configuration(original.getResources().getConfiguration());landscape.orientation=Configuration.ORIENTATION_LANDSCAPE;main.configurationChange(landscape);
        assertSame(original,main.get());assertTrue(LanAudioService.uiVisible);assertTrue(gate.active(SystemClock.elapsedRealtime()));main.pause().stop().destroy();
        var lan=Robolectric.buildActivity(LanAudioActivity.class).create().start().resume();var lanOriginal=lan.get();
        Configuration portrait=new Configuration(lanOriginal.getResources().getConfiguration());portrait.orientation=Configuration.ORIENTATION_PORTRAIT;lan.configurationChange(portrait);
        assertSame(lanOriginal,lan.get());assertTrue(LanAudioService.uiVisible);lan.pause().stop().destroy();
    }
    @Test public void gyroMotionDelaysNeutralWithoutTimingOutActivation()throws Exception{
        quiet(100);manualStart();assertTrue(gate.active(SystemClock.elapsedRealtime()));
        gyro=.3;quiet(12000);assertTrue(gate.active(SystemClock.elapsedRealtime()));assertFalse((Boolean)get(service,"commandReady"));gyro=0;quiet(1600);assertTrue((Boolean)get(service,"commandReady"));
    }
    @Test public void visibleGuideTracksButtonActivationAndExplicitStop()throws Exception{
        arm();LanState.INSTANCE.started=true;pose=new double[]{1,0,0,0};quiet(12000);
        var page=Robolectric.buildActivity(LanAudioActivity.class).create().start();
        try{
            android.widget.TextView guide=(android.widget.TextView)get(page.get(),"guide");
            assertTrue(guide.getText().toString().startsWith("Управление активировано."));
            assertFalse(guide.getText().toString().contains("Все команды заблокированы"));
            assertTrue(((android.widget.TextView)get(page.get(),"controlStatus")).getText().toString().contains("без таймера"));
            pose=new double[]{0,1,0,0};quiet(900);
            assertTrue(LanState.INSTANCE.armed);
            service.onStartCommand(new Intent().setAction(LanAudioService.DEACTIVATE),0,9);
            Method render=LanAudioActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(page.get());
            assertFalse(LanState.INSTANCE.armed);assertTrue(guide.getText().toString().startsWith("Режим выключен."));
        }finally{page.stop().destroy();}
    }
    @Test public void guideExplainsHostBlockInsteadOfRequestingRotation()throws Exception{
        LanState.INSTANCE.hostAllowed=false;LanState.INSTANCE.armed=false;
        var page=Robolectric.buildActivity(LanAudioActivity.class).create().start();
        try{String text=((android.widget.TextView)get(page.get(),"guide")).getText().toString();assertTrue(text.contains("Включить управление хостом"));assertFalse(text.contains("Поверните"));}
        finally{page.stop().destroy();}
    }
    @Test public void hostPolicyAndIndividuallyStaleDependenciesStillClose()throws Exception{
        arm();receiver.policy(ByteBuffer.allocate(9).put((byte)0).putLong(1).array());frame();assertFalse(gate.active(SystemClock.elapsedRealtime()));
        receiver.policy(ByteBuffer.allocate(9).put((byte)1).putLong(2).array());pose=new double[]{0,1,0,0};arm();set(service,"lastGyro",SystemClock.elapsedRealtime()-151);sample(Sensor.TYPE_GAME_ROTATION_VECTOR);assertFalse(gate.active(SystemClock.elapsedRealtime()));assertEquals("GYRO_STALE",gate.reason());
        pose=new double[]{0,1,0,0};arm();set(service,"lastAccel",SystemClock.elapsedRealtime()-151);sample(Sensor.TYPE_GAME_ROTATION_VECTOR);assertFalse(gate.active(SystemClock.elapsedRealtime()));assertEquals("ACCEL_STALE",gate.reason());
    }
    void invalidPendingTrain(int type,double value)throws Exception{
        arm();for(int i=0;i<3;i++){acceleration=-19.81;frame();frame();acceleration=-9.81;quiet(220);}
        assertEquals(3,get(engine,"taps"));long end=(Long)get(engine,"trainEnd");
        while(SystemClock.elapsedRealtime()<end+latency-100)frame();
        ShadowSystemClock.advanceBy(Duration.ofMillis(end+latency-SystemClock.elapsedRealtime()+1));fresh();
        if(type==Sensor.TYPE_ACCELEROMETER)acceleration=value;else gyro=value;
        sample(type);assertFalse(gate.active(SystemClock.elapsedRealtime()));assertEquals("INVALID_SENSOR",gate.reason());assertEquals(0L,get(service,"gestureActions"));
    }
    @Test public void invalidAccelerationCannotFinishPendingThreeTaps()throws Exception{invalidPendingTrain(Sensor.TYPE_ACCELEROMETER,Double.NaN);}
    @Test public void invalidGyroCannotLeavePendingThreeTapsArmed()throws Exception{invalidPendingTrain(Sensor.TYPE_GYROSCOPE,Double.POSITIVE_INFINITY);}
    @Test public void actualGateEdgesOnlyToneOnceDespiteNeutralAndHealthRevisions()throws Exception{
        List<Boolean> tones=new ArrayList<>();set(service,"feedback",new ActivationFeedback(tones::add));settings.prefs.edit().putBoolean("activationSound",true).commit();
        arm();assertEquals(List.of(true),tones);
        Shadows.shadowOf(service.getSystemService(WindowManager.class).getDefaultDisplay()).setRotation(1);frame();quiet(1700);assertEquals(List.of(true),tones);
        pose=new double[]{1,0,0,0};quiet(12000);assertEquals(List.of(true),tones);
        pose=new double[]{0,1,0,0};quiet(900);assertEquals(List.of(true),tones);
        service.onStartCommand(new Intent().setAction(LanAudioService.DEACTIVATE),0,9);assertEquals(List.of(true,false),tones);
        settings.hostControl(false);frame();frame();assertEquals(List.of(true,false),tones);
    }
    void manualStart()throws Exception{service.onStartCommand(new Intent().setAction("app.catchwave.LAN_ACTIVATE"),0,8);}
    @Test public void activityButtonAloneActivatesAndStopsWithoutTableReference()throws Exception{
        pose=new double[]{1,0,0,0};quiet(100);LanState.INSTANCE.started=true;LanState.INSTANCE.listening=true;
        var page=Robolectric.buildActivity(LanAudioActivity.class).create().start();
        try{
            android.widget.Button button=(android.widget.Button)get(page.get(),"mode");
            assertEquals("Активировать управление",button.getText().toString());assertTrue(button.isEnabled());
            button.performClick();Intent activate=Shadows.shadowOf(page.get()).getNextStartedService();
            assertEquals(LanAudioService.ACTIVATE,activate.getAction());service.onStartCommand(activate,0,8);
            quiet(1700);assertTrue(LanState.INSTANCE.armed);assertTrue(LanState.INSTANCE.controlReady);
            Method render=LanAudioActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(page.get());
            assertEquals("Выключить управление",button.getText().toString());
            button.performClick();Intent deactivate=Shadows.shadowOf(page.get()).getNextStartedService();
            assertEquals(LanAudioService.DEACTIVATE,deactivate.getAction());service.onStartCommand(deactivate,0,9);
            pose=new double[]{0,1,0,0};quiet(900);pose=new double[]{0,C,-C,0};quiet(300);
            assertFalse(LanState.INSTANCE.armed);assertTrue(receiver.playbackAlive());assertTrue(settings.resume());
        }finally{page.stop().destroy();}
    }
    @Test public void explicitActivationWithoutTablePositionWaitsForNeutralAndSurvivesPickup()throws Exception{
        pose=new double[]{1,0,0,0};quiet(100);assertFalse(gate.active(SystemClock.elapsedRealtime()));
        manualStart();assertTrue(gate.active(SystemClock.elapsedRealtime()));assertFalse((Boolean)get(service,"commandReady"));
        quiet(1700);assertTrue((Boolean)get(service,"commandReady"));
        pose=new double[]{0,1,0,0};quiet(1900);assertTrue(gate.active(SystemClock.elapsedRealtime()));assertTrue((Boolean)get(service,"commandReady"));
    }
    @Test public void explicitStopOnlyStopsControlAndKeepsAudioAndPermissions()throws Exception{
        arm();service.onStartCommand(new Intent().setAction("app.catchwave.LAN_DEACTIVATE"),0,9);
        assertFalse(gate.active(SystemClock.elapsedRealtime()));assertFalse((Boolean)get(service,"commandReady"));assertTrue(receiver.playbackAlive());assertTrue(settings.resume());assertTrue(settings.hostControl());assertTrue(settings.gestures());
    }
    @Test public void explicitActivationCannotUseRevokedPermission()throws Exception{
        pose=new double[]{1,0,0,0};quiet(100);settings.hostControl(false);manualStart();assertFalse(gate.active(SystemClock.elapsedRealtime()));
    }
    @Test public void explicitActivationCannotUseStaleDependenciesOrReviveOwnerStop()throws Exception{
        quiet(900);pose=new double[]{1,0,0,0};frame();set(service,"lastGyro",SystemClock.elapsedRealtime()-151);manualStart();assertFalse(gate.active(SystemClock.elapsedRealtime()));
        settings.prefs.edit().putBoolean("resume",false).commit();quiet(100);manualStart();assertFalse(gate.active(SystemClock.elapsedRealtime()));assertFalse(receiver.playbackAlive());assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
    }
}
