// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
import android.content.Intent;
import android.net.*;
import java.net.InetAddress;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class LanRecoveryTest {
    @Test public void routeNeedsDirectWifiPrefixRatherThanMobileDefault()throws Exception {
        var wifi=new NetworkCapabilities();
        ReflectionHelpers.callInstanceMethod(wifi,"addTransportType",ClassParameter.from(int.class,NetworkCapabilities.TRANSPORT_WIFI));
        ReflectionHelpers.callInstanceMethod(wifi,"addCapability",ClassParameter.from(int.class,NetworkCapabilities.NET_CAPABILITY_NOT_VPN));
        RouteInfo route=ReflectionHelpers.callConstructor(RouteInfo.class,ClassParameter.from(IpPrefix.class,new IpPrefix(InetAddress.getByName("192.168.1.0"),24)),ClassParameter.from(InetAddress.class,null),ClassParameter.from(String.class,"wlan0"));
        var links=new LinkProperties();ReflectionHelpers.callInstanceMethod(links,"setInterfaceName",ClassParameter.from(String.class,"wlan0"));
        ReflectionHelpers.callInstanceMethod(links,"addRoute",ClassParameter.from(RouteInfo.class,route));
        assertTrue(LanNetwork.reaches(wifi,links,InetAddress.getByName("192.168.1.101")));
        assertFalse(LanNetwork.reaches(wifi,links,InetAddress.getByName("192.168.2.101")));
        var mobile=new NetworkCapabilities();ReflectionHelpers.callInstanceMethod(mobile,"addTransportType",ClassParameter.from(int.class,NetworkCapabilities.TRANSPORT_CELLULAR));
        assertFalse(LanNetwork.reaches(mobile,links,InetAddress.getByName("192.168.1.101")));
    }
    @Test public void recoveryIsBoundedAndNeverRetriesChangedIdentityOrProtocol(){
        var retry=new LanRecovery();retry.begin(100);
        assertEquals(250,retry.retry(LanConnection.Error.CONNECT_REFUSED,101));
        assertEquals(-1,retry.retry(LanConnection.Error.PIN_MISMATCH,102));
        assertEquals(-1,retry.retry(LanConnection.Error.PROTOCOL,102));
        assertEquals(750,retry.retry(LanConnection.Error.HOST_CLOSED,103));
        assertEquals(1500,retry.retry(LanConnection.Error.CONNECT_TIMEOUT,104));
        assertEquals(-1,retry.retry(LanConnection.Error.NETWORK,105));
        retry.begin(200);assertEquals(-1,retry.retry(LanConnection.Error.NETWORK,20200));
        retry.stop();assertFalse(retry.active());assertEquals(-1,retry.retry(LanConnection.Error.NETWORK,201));
    }
    @Test public void ownerStopAndLateRefreshCancelPendingRecoveryBeforeSocketCreation()throws Exception {
        var settings=new LanSettings(RuntimeEnvironment.getApplication());settings.prefs.edit().clear().apply();settings=new LanSettings(RuntimeEnvironment.getApplication());
        var life=Robolectric.buildService(LanAudioService.class).create();
        try{settings.prefs.edit().putBoolean("resume",true).apply();LanState.INSTANCE.connected=true;
            var retry=LanAudioService.class.getDeclaredMethod("retryWifi",LanConnection.Error.class,LanConnection.Stage.class);retry.setAccessible(true);
            assertTrue((boolean)retry.invoke(life.get(),LanConnection.Error.NETWORK,LanConnection.Stage.STREAMING));
            assertTrue(settings.resume());assertEquals(LanConnection.Error.NONE,LanState.INSTANCE.error);
            var finish=LanAudioService.class.getDeclaredMethod("finishReceiver",long.class);finish.setAccessible(true);finish.invoke(life.get(),0L);
            assertFalse(Shadows.shadowOf(life.get()).isStoppedBySelf());
            life.get().onStartCommand(new Intent().setAction(LanAudioService.STOP),0,1);
            life.get().onStartCommand(new Intent().setAction(LanAudioService.REFRESH),0,2);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertFalse(settings.resume());assertTrue(Shadows.shadowOf(life.get()).isStoppedBySelf());
            var receiver=LanAudioService.class.getDeclaredField("receiver");receiver.setAccessible(true);assertNull(receiver.get(life.get()));
        }finally{life.destroy();settings.prefs.edit().clear().apply();LanState.INSTANCE.reset(settings);}
    }
}
