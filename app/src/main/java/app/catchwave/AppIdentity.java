package app.catchwave;

final class AppIdentity {
    private AppIdentity(){}
    static String version(){return BuildConfig.VERSION_NAME;}
    static String label(){return "CatchWave "+version();}
    static String userAgent(){return label()+" (Android; music recognition)";}
}
