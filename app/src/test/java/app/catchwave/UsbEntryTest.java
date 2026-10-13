package app.catchwave;

import android.content.Intent;
import android.hardware.usb.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class UsbEntryTest {
    private UsbManager manager;private UsbAccessory accessory;
    @Before public void setup()throws Exception{
        manager=RuntimeEnvironment.getApplication().getSystemService(UsbManager.class);
        // Framework-only constructor is used solely by the JVM fixture, not production.
        accessory=UsbAccessory.class.getDeclaredConstructor(String.class,String.class,String.class,String.class,String.class,String.class)
            .newInstance("CatchWave","CatchWave Host","public fixture","1","https://example.invalid","fixture");
        Shadows.shadowOf(manager).setAttachedUsbAccessory(accessory);Shadows.shadowOf(manager).grantPermission(accessory);
        new LanSettings(RuntimeEnvironment.getApplication()).prefs.edit().clear().commit();LanState.INSTANCE.started=false;
        while(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()!=null){}
    }
    @After public void cleanup(){LanState.INSTANCE.started=false;Shadows.shadowOf(manager).setAttachedUsbAccessory(null);}
    @Test public void externalAttachCannotOverrideExplicitDisconnectEvenWithPermission(){
        try(ActivityController<UsbAttachActivity> a=Robolectric.buildActivity(UsbAttachActivity.class,new Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED).putExtra(UsbManager.EXTRA_ACCESSORY,accessory))){
            a.create();assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
            assertFalse(new LanSettings(a.get()).resume());
        }
    }
    @Test public void externalAttachContinuesActiveOwnerSessionWithoutSecondButton(){
        new LanSettings(RuntimeEnvironment.getApplication()).prefs.edit().putBoolean("resume",true).commit();LanState.INSTANCE.started=true;
        try(ActivityController<UsbAttachActivity> a=Robolectric.buildActivity(UsbAttachActivity.class)){
            a.create();Intent start=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();assertNotNull(start);assertEquals(LanAudioService.USB,start.getAction());
        }
    }
    @Test public void privateOwnerUsbActionStartsImmediatelyAfterExistingPermission(){
        try(ActivityController<UsbConnectActivity> a=Robolectric.buildActivity(UsbConnectActivity.class)){
            a.create();Intent start=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();assertNotNull(start);assertEquals(LanAudioService.USB,start.getAction());
        }
    }
    @Test public void forgedParcelableCannotSubstituteForMissingOsAccessory(){
        Shadows.shadowOf(manager).setAttachedUsbAccessory(null);LanState.INSTANCE.started=true;
        new LanSettings(RuntimeEnvironment.getApplication()).prefs.edit().putBoolean("resume",true).commit();
        try(ActivityController<UsbAttachActivity> a=Robolectric.buildActivity(UsbAttachActivity.class,new Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED).putExtra(UsbManager.EXTRA_ACCESSORY,accessory))){
            a.create();assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        }
    }
}
