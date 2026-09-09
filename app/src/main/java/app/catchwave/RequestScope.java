package app.catchwave;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cancellation belongs to one capture generation, including its catalog lookup. */
public final class RequestScope {
    private static final ExecutorService closers=Executors.newCachedThreadPool(r -> {
        Thread thread=new Thread(r,"CatchWave-request-close"); thread.setDaemon(true); return thread;
    });
    public static final class CancelledException extends IOException {
        public CancelledException() { super("Запрос отменён."); }
    }

    private final Object lock = new Object();
    private final Set<HttpURLConnection> connections =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean cancelled;

    public boolean isCancelled() { return cancelled; }

    public void throwIfCancelled() throws CancelledException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancelledException();
    }

    public void register(HttpURLConnection connection) throws CancelledException {
        boolean reject;
        synchronized (lock) {
            reject = cancelled;
            if (!reject) connections.add(connection);
        }
        if (reject) {
            disconnect(connection);
            throw new CancelledException();
        }
    }

    public void unregister(HttpURLConnection connection) {
        synchronized (lock) { connections.remove(connection); }
    }

    public void cancel() {
        ArrayList<HttpURLConnection> pending;
        synchronized (lock) {
            if (cancelled) return;
            cancelled = true;
            pending = new ArrayList<>(connections);
            connections.clear();
        }
        // Transport callbacks may re-enter this scope. Never call them under lock.
        // HttpURLConnection.disconnect may wait for a transport lock. Keep that off the UI thread.
        for (HttpURLConnection connection : pending) disconnectAsync(connection);
    }

    static void disconnectAsync(HttpURLConnection connection) { closers.execute(() -> disconnect(connection)); }

    static void disconnect(HttpURLConnection connection) {
        try { connection.disconnect(); } catch (RuntimeException ignored) { /* Continue closing other requests. */ }
    }
}
