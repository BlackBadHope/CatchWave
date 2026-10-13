package app.catchwave;
import android.content.Intent;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class UsbRouteTest {
    private LanSettings settings;
    private static final String PAIR="CW1/192.0.2.1/49372/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8/AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    @Before public void setup(){settings=new LanSettings(RuntimeEnvironment.getApplication());settings.prefs.edit().clear().apply();settings=new LanSettings(RuntimeEnvironment.getApplication());}
    @After public void cleanup(){settings.prefs.edit().clear().apply();}
    @Test public void fallbackRequiresSamePinnedHostAndSavedRoute(){
        PairingCode code=PairingCode.parse(PAIR);assertFalse(TransportEpoch.sameHost(settings,code));settings.pair(code,false);assertTrue(TransportEpoch.sameHost(settings,code));
        assertFalse(TransportEpoch.sameHost(settings,PairingCode.parse(PAIR.replace("192.0.2.1","192.0.2.2"))));
        assertFalse(TransportEpoch.sameHost(settings,PairingCode.parse(PAIR.replace("49372","49373"))));
        settings.prefs.edit().putString("token","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").apply();assertFalse(TransportEpoch.sameHost(settings,code));
    }
    @Test public void temporaryUsbEndpointPreservesSavedQrAndPhoneControl(){
        PairingCode code=PairingCode.parse(PAIR);settings.pair(code,true);LanSettings usb=new LanSettings(RuntimeEnvironment.getApplication(),code.loopback(49373));
        assertEquals("127.0.0.1",usb.host());assertEquals(49373,usb.port());assertEquals("192.0.2.1",settings.host());assertEquals(49372,settings.port());assertTrue(usb.hostControl());
    }
    @Test public void authenticatedUsbCanRememberItsWifiRouteWithoutQrOrGrantingControl(){
        PairingCode code=PairingCode.parse(PAIR);settings.prefs.edit().putBoolean("resume",true).apply();
        settings.rememberUsbRoute(code);assertTrue(TransportEpoch.sameHost(settings,code));assertTrue(settings.resume());assertFalse(settings.hostControl());
        settings.prefs.edit().putBoolean("resume",false).apply();settings.rememberUsbRoute(code);assertFalse(settings.resume());
    }
    @Test public void explicitDisconnectAndNewReceiverRejectOldCallbacks(){
        TransportEpoch epoch=new TransportEpoch();long old=epoch.next();assertTrue(epoch.current(old,true));assertFalse(epoch.current(old,false));long next=epoch.next();assertFalse(epoch.current(old,true));assertTrue(epoch.current(next,true));epoch.next();assertFalse(epoch.current(next,true));
    }
    @Test public void stoppedUsbStartDoesNotCreateAccessoryOrReceiver()throws Exception{
        var life=Robolectric.buildService(LanAudioService.class).create();try{settings.prefs.edit().putBoolean("resume",false).apply();life.get().onStartCommand(new Intent().setAction(LanAudioService.USB),0,1);
            assertTrue(Shadows.shadowOf(life.get()).isStoppedBySelf());for(String name:new String[]{"receiver","usb"}){var field=LanAudioService.class.getDeclaredField(name);field.setAccessible(true);assertNull(field.get(life.get()));}
        }finally{life.destroy();}
    }
    @Test public void usbBootstrapKeepsFollowingTlsBytesAndRejectsInvalidLength()throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();var write=new java.io.DataOutputStream(bytes);write.writeBytes("CWUSB1\n");write.writeShort(PAIR.length());write.writeBytes(PAIR);write.writeByte(22);
        var input=new java.io.BufferedInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()),65536);assertEquals("192.0.2.1",UsbAudioProxy.readPairing(input).host);assertEquals(22,input.read());
        for(byte[] invalid:new byte[][]{"CWUSB1\n\1\1".getBytes(java.nio.charset.StandardCharsets.US_ASCII),"CWUSB1\n\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII),new byte[9],java.util.Arrays.copyOf(bytes.toByteArray(),10)}){
            try{UsbAudioProxy.readPairing(new java.io.ByteArrayInputStream(invalid));fail("Invalid USB bootstrap accepted");}catch(java.io.IOException expected){}
        }
    }
    @Test public void failedCurrentReceiverStopsServiceEvenAfterFailureClearsResume()throws Exception{
        var life=Robolectric.buildService(LanAudioService.class).create();try{settings.prefs.edit().putBoolean("resume",false).apply();
            var finish=LanAudioService.class.getDeclaredMethod("finishReceiver",long.class);finish.setAccessible(true);finish.invoke(life.get(),0L);assertTrue(Shadows.shadowOf(life.get()).isStoppedBySelf());
        }finally{life.destroy();}
    }
    @Test public void replacedReceiverCannotStopNewTransport()throws Exception{
        var life=Robolectric.buildService(LanAudioService.class).create();try{
            var field=LanAudioService.class.getDeclaredField("transport");field.setAccessible(true);((TransportEpoch)field.get(life.get())).next();
            var finish=LanAudioService.class.getDeclaredMethod("finishReceiver",long.class);finish.setAccessible(true);finish.invoke(life.get(),0L);assertFalse(Shadows.shadowOf(life.get()).isStoppedBySelf());
        }finally{life.destroy();}
    }
}
