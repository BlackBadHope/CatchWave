package app.catchwave;
import java.nio.*;
import java.nio.file.*;
/** One public-fixture recognition request for the new three-second first window. */
public final class FastRecognitionProbe {
    public static void main(String[] args) throws Exception {
        byte[] wav=Files.readAllBytes(Path.of(args[0]));short[] pcm=new short[48000];
        ByteBuffer.wrap(wav,44,pcm.length*2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        long start=System.nanoTime();Track t=new RecognitionClient().recognize(pcm,10000);
        long elapsed=(System.nanoTime()-start)/1_000_000;
        if(t==null)throw new AssertionError("Three-second fixture did not match; six-second retry is required");
        System.out.println("title="+t.title+" artist="+t.artist+" offsetMs="+t.offsetMs+" sampleMs="+t.sampleDurationMs+" skew="+t.timeSkew+" processingAndNetworkMs="+elapsed);
        if(!"Sneaky Snitch".equals(t.title)||!t.hasPosition()||t.sampleDurationMs!=3000||Math.abs(t.offsetMs-59981)>250)throw new AssertionError("Unexpected recording or offset");
        System.out.println("PASS: 3-second public fixture, correct recording and offset; 1 recognition HTTP request");
    }
}
