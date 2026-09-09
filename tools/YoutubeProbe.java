package app.catchwave;
public final class YoutubeProbe {
    public static void main(String[] args) throws Exception {
        String url=new RecognitionClient().searchYoutube(new Track("sneaky","Sneaky Snitch","Kevin MacLeod","",59981,0,0));
        System.out.println("YOUTUBE_MUSIC_LINK "+url);
        if(!RecognitionClient.validMusicUrl(url))throw new AssertionError("No matched song URL");
        System.out.println("PASS: anonymous YouTube Music search and exact title/artist match");
    }
}
