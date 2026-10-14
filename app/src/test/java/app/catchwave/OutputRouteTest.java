package app.catchwave;

import android.media.AudioDeviceInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class OutputRouteTest {
    @Test public void mediaSpeakerRouteDoesNotQualifyAsHeadphones(){
        assertFalse(MediaBridge.headphonesForTypes(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        assertEquals("System output: speaker",MediaBridge.outputLabelForTypes(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        assertFalse(MediaBridge.headphonesForTypes(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE));
    }
    @Test public void routedWiredAndUsbHeadsetsQualify(){
        for(int type:new int[]{AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET}){
            assertTrue(MediaBridge.headphonesForTypes(type));
            assertEquals("System output: wired headphones",MediaBridge.outputLabelForTypes(type));
        }
    }
    @Test public void routedBluetoothMediaHeadsetsQualify(){
        for(int type:new int[]{AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLE_HEADSET}){
            assertTrue(MediaBridge.headphonesForTypes(type));
            assertEquals("System output: Bluetooth",MediaBridge.outputLabelForTypes(type));
        }
    }
    @Test public void scoAndBleSpeakerNeverQualifyAsHeadphones(){
        for(int type:new int[]{AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_SPEAKER}){
            assertFalse(MediaBridge.headphonesForTypes(type));
            assertEquals("System output: Bluetooth",MediaBridge.outputLabelForTypes(type));
        }
    }
    @Test public void anySpeakerInMixedMediaRoutePreventsHeadphoneGate(){
        assertFalse(MediaBridge.headphonesForTypes(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        assertFalse(MediaBridge.headphonesForTypes(AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        assertEquals("System output: unknown",MediaBridge.outputLabelForTypes(AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
    }
    @Test public void missingOrUnclassifiedRoutesStayUnknown(){
        assertFalse(MediaBridge.headphonesForTypes());
        assertFalse(MediaBridge.headphonesForTypes(AudioDeviceInfo.TYPE_USB_DEVICE));
        assertEquals("System output: unknown",MediaBridge.outputLabelForTypes());
        assertEquals("System output: unknown",MediaBridge.outputLabelForTypes(AudioDeviceInfo.TYPE_UNKNOWN));
    }
}
