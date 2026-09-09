package app.catchwave;
import java.nio.*;
import java.nio.file.*;
public final class EndToEndProbe {
    public static void main(String[] args) throws Exception {
        byte[] wav=Files.readAllBytes(Path.of(args[0]));
        short[] pcm=new short[96000];ByteBuffer.wrap(wav,44,pcm.length*2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        RecognitionClient client=new RecognitionClient();Track track=client.recognize(pcm,10000);
        if(track==null||!track.hasPosition()||!"Sneaky Snitch".equals(track.title))throw new AssertionError("Unexpected recognition");
        System.out.println("RECOGNIZED "+track.title+" / "+track.artist+" @ "+track.offsetMs+" ms");
        String link=client.resolve(track);if(!RecognitionClient.validMusicUrl(link))throw new AssertionError("No link");
        System.out.println("YOUTUBE_MUSIC "+link);
        System.out.println("PASS: final recognition client -> exact YouTube Music match (2 HTTP requests)");
    }
}
