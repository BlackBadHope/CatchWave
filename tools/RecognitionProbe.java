package app.catchwave;
import java.nio.*;
import java.nio.file.*;
import java.util.Arrays;

/** Bounded integration gate: exactly two recognition calls and one link lookup on public test audio. */
public final class RecognitionProbe {
    public static void main(String[] args) throws Exception {
        byte[] wav=Files.readAllBytes(Path.of(args[0]));
        ByteBuffer header=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if(header.getInt(24)!=16000||header.getShort(22)!=1||header.getShort(34)!=16)throw new IllegalArgumentException("Expected mono16 16kHz WAV");
        short[] samples=new short[(wav.length-44)/2];header.position(44);header.asShortBuffer().get(samples);
        RecognitionClient client=new RecognitionClient();
        Track first=client.recognize(Arrays.copyOfRange(samples,0,Math.min(samples.length,96000)),10000);
        if(first==null||!first.hasPosition())throw new AssertionError("No valid recognition");
        System.out.println("RECOGNIZED title="+first.title+" artist="+first.artist+" offsetMs="+first.offsetMs+" skew="+first.timeSkew+" appleId="+first.appleId);
        Track shifted=client.recognize(Arrays.copyOfRange(samples,32000,Math.min(samples.length,128000)),12000);
        if(shifted==null||!first.key.equals(shifted.key))throw new AssertionError("Shifted clip identified a different recording");
        long difference=shifted.offsetMs-first.offsetMs;
        System.out.println("OFFSET_DELTA "+difference+" ms, expected 2000 ± 250 ms");
        if(Math.abs(difference-2000)>250)throw new AssertionError("Offset semantics failed");
        String link=client.resolve(first);System.out.println("YOUTUBE_MUSIC_LINK "+link);
        if(!RecognitionClient.validMusicUrl(link))throw new AssertionError("No direct YouTube Music link");
        System.out.println("PASS: fingerprint + online recognition + offset + YouTube Music mapping");
    }
}
