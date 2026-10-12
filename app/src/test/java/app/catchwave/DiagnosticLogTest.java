package app.catchwave;

import java.time.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticLogTest {
    DiagnosticLog log() {
        return new DiagnosticLog(
                () -> Instant.parse("2026-10-10T20:00:00Z"), ZoneId.of("Europe/Kyiv"));
    }

    @Test
    public void boundedRingHasTimeZoneAndCorrelationButRejectsArbitraryPayload() {
        DiagnosticLog l = log();
        l.session(new byte[16]);
        l.event(DiagnosticLog.Event.STOP, "private endpoint/token");
        for (int i = 0; i < 1000; i++) l.event(DiagnosticLog.Event.SNAPSHOT, "PCM_SENT", i);
        String t = l.text();
        assertEquals(256, t.lines().count());
        assertTrue(t.contains("2026-10-10T20:00:00Z zone=Europe/Kyiv offset=+03:00 session="));
        assertFalse(t.contains("private"));
        assertTrue(t.contains("v0=999"));
        assertFalse(t.contains("v0=743\n"));
        assertTrue(
                t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        < DiagnosticLog.MAX_BYTES);
    }

    @Test
    public void silentSourceAndStoppedStreamAreDifferent() {
        assertEquals("SILENT_SOURCE", DiagnosticLog.output(true, true, 50, false, 2));
        assertEquals("STOPPED_STREAM", DiagnosticLog.output(false, true, 50, false, 2));
        assertEquals("OUTPUT_ACCEPTED", DiagnosticLog.output(true, false, 50, false, 2));
    }

    @Test
    public void focusRouteAndZeroVolumeHaveExplicitClassification() {
        assertEquals("FOCUS_LOST", DiagnosticLog.output(true, false, 50, true, 2));
        assertEquals("ROUTE_UNKNOWN", DiagnosticLog.output(true, false, 50, false, -1));
        assertEquals("VOLUME_ZERO", DiagnosticLog.output(true, false, 0, false, 2));
    }

    @Test
    public void gestureMuteAndVolumeZeroHistorySurviveRestore() {
        DiagnosticLog a = log();
        a.event(DiagnosticLog.Event.GESTURE, "MUTE");
        a.event(DiagnosticLog.Event.VOLUME, "MUTE", 94, 0, 94);
        DiagnosticLog b = log();
        b.restore(a.text() + "CW1/private/secret\n");
        assertTrue(b.text().contains("code=MUTE v0=94 v1=0 v2=94"));
        assertFalse(b.text().contains("secret"));
    }

    @Test
    public void brokenClockDoesNotBreakCallerAndOversizedRestoreIsRejected() {
        DiagnosticLog l =
                new DiagnosticLog(
                        () -> {
                            throw new IllegalStateException("private failure");
                        },
                        ZoneOffset.UTC);
        l.event(DiagnosticLog.Event.STOP, "INTERNAL");
        assertEquals(1, l.failures());
        assertEquals("\n", l.text());
        l.restore("x".repeat(DiagnosticLog.MAX_BYTES + 1));
        assertEquals("\n", l.text());
    }

    @Test
    public void asynchronousRestoreKeepsOldBeforeNewAndDoesNotEvictFreshStartup() {
        DiagnosticLog old = log();
        for (int i = 0; i < 256; i++) old.event(DiagnosticLog.Event.SNAPSHOT, "OLD", i);
        DiagnosticLog current = log();
        current.event(DiagnosticLog.Event.RECONNECT, "START_REQUESTED");
        current.restore(old.text());
        assertEquals(256, current.text().lines().count());
        assertTrue(current.text().stripTrailing().endsWith("event=RECONNECT code=START_REQUESTED"));
    }
}
