# Data and permissions

CatchWave has no account, backend, advertising ID or analytics. Audio buffers and sensor processing remain in RAM; the app does not record them to files. Recognition fingerprints are sent over HTTPS to Shazam; title/artist searches use YouTube. These services see the request's network address.

LAN audio and control use pinned TLS on the local network. Pairing stores the PC endpoint, certificate pin and authentication token in Android private preferences. The QR contains those values: scan locally and do not publish it. Forget pairing removes endpoint/pin/token. Preferences also store owner choices and finite last-error/stage codes. Raw sensor vectors, audio and player titles are not part of the targeted LAN diagnostic dump. Android backup is disabled.

Windows creates a process pairing token and certificate, imports the key temporarily for Schannel, and disposes it with the Host. No certificate trust is installed. Host control permission is saved as a boolean in the user's local application data; the pairing identity is not stored in that file. Restarting the Host requires a new scan.

Camera supports local QR scanning. Notification display supports the foreground playback service. Notification access is a separate permission for the original YouTube Music media-session mode; message notification text is not read. Microphone and optional screen/audio capture consent apply to recognition/acoustic refinement. Screen-off gestures are a separate owner choice and may consume battery through a partial wake lock.

Normal LAN operation requires no USB, ADB, root or access to private app/message data. Installation, native permission/firewall prompts and pairing confirmation are owner actions. Do not publish credentials, signing keys, private QR/preferences, audio, full logcat, device identifiers or network details in issues.
