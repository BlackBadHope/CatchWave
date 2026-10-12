package app.catchwave;

import android.os.Looper;
import android.os.NetworkOnMainThreadException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class LanReceiverShutdownTest {
    private LanSettings settings;
    private LanReceiver receiver;
    private static final LanReceiver.Listener LISTENER=new LanReceiver.Listener(){
        public boolean ready(){return false;} public void connected(){} public void finished(){} public void ack(boolean accepted){}
    };
    @Before public void setup(){
        settings=new LanSettings(RuntimeEnvironment.getApplication());
        settings.prefs.edit().clear().putBoolean("explicitControlUi21",true).commit();
        LanState.INSTANCE.started=false;LanState.INSTANCE.connected=false;LanState.INSTANCE.error=LanConnection.Error.NONE;
        receiver=new LanReceiver(settings,LISTENER);
        assertSame("Regression must execute on Android's main looper",Looper.getMainLooper(),Looper.myLooper());
    }
    @After public void cleanup(){settings.prefs.edit().clear().commit();LanState.INSTANCE.started=false;LanState.INSTANCE.connected=false;}
    private void socket(SSLSocket value)throws Exception{
        Field f=LanReceiver.class.getDeclaredField("socket");f.setAccessible(true);Object slot=f.get(receiver);
        if(slot instanceof AtomicReference){((AtomicReference<SSLSocket>)slot).set(value);}else f.set(receiver,value);
    }
    private boolean active()throws Exception{Field f=LanReceiver.class.getDeclaredField("active");f.setAccessible(true);return ((AtomicBoolean)f.get(receiver)).get();}
    @Test public void serviceTeardownClosesTlsOffMainThread()throws Exception{
        BlockingTls socket=new BlockingTls(false);socket(socket);
        ServiceController<LanAudioService> life=Robolectric.buildService(LanAudioService.class).create();
        Field f=LanAudioService.class.getDeclaredField("receiver");f.setAccessible(true);f.set(life.get(),receiver);
        LanState.INSTANCE.started=true;LanState.INSTANCE.connected=true;
        try{life.destroy();}finally{socket.release.countDown();}
        assertFalse(active());assertFalse(LanState.INSTANCE.started);assertFalse(LanState.INSTANCE.connected);
        assertTrue(socket.done.await(2,TimeUnit.SECONDS));
        assertNotSame(Looper.getMainLooper().getThread(),socket.closedOn);
        assertEquals(1,socket.closes.get());
    }
    @Test public void repeatedCloseReturnsWhileTlsCloseIsBlockedAndClosesOnce()throws Exception{
        BlockingTls socket=new BlockingTls(true);socket(socket);
        try{
            receiver.close();assertFalse(active());assertTrue(socket.entered.await(2,TimeUnit.SECONDS));
            receiver.close();receiver.close();assertEquals(1,socket.closes.get());
        }finally{socket.release.countDown();}
        assertTrue(socket.done.await(2,TimeUnit.SECONDS));
    }
    @Test public void cancellationBeforeSocketPublicationStillClosesLateSocket()throws Exception{
        receiver.close();BlockingTls socket=new BlockingTls(false);
        Method publish=LanReceiver.class.getDeclaredMethod("publishSocket",SSLSocket.class);publish.setAccessible(true);
        assertEquals(Boolean.FALSE,publish.invoke(receiver,socket));
        assertFalse(active());assertTrue(socket.done.await(2,TimeUnit.SECONDS));assertEquals(1,socket.closes.get());
    }
    private static final class BlockingTls extends SSLSocket {
        final AtomicInteger closes=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),done=new CountDownLatch(1),release;
        volatile Thread closedOn;
        BlockingTls(boolean block){release=new CountDownLatch(block?1:0);}
        @Override public void close()throws IOException{
            closes.incrementAndGet();closedOn=Thread.currentThread();
            if(Looper.myLooper()==Looper.getMainLooper())throw new NetworkOnMainThreadException();
            entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IOException("Test close deadline");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            finally{done.countDown();}
        }
        public String[] getSupportedCipherSuites(){return new String[0];}
        public String[] getEnabledCipherSuites(){return new String[0];}
        public void setEnabledCipherSuites(String[] value){}
        public String[] getSupportedProtocols(){return new String[0];}
        public String[] getEnabledProtocols(){return new String[0];}
        public void setEnabledProtocols(String[] value){}
        public SSLSession getSession(){return null;}
        public void addHandshakeCompletedListener(HandshakeCompletedListener value){}
        public void removeHandshakeCompletedListener(HandshakeCompletedListener value){}
        public void startHandshake(){}
        public void setUseClientMode(boolean value){}
        public boolean getUseClientMode(){return true;}
        public void setNeedClientAuth(boolean value){}
        public boolean getNeedClientAuth(){return false;}
        public void setWantClientAuth(boolean value){}
        public boolean getWantClientAuth(){return false;}
        public void setEnableSessionCreation(boolean value){}
        public boolean getEnableSessionCreation(){return true;}
    }
}
