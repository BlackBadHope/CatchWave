package app.catchwave;

import org.junit.Test;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RequestScopeTest {
    private static class Connection extends HttpURLConnection {
        final AtomicInteger closes=new AtomicInteger();
        final CountDownLatch closed=new CountDownLatch(1);
        Connection() throws MalformedURLException { super(new URL("https://fixture.invalid/")); }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { closes.incrementAndGet(); closed.countDown(); }
    }

    @Test(timeout=5000) public void cancellationIsIdempotentAndClosesRegisteredConnectionOnce() throws Exception {
        RequestScope scope=new RequestScope(); Connection connection=new Connection();
        scope.register(connection); scope.register(connection); scope.cancel(); scope.cancel();
        assertTrue(scope.isCancelled());
        assertTrue(connection.closed.await(1,TimeUnit.SECONDS)); assertEquals(1,connection.closes.get());
        try { scope.throwIfCancelled(); fail("cancelled scope cannot continue"); }
        catch(RequestScope.CancelledException expected) { }
    }

    @Test public void registrationAfterCancellationIsRejectedAndClosed() throws Exception {
        RequestScope scope=new RequestScope(); scope.cancel(); Connection connection=new Connection();
        try { scope.register(connection); fail("late connection must not escape cancellation"); }
        catch(RequestScope.CancelledException expected) { }
        assertEquals(1,connection.closes.get());
    }

    @Test(timeout=5000) public void completedConnectionIsNotClosedAgainByCancellation() throws Exception {
        RequestScope scope=new RequestScope(); Connection finished=new Connection(), active=new Connection();
        scope.register(finished); scope.unregister(finished); scope.register(active);
        scope.cancel();
        assertTrue(active.closed.await(1,TimeUnit.SECONDS)); assertEquals(0,finished.closes.get());
    }

    @Test(timeout=5000) public void cancellationDoesNotWaitForTransportOrHoldScopeLock() throws Exception {
        RequestScope scope=new RequestScope();
        CountDownLatch closing=new CountDownLatch(1), release=new CountDownLatch(1);
        Connection blocked=new Connection() {
            @Override public void disconnect() {
                closing.countDown();
                try { release.await(2,TimeUnit.SECONDS); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); }
                super.disconnect();
            }
        };
        ExecutorService caller=Executors.newSingleThreadExecutor();
        try {
            scope.register(blocked);
            Future<?> cancelled=caller.submit(scope::cancel);
            cancelled.get(1,TimeUnit.SECONDS);
            assertTrue(closing.await(1,TimeUnit.SECONDS));
            // A second operation can enter the scope while transport shutdown is still blocked.
            caller.submit(() -> scope.unregister(blocked)).get(1,TimeUnit.SECONDS);
            assertTrue(scope.isCancelled()); assertEquals(1,blocked.closed.getCount());
        } finally { release.countDown(); caller.shutdownNow(); }
        assertTrue(blocked.closed.await(1,TimeUnit.SECONDS));
    }
}
