package app.catchwave;
import java.nio.file.*;
public final class LinkProbe {
    public static void main(String[] args) throws Exception {
        String url=args[0].equals("--live")?new RecognitionClient().resolve(new Track("sneaky","Sneaky Snitch","Kevin MacLeod","1891353860",59981,0,0)):RecognitionClient.parseSongLink(Files.readString(Path.of(args[0])));
        System.out.println("YOUTUBE_MUSIC_LINK "+url);
        if(!RecognitionClient.validMusicUrl(url))throw new AssertionError("Missing YouTube Music URL");
        System.out.println("PASS: direct YouTube Music link");
    }
}
