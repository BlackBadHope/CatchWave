# Data and permissions

CatchWave has no account, backend, advertising ID or analytics. Audio buffers and sensor processing remain in RAM; the app does not record them to files. Recognition fingerprints are sent over HTTPS to Shazam; title/artist searches use YouTube. These services see the request's network address.

LAN audio and control use pinned TLS on the local network. Pairing stores the PC endpoint, certificate pin and authentication token in Android private preferences. The QR contains those values: scan locally and do not publish it. Forget pairing removes endpoint/pin/token. Preferences also store owner choices and finite last-error/stage codes. A private local journal retains at most 256 metadata events (under 128 KiB plus one bounded temporary snapshot): wall time/timezone, nonsecret random attempt/session IDs, fixed codes, numeric frame/route/focus/volume/control counters. Persistence is asynchronous about once per second. Raw sensor vectors, audio and player titles are not part of the targeted LAN diagnostic dump. Android backup is disabled.

Windows creates a process pairing token and certificate, imports the key temporarily for Schannel, and disposes it with the Host. No certificate trust is installed. Host control permission is saved as a boolean in the user's local application data; the pairing identity is not stored in that file. A bounded diagnostic journal is stored beside it in local application data. Restarting the Host requires a new scan.

View/export is an explicit owner action using the native file chooser; there is no automatic upload. Journals exclude audio, raw sensor vectors, tokens, certificate pins, QR, endpoints, device names, private messages and track titles.

Camera supports local QR scanning. Vibration signals the timed command window and uses the normal VIBRATE permission. Notification display supports the foreground playback service. Notification access is a separate permission for the original YouTube Music media-session mode; message notification text is not read. Microphone and optional screen/audio capture consent apply to recognition/acoustic refinement. Screen-off gestures are a separate owner choice and may consume battery through a partial wake lock.

Normal LAN operation requires no USB, ADB, root or access to private app/message data. Installation, native permission/firewall prompts and pairing confirmation are owner actions. Do not publish credentials, signing keys, private QR/preferences, audio, full logcat, device identifiers or network details in issues.
