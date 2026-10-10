# Known limitations

CatchWave is experimental. A single OnePlus 12 manual pilot accepted LAN audio/gestures, including held volume in Android 25. Broader reliability, latency, quality and screen-off battery behavior remain unmeasured.

- Host capture uses the current default multimedia output. No independent CatchWave endpoint, selector, discovery, route switch, resampling, internet relay or firewall installer exists.
- AudioRelay parity is not established. Existing virtual output can be captured, but no AudioRelay source/private protocol is included. Tilt controls local Android media volume.
- WASAPI supports mono/stereo PCM at 44100/48000/96000 Hz. Unsupported formats fail explicitly. Stalls drop stale audio; seamless recovery and end-to-end latency are not guaranteed.
- With management enabled, ordinary phone handling can trigger commands. An owner pickup caused tilt to reduce volume to silence. Keep management off outside deliberate use/testing; version 27 added timed relative arming; version 29 fixes integration resets and adds optional sound feedback, but has not yet demonstrated a reliable reduction in accidental input. It is not production-ready.
- Gestures need neutral/fresh sensors. Held native volume steps repeat at most every 400 ms. Motion/posture/OEM behavior can affect detection. Windows player support determines media-key effects; ACK is not proof of playback transition.
- A relative screen-down quarter-turn opens a fixed 10 s window. Stable return/face-up, expiry, sensor/connection/permission loss close it; pickup requires fresh neutral. Sensors infer posture, not contact with a table or human direction. Identical-orientation translation cannot establish whether the phone was picked up. Thresholds need physical testing.
- Diagnostics retain at most 256 metadata events locally. Disk failures keep RAM history and increment a counter; the last up-to-1 s of events can be lost on abrupt process kill. There is no audio recording or automatic log upload.
- Optional screen-off gestures use a foreground service and partial wake lock. Long runs, battery cost and Bluetooth routes need testing. Android power management can stop the process.
- Host restart invalidates pairing. Scan a new private QR locally. Never publish pairing data/signing keys.
- The experimental APK uses a local debug signer. Different signers prevent in-place updates. Production signing and a new binary GitHub Release are outside this source update.
- Recognition uses third-party HTTPS and multiple consistent fragments. Matching metadata can select another master/remix/live version. YouTube availability varies by account/region.
- Timeline alignment/acoustic refinement remain experimental; millisecond synchronization is not guaranteed. Handoff seeks to estimated position without adding callback lag. Silent/denied capture invalidates acoustic refinement. Live Sync requires headphones/media-session access; queue/metadata gaps can need user intervention.
- iOS, continuous acoustic Live Sync refinement and automatic version disambiguation are not implemented.
- Android minSdk30 is a declared installation/API baseline, not evidence for every model. Accel+gyro are required for all gestures; without either gestures are disabled. Missing game rotation vector falls back to rotation vector; absence of both locks all gesture commands because timed arming needs orientation. The current QR pairing needs a camera. Windows ARM64/x86, Linux/macOS Host and other Android models are not physically validated; no Linux/macOS Host or iOS client exists.
