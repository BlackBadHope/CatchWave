// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;

import android.media.*;
import java.util.concurrent.*;

/** Short quiet mono sweep mixed with media. No focus request, route or system volume changes. */
final class ActivationTone implements AutoCloseable {
    static final int RATE = 48000, DURATION_MS = 120;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(2),
                    r -> {
                        Thread t = new Thread(r, "CatchWave-tone");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.DiscardOldestPolicy());

    static short[] samples(boolean entering) {
        short[] result = new short[RATE * DURATION_MS / 1000];
        double phase = 0;
        for (int i = 0; i < result.length; i++) {
            double progress = i / (double) (result.length - 1),
                    frequency = entering ? 640 + 260 * progress : 900 - 260 * progress;
            phase += 2 * Math.PI * frequency / RATE;
            double edge = Math.min(1, Math.min(i, result.length - 1 - i) / (RATE * .015));
            double envelope = Math.sin(edge * Math.PI / 2);
            envelope *= envelope;
            result[i] = (short) Math.round(32767 * .04 * envelope * Math.sin(phase));
        }
        return result;
    }

    void play(boolean entering) {
        if (worker.isShutdown()) return;
        worker.execute(
                () -> {
                    AudioTrack track = null;
                    try {
                        short[] pcm = samples(entering);
                        track =
                                new AudioTrack.Builder()
                                        .setAudioAttributes(
                                                new AudioAttributes.Builder()
                                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                                        .setContentType(
                                                                AudioAttributes
                                                                        .CONTENT_TYPE_SONIFICATION)
                                                        .build())
                                        .setAudioFormat(
                                                new AudioFormat.Builder()
                                                        .setSampleRate(RATE)
                                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                                        .setChannelMask(
                                                                AudioFormat.CHANNEL_OUT_MONO)
                                                        .build())
                                        .setTransferMode(AudioTrack.MODE_STATIC)
                                        .setBufferSizeInBytes(pcm.length * 2)
                                        .build();
                        // MODE_STATIC is successfully created in STATE_NO_STATIC_DATA until PCM is
                        // loaded.
                        if (track.write(pcm, 0, pcm.length) != pcm.length
                                || track.getState() != AudioTrack.STATE_INITIALIZED)
                            throw new IllegalStateException();
                        track.play();
                        DiagnosticStore.LOG.event(
                                DiagnosticLog.Event.CONTROL, "TONE_STARTED", entering ? 1 : 0);
                        Thread.sleep(DURATION_MS + 30);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException failure) {
                        DiagnosticStore.LOG.event(DiagnosticLog.Event.CONTROL, "TONE_FAILED");
                    } finally {
                        if (track != null)
                            try {
                                track.release();
                            } catch (RuntimeException ignored) {
                            }
                    }
                });
    }

    @Override
    public void close() {
        worker.shutdown();
    }
}
