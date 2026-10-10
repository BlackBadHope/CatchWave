# CatchWave Windows Host

Portable .NET 9 Windows desktop Host **0.1.21-armed-diagnostics**, matching Android **0.1.21-armed-diagnostics/versionCode 27** source in this tree. It captures the default multimedia output with shared WASAPI loopback. Supported input: mono/stereo PCM at 44100/48000/96000 Hz, 16/24/32-bit integer or 32-bit float. Network output is PCM16 in 20 ms frames. No resampling, endpoint selector, separate audio driver or automatic route switch is implemented.

## Build

Install .NET 9 SDK and Windows Desktop runtime on a Windows desktop version covered by its [supported OS matrix](https://github.com/dotnet/core/blob/main/release-notes/9.0/supported-os.md). The manual pilot used Windows 11 x64/build 26200; other versions/architectures are not certified. The project does not pin a RID. An x64-built apphost needs x64 support; native ARM64 use requires a matching runtime/build and separate verification. From the repository root:

```powershell
.\windows\build.ps1 -Validate
.\windows\test-interop.ps1
```

Output: `%TEMP%/CatchWave-public25-host-build/bin/`. Keep all produced files together for portable use. QRCoder 1.6.0 resolves through NuGet; preserve its license when distributing binaries. Interop also needs JDK 17 on PATH or `JAVA_HOME`.

## Run and pair

```powershell
CatchWave.Host.exe --listen <PC-LAN-IPv4> --port 49372
```

Use an explicit local IPv4 address on a trusted LAN. No automatic firewall changes occur. If Windows asks for access, the owner chooses the intended private network. Scan the local QR in CatchWave and confirm. QR/CW1 contains a private token and pin: do not post, screenshot, log or pass it through ADB. The certificate is generated for the Host process and disposed on exit; no certificate trust entry is installed. Restarting requires fresh pairing.

PC control starts OFF. Its owner-approved boolean is stored in `%LOCALAPPDATA%/CatchWave/host-control21.json`; no pairing token is stored there. Enable control on both sides. Revocation and key dispatch share a lock; policy generations reject older permission states. Dashboard phone output/sensor reports are authenticated counters, not measurements of audible sound.

`--synthetic --disable-media-keys` sends zero PCM with no WASAPI capture and force-disabled Windows keys. It still creates an Android audio receiver and may take audio focus. `--enable-media-keys` is an explicit session override; normal use should use owner approval controls. No service, autostart, discovery, internet relay or firewall installer is included. Closing the Host window stops its listener and ends the session.

## Test boundaries

`--self-test` uses an unshown form, loopback TLS, generated test identity, synthetic silence and fake media keys. `--self-test --qr-fixture <path>` exports a deterministic **public test QR** at documentation address 192.0.2.1 with byte-sequence values, not a live identity. Android decodes the committed fixture using its real decoder.

`--interop-test <java.exe> <classes>` uses the actual Java parser/timeline/freshness code and an 800 ms fake focus delay. Its generated identity goes to the Java child through stdin, never printed. Automated tests do not exercise physical Android AudioTrack, real WASAPI capture or player transitions.

One OnePlus 12 manual pilot accepted audio/gestures. Screen-off battery behavior, broader support, controlled AudioRelay A/B quality and an independent CatchWave endpoint remain open.

## Local diagnostics

The Host keeps the last 256 metadata events under local application data in `CatchWave/diagnostics.log`. View/export buttons are in the window. Time/zone, nonsecret session ID, capture packet/silence counts, output reports, transitions, route-change detection and media-key dispatch results are included. No PCM/audio, QR, tokens/pins, endpoint names/IDs or track titles are written. Disk failures cannot stop audio; the counter remains visible. See [diagnostics](../docs/DIAGNOSTICS.md). Android 27 reports actual armed/neutral readiness; permission can be turned off even while the phone is locked or disconnected.
