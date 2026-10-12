# CatchWave

Experimental Android music companion, with a Windows-to-Android LAN audio receiver and gesture remote.

Current Android source: **0.1.31-unified-ui, versionCode 49**; Windows Host **0.1.31-desktop**. Both use one dark green visual system. The home screen opens either PC audio/gesture control or nearby-music recognition. Scan and connect/disconnect stay at the top of the PC-audio screen. Scanning during playback first asks to stop the session. Activation uses only a button, with no table pose or rotation prerequisite.

This release includes asynchronous TLS disconnect/cancellation, protection against a late refresh restarting a stopped session, background audio/power handling, bounded clock correction and continuity across small frame gaps. A delayed first orientation sample now starts a fresh neutral-calibration window. Permission, freshness, posture and accidental-input guards remain enabled. Previous physical checks confirmed audible audio and commands on one phone; automated tests and short listening checks do not establish long-term wireless reliability, battery life or AudioRelay parity.

Download the Android APK and portable Windows Host from [Releases](https://github.com/BlackBadHope/CatchWave/releases). See [UI verification](design-qa.md) and [Windows setup](windows/README.md). This is an experimental prerelease.

**Accidental-input warning:** ordinary handling can trigger commands while control is active. Turn it off outside deliberate use. VersionCode 41 added a posture-change neutral guard after an unintended volume reduction; versionCode 47 retains it and replaces gesture activation with an explicit button. Gesture reliability has not been benchmarked.

## Windows audio and gesture remote

The portable Windows Host captures the **current default multimedia output** with WASAPI loopback and streams PCM over pinned TLS on a local network. Android plays it through its media output. The Host installs no audio driver, service, autostart entry, certificate trust or firewall rule. A separate CatchWave Windows audio endpoint is not implemented. AudioRelay feature/quality parity has not been established; AudioRelay code, binaries and private protocol are not used.

| Gesture | Action |
| --- | --- |
| Tilt left/right and hold | Android media volume down/up; first step after 250 ms, then every 400 ms |
| Return toward neutral | Stop held volume adjustment |
| Shake, or 3 taps | Windows play/pause |
| 4 taps | Windows next |
| 5 taps | Windows previous |
| 6 taps | Android media mute/restore |

Tilt entry is about 24 degrees, release about 15 degrees. Fresh stable sensor samples are required; missed samples do not queue delayed volume steps. Native Android steps determine the increment. Windows keys depend on the active player's supported actions. A command ACK confirms dispatch, not a successful player transition.

1. Build Android and Windows below. Install the APK yourself and launch CatchWave.
2. On a trusted shared LAN, start `CatchWave.Host.exe --listen <PC-LAN-IPv4> --port 49372`.
3. Open **«Звук с ПК»** on the phone, scan the locally displayed Host QR, confirm pairing and press **«Подключиться»**. Grant Camera for scanning and Notifications if requested. Keep the QR private: it contains a session token and certificate pin. Restarting the Host requires a new scan.
4. Allow control explicitly in the Host and on the phone, enable **«Разрешить жесты»**, then press **«Активировать управление»**. No prior pose or turn is needed. Two short vibrations and an optional rising tone indicate activation. Press **«Выключить управление»** to turn it off; the optional descending tone follows deactivation. There is no mode timer and phone position does not activate or deactivate it. Permission, sensor and session faults still turn control off.
5. Hold the phone screen toward you, top edge up, still for 1.5 seconds to set neutral. Posture/display changes require fresh neutral without closing the mode. **«Сбросить активацию и нейтраль»** turns control off and requires the button again. Tilt slowly about 30 degrees and hold. Tap gently 0.2–0.5 seconds apart, then pause at least 0.7 seconds after a series.
6. Gestures normally require CatchWave to be visible. The optional **«Разрешить жесты при выключенном экране»** setting uses the running foreground service and a partial wake lock. Screen-off behavior and battery cost remain experimental. Use **«Отключиться»** to stop the Android session; close the Host to end its pairing session.

Start with comfortable phone media volume. Audio focus may pause another receiver such as AudioRelay. The owner accepts native installation, permission and firewall dialogs. Normal LAN use needs no ADB, root or access to the Windows player's notifications.

The phone and Host offer local diagnostic view/export: at most 256 events, timestamps/timezones, nonsecret session correlation, source/output/focus/route, frame counters and command/volume results. No audio, raw sensor vectors, QR, credentials, device names or track titles are recorded. See [diagnostics](docs/DIAGNOSTICS.md).

See [LAN setup](docs/LAN-SETUP.md), [Windows instructions](windows/README.md) and [validation](docs/VALIDATION-ARMING-FEEDBACK.md).

## Music recognition and handoff

The original mode records a short microphone fragment, computes a fingerprint locally, sends it to Shazam recognition over HTTPS, and asks native YouTube Music to continue at the estimated position. The Shazam app is not required. Matching metadata does not guarantee the same remix/master/recording. Live Sync requires headphones and access to the YouTube Music media session. Microphone recognition and LAN receiving are separate modes.

Recognition requires Microphone, internet and YouTube Music. Notification access is used for its media session; message notifications are not read. Optional acoustic refinement asks Android for screen/audio capture consent. Audio buffers remain in RAM. Fingerprints go to Shazam; title/artist searches go to YouTube. Those services see the network address. There is no CatchWave account, backend, advertising ID or analytics. LAN pairing preferences, finite failure/stage values and a bounded local diagnostic journal are stored privately on the phone. Windows saves its permission boolean and bounded metadata journal. See [known limitations](KNOWN_ISSUES.md).

## Compatibility

| Platform or feature | Declared baseline | Physical validation |
| --- | --- | --- |
| Android | Android 11/API 30 minimum; target SDK 35, compile SDK 36; Java APK with no bundled native ABI libraries | OnePlus 12, Android 16/API 36 |
| Windows Host | Windows desktop with .NET 9 Windows Desktop runtime; WinForms, WASAPI and user32 are required | Windows 11 x64, build 26200 |
| Windows ARM64/x86 | No RID/architecture is pinned in the project; needs a matching runtime/apphost build | Not validated |
| Linux/macOS | Can build Android sources with the SDK; no Host implementation | No audio/remote support implemented |
| iOS | No client implementation | Not supported |

These SDK/OS baselines do not certify other phone models or future Android releases. API 33 notification/typed parcel access, API 34 capture consent and API 31 privacy-sensitive capture calls are guarded; release lint checks the API 30 minimum. Windows support also depends on the runtime's current [official OS matrix](https://github.com/dotnet/core/blob/main/release-notes/9.0/supported-os.md); do not infer support for every Windows 10 edition or Server from the framework's capabilities. The accepted Windows build is x64; ARM64 needs separate testing.

Audio receiving does not require gesture sensors. Gestures require both an accelerometer and gyroscope; absence or failed registration of either disables all gestures. Tilt prefers game rotation vector and falls back to ordinary rotation vector. Without either orientation vector, all commands stay locked because intentional arming cannot be established. Audio remains independent. A camera is optional for APK installation but required by the current QR-only pairing flow. The manifest requires a microphone because the original recognition mode remains included.

Only the phone's built-in speaker route was used in the audio pilot. Android can route media to headphones/Bluetooth, but latency, fixed-volume behavior and route changes are not certified. OEM battery restrictions can stop background playback/sensors despite a foreground service and optional wake lock. Screen-off mode and sensor thresholds need device-specific checks; no battery whitelist or settings bypass is performed.

## Build and verify

Android requires JDK 17, SDK Platform 36 and Build Tools 36.0.0. Minimum Android: 11/API 30; compile SDK 36, target SDK 35. Gradle 8.11.1 is pinned by the wrapper; AGP 8.9.1. Set `ANDROID_HOME` or create untracked `local.properties` with `sdk.dir=...`.

```powershell
.\gradlew.bat --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
# Windows helper: validates and copies the APK to ignored dist/.
.\build.ps1 -Validate
```

Linux/macOS: use `./gradlew` with the same tasks. Default output: `app/build/outputs/apk/release/app-release.apk`. The experimental APK uses the developer's local Android debug signing key; keys are never committed. Different signers prevent in-place updates. Building with another key cannot update the distributed APK in place. Older Releases contain older versions.

Windows needs Windows 10/11 and .NET 9 SDK/Windows Desktop runtime:

```powershell
.\windows\build.ps1 -Validate
.\windows\test-interop.ps1
```

Test modes use bounded loopback TLS, synthetic silence and fake media keys. They do not capture real audio, control a phone or display a live pairing QR. GitHub Actions runs these Android, Host and Java/.NET checks.

## License and contributions

**GPL-3.0-or-later**. Preserve [LICENSE.txt](LICENSE.txt), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [SOURCE_PROVENANCE.txt](SOURCE_PROVENANCE.txt) and `LICENSES/`. Fingerprinting adapts SongRec via Audile. QR dependencies are ZXing Android Embedded/Core and QRCoder under their respective licenses; pinned public source URLs/hashes are in `third-party-source/provenance.json`.

See [CONTRIBUTING.md](CONTRIBUTING.md). Never publish private QR/pairing data, signing keys, tokens, private audio, full logcat or device identifiers. CatchWave is not affiliated with Google, YouTube, Apple, Shazam, SongRec, Audile or AudioRelay.
