// SPDX-License-Identifier: GPL-3.0-or-later
package app.catchwave;
/** Fixed public diagnostic codes; never exception text or credentials. */
final class LanConnection {
    enum Stage { DISCONNECTED, SCANNED, STARTING, TCP, TLS, AUTH, AUTHENTICATED, FORMAT, FOCUS, OUTPUT, SILENCE, STREAMING, ERROR, BUFFERING, PHONE_AUDIO }
    enum Error { NONE, ANDROID_START, CONNECT_TIMEOUT, CONNECT_REFUSED, NETWORK, TLS_HANDSHAKE, PIN_MISMATCH, CERT_TIME,
        AUTH_REJECTED_OR_CLOSED, PROTOCOL, HOST_TIMEOUT, HOST_CLOSED, AUDIO_FOCUS, AUDIO_FOCUS_LOST, PCM_OUTPUT, AUDIO_STALLED, INTERNAL, USB_SETUP, USB_DISCONNECTED }
    static String title(Stage stage) { return switch(stage) {
        case DISCONNECTED -> L.t("No connection"); case SCANNED -> L.t("QR confirmed — ready to connect");
        case STARTING -> L.t("Starting Android service…"); case TCP -> L.t("Connecting to PC…"); case TLS -> L.t("Checking secure connection…");
        case AUTH -> L.t("Confirming pairing…"); case AUTHENTICATED -> L.t("Pairing accepted by the PC");
        case FORMAT -> L.t("Audio format received"); case FOCUS -> L.t("Getting audio focus…"); case OUTPUT -> L.t("Preparing phone audio…");
        case SILENCE -> L.t("Connection working — receiving silence"); case STREAMING -> L.t("PC audio is playing"); case ERROR -> L.t("Connection stopped — error");
        case BUFFERING -> L.t("Connection kept — waiting for fresh audio");
        case PHONE_AUDIO -> L.t("Phone audio has priority — PC connection kept");
    }; }
    static String remedy(Error error) { return switch(error) {
        case NONE -> "";
        case USB_SETUP -> L.t("Could not connect via USB. Check the cable and allow CatchWave access to the USB accessory.");
        case USB_DISCONNECTED -> L.t("Cable disconnected. To switch to Wi-Fi automatically, first save the QR of this same Host.");
        case ANDROID_START -> L.t("Android did not allow starting the service. Open CatchWave and retry the connection.");
        case CONNECT_TIMEOUT -> L.t("The PC did not answer over TCP. Check the address, Wi-Fi and the Host port.");
        case CONNECT_REFUSED -> L.t("The PC port does not accept connections. Start the Host; after it restarts a new QR is needed.");
        case NETWORK -> L.t("The network connection dropped. Check the connection to the PC.");
        case TLS_HANDSHAKE -> L.t("TLS was not established. Check that the Host and app versions match.");
        case PIN_MISMATCH -> L.t("The Host certificate changed. Scan a new QR from the current Host window.");
        case CERT_TIME -> L.t("The certificate is outside its validity period. Check the phone and PC dates; restart the Host and scan a new QR.");
        case AUTH_REJECTED_OR_CLOSED -> L.t("The Host closed the connection before confirming pairing. The current Host's QR is needed; the exact reason is shown on the PC.");
        case PROTOCOL -> L.t("Incompatible data received. Use the app and Host from the same release.");
        case HOST_TIMEOUT -> L.t("The Host stopped sending data. Check the status in the PC window.");
        case HOST_CLOSED -> L.t("The Host closed the connection. After it restarts, scan a new QR.");
        case AUDIO_FOCUS -> L.t("Android did not grant audio focus. Another player may have kept audio control.");
        case AUDIO_FOCUS_LOST -> L.t("Android gave audio focus to another app.");
        case PCM_OUTPUT -> L.t("Could not start or continue PCM output on the phone.");
        case AUDIO_STALLED -> L.t("Audio frames went stale. The connection was stopped so buffered audio is not played.");
        case INTERNAL -> L.t("Unexpected CatchWave error. Report the code and stage shown here.");
    }; }
    static boolean effectiveControl(boolean live,boolean desired,boolean hostAllowed) { return live&&desired&&hostAllowed; }
    private LanConnection() {}
}
