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
        return "Обработка музыки — на этом телефоне: микрофон и звуковой отпечаток считаются здесь. "
            +"Приложение Shazam не нужно, не ставится и не открывается.\n\n"
            +"Чтобы узнать название и секунду внутри записи, отпечаток сверяется с каталогом в интернете. "
            +"Встроенной базы всех песен у Android нет: ни Pixel Now Playing, ни другой системный API "
            +"не отдаёт стороннему приложению таймкод для перемотки.\n\n"
            +"YouTube Music — выбранный плеер, чтобы продолжить слушать. Это не распознаватель.";
    }
}
