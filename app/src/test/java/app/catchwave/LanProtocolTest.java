package app.catchwave;

import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.Assert.*;

public class LanProtocolTest {
    @Test
    public void windowsWireFixtureRoundTrips() throws Exception {
        byte[] format = new byte[24];
        ByteBuffer fields = ByteBuffer.wrap(format);
        fields.position(16);
        fields.putInt(48000).put((byte) 2).put((byte) 16).putShort((short) 960);
        LanProtocol.Format f = new LanProtocol.Format(format);
        assertEquals(3840, f.bytes());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        LanProtocol.write(new DataOutputStream(bytes), 1, format);
        assertEquals(1, bytes.toByteArray()[0]);
        assertEquals(24, ByteBuffer.wrap(bytes.toByteArray(), 1, 4).getInt());
        assertArrayEquals(
                format,
                LanProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))
                        .body);
    }

    @Test
    public void oversizedAndTruncatedFramesRejected() throws Exception {
        assertThrows(
                IOException.class,
                () ->
                        LanProtocol.read(
                                new DataInputStream(
                                        new ByteArrayInputStream(new byte[] {2, 0, 1, 0, 0}))));
        assertThrows(
                EOFException.class,
                () ->
                        LanProtocol.read(
                                new DataInputStream(
                                        new ByteArrayInputStream(new byte[] {2, 0, 0, 0, 4, 1}))));
    }

    @Test
    public void badFormatRejected() {
        byte[] b = new byte[24];
        assertThrows(IOException.class, () -> new LanProtocol.Format(b));
    }

    private static byte[] frame(long sequence, long time) {
        return ByteBuffer.allocate(20).putLong(sequence).putLong(time).putInt(0).array();
    }

    @Test
    public void gapsDetectedAndReplayIsFatal() throws Exception {
        LanProtocol.AudioGate gate = new LanProtocol.AudioGate();
        assertFalse(gate.accept(frame(0, 20), 4));
        assertFalse(gate.accept(frame(1, 40), 4));
        assertTrue(gate.accept(frame(4, 100), 4));
        assertThrows(IOException.class, () -> gate.accept(frame(4, 100), 4));
        assertThrows(IOException.class, () -> gate.accept(frame(5, 99), 4));
        assertThrows(IOException.class, () -> gate.accept(new byte[100], 4));
    }

    @Test
    public void shortAudioWritesPreserveEveryByte() throws Exception {
        byte[] pcm = new byte[19];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        LanProtocol.writeFully(
                (b, o, n) -> {
                    int part = Math.min(n, 3);
                    actual.write(b, o, part);
                    return part;
                },
                pcm);
        assertArrayEquals(pcm, actual.toByteArray());
        assertThrows(IOException.class, () -> LanProtocol.writeFully((b, o, n) -> 0, pcm));
        assertThrows(IOException.class, () -> LanProtocol.writeFully((b, o, n) -> -6, pcm));
    }

    @Test
    public void commandCarriesSessionSequenceObservedAudioAndPolicyGeneration() {
        byte[] b = LanProtocol.command(new byte[16], 9, 20, 3, 6);
        assertEquals(41, b.length);
        assertEquals(9, ByteBuffer.wrap(b, 16, 8).getLong());
        assertEquals(20, ByteBuffer.wrap(b, 24, 8).getLong());
        assertEquals(3, b[32]);
        assertEquals(6, ByteBuffer.wrap(b, 33, 8).getLong());
    }

    @Test
    public void policyChangesBeforeFirstAudioAreAllowed() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        LanProtocol.write(out, LanProtocol.POLICY, new byte[9]);
        LanProtocol.write(out, LanProtocol.POLICY, new byte[9]);
        LanProtocol.write(out, LanProtocol.AUDIO, new byte[20]);
        int[] policies = {0};
        LanProtocol.Packet first =
                LanProtocol.firstAudio(
                        new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                        body -> policies[0]++);
        assertEquals(2, policies[0]);
        assertEquals(LanProtocol.AUDIO, first.kind);
    }
}
