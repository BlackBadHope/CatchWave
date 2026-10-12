// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** Private app storage, bounded background snapshots; disk failures never stop audio. */
final class DiagnosticStore {
    static final DiagnosticLog LOG = new DiagnosticLog();
    private static boolean started;
    private static volatile long failures;

    static synchronized void start(Context context) {
        if (started) return;
        started = true;
        Path file = context.getFilesDir().toPath().resolve("lan-diagnostics.log");
        ScheduledExecutorService worker =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "CatchWave-diagnostics");
                            t.setDaemon(true);
                            return t;
                        });
        worker.execute(
                () -> {
                    try {
                        if (Files.exists(file) && Files.size(file) <= DiagnosticLog.MAX_BYTES)
                            LOG.restore(
                                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                    } catch (Exception ignored) {
                        failures++;
                    }
                });
        worker.scheduleWithFixedDelay(
                new Runnable() {
                    long saved = -1;

                    public void run() {
                        try {
                            long v = LOG.version();
                            if (v == saved) return;
                            Path tmp = file.resolveSibling("lan-diagnostics.tmp");
                            Files.write(tmp, LOG.text().getBytes(StandardCharsets.UTF_8));
                            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                            saved = v;
                        } catch (Exception ignored) {
                            failures++;
                        }
                    }
                },
                1,
                1,
                TimeUnit.SECONDS);
    }

    static long failures() {
        return failures + LOG.failures();
    }

    private DiagnosticStore() {}
}
