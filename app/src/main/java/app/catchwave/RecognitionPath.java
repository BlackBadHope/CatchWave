package app.catchwave;

/**
 * Three jobs, three places.
 * Processing is on this phone. Playback is the user's player.
 * No phone ships a full song catalog, so lookup still uses HTTPS.
 * The Shazam <em>app</em> is not a dependency and is never launched.
 */
final class RecognitionPath {
    static final String SHAZAM_APP="com.shazam.android";
    private RecognitionPath(){}
    static boolean requiresShazamApp(){return false;}
    static boolean requiresYoutubeMusicApp(){return true;}
    static String explanation(){
        return L.t("Music processing happens on this phone: the microphone and audio fingerprint are computed here. ")
            +L.t("The Shazam app is not needed, installed or opened.\n\n")
            +L.t("To find the title and the second within the recording, the fingerprint is matched against an online catalog. ")
            +L.t("Android has no built-in database of all songs: neither Pixel Now Playing nor any other system API ")
            +L.t("gives a third-party app a timecode for seeking.\n\n")
            +L.t("YouTube Music is the chosen player to keep listening. It is not the recognizer.");
    }
}
