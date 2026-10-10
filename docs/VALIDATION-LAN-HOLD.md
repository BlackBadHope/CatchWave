# Validation: Android 25 and matching Windows Host

This historical report describes the accepted Android 0.1.20-hold-volume/versionCode 25 runtime sources and the corresponding Windows Host. That publication excluded the owner's signing key, private evidence bundle, live QR and device logs. For current version-29 checks and open physical validation, see [arming feedback validation](VALIDATION-ARMING-FEEDBACK.md).

The accepted local build passed **240 Android unit/Robolectric tests**, **0 failures**, and release lint **0 errors/18 warnings**. Coverage includes real decoder fixtures, output-startup receiving, freshness/timeline, policy generations, tilt axes/postures, taps/shake, held timing/hysteresis/direction reversal, neutral/permission/freshness stops, volume limits, stale UI callbacks and mute checkpoint preservation. Service volume tests use a fake device; they do not measure hardware gesture reliability.

Windows checks use bounded `--self-test` and Java/.NET `--interop-test`, synthetic silence, generated loopback identities and fake keys. The committed QR is deterministic public test data at 192.0.2.1. No live device, real audio capture, real keys or live pairing identity is used. The GitHub Actions workflow repeats these supported build/test commands on the published commit.

A OnePlus 12/Android 16 passed a short owner-operated sound/gesture pilot, including held volume after installing 25. This is qualitative acceptance on one setup, not statistical reliability or latency/battery benchmarking. An independent review checks the publication diff; automated and manual evidence remain distinct.

Open:long runs/reconnects across networks/devices, controlled AudioRelay A/B measurements, background/OEM battery behavior, Bluetooth routing, independent Windows endpoint and production binary signing/distribution.
