package app.catchwave;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
public class NetworkUiBudgetTest {
    @Test public void blockedUiRetainsOneTaskAndTheLatestOfFiftyThousandUpdates(){
        ArrayDeque<Runnable> queue=new ArrayDeque<>();AtomicInteger value=new AtomicInteger(),calls=new AtomicInteger();
        LatestCallback<Integer> callback=new LatestCallback<>(r->{queue.add(r);return true;},v->{value.set(v);calls.incrementAndGet();});
        for(int i=0;i<50000;i++)callback.accept(i);
        assertEquals("Untrusted network input cannot grow the main queue",1,queue.size());queue.remove().run();
        assertEquals(49999,value.get());assertEquals(1,calls.get());assertTrue(queue.isEmpty());
    }
    @Test public void policyBurstFailsBeforeUnlimitedUiDispatch()throws Exception{
        PacketRate rate=new PacketRate(64);for(int i=0;i<64;i++)rate.accept(1000);
        try{rate.accept(1000);fail("Authenticated policy flood was accepted");}catch(java.io.IOException expected){}
        rate.accept(2000);
    }
    @Test public void ordinaryPolicyUpdatesAndShortStartupBurstRemainValid()throws Exception{
        PacketRate rate=new PacketRate(64);for(int i=0;i<16;i++)rate.accept(1000);for(int i=1;i<100;i++)rate.accept(1000+i*500);
    }
}
