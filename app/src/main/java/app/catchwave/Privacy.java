package app.catchwave;

/** What leaves the phone. No advertising ID, analytics or account. */
final class Privacy {
    private Privacy(){}
    static String title(){return L.t("What leaves the phone");}
    static String notice(){
        return L.t("There is no advertising ID. There are no analytics, no CatchWave account and no ads of our own in the app.\n\n")
            +RecognitionPath.explanation()+"\n\n"
            +L.t("On the device: microphone, internal audio and PC audio buffers are kept only in RAM. Settings and the latest LAN error/stage codes are stored in the app's private settings. The PC address, certificate pin and pairing token are stored there too; «Forget pairing» deletes them. A local ring log keeps the last 256 LAN events: time/time zone, a random session ID, stages, focus/route, counters, volume and command results. Audio, raw sensor data, QR codes, tokens and track titles are not recorded. You choose whether to view or export it. There are no Android backups.\n\n")
            +L.t("Recognition, HTTPS only:\n")
            +L.t("• amp.shazam.com — fingerprint catalog (not the Shazam app, not a recording). Title, artist, timecode estimate. The UUID in the address is single-use.\n")
            +L.t("• music.youtube.com — search for the same recording for the player. Launch via the native YouTube Music media session if it is installed.\n\n")
            +L.t("PC audio: PCM and commands travel over the local network via TLS with pin and token checks. The QR contains pairing data; camera scanning happens locally. Windows Host saves the control permission choice and a limited local metadata log; its key and token are created for the current run.\n\n")
            +L.t("Notification access is needed for the YouTube Music media session. Notification text is not read. The permission to show notifications is used by the background playback service. The camera is needed for the QR. Gestures with the screen off are enabled separately and may use battery.\n\n")
            +L.t("The catalog and YouTube see the request IP. These are their services, not our collection. 3×≤120 ms is recognizer consistency, not a promise of audible milliseconds.");
    }
}
