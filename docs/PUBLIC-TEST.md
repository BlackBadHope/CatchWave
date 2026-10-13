# Public test candidate 0.1.45

This checkpoint is an **experimental candidate**, not a stable release or AudioRelay parity claim. Android is the only client; Windows x64 is the only accepted Host architecture. APK and Host must come from the matching candidate package. Exact revision, hashes and verification results belong to the release build manifest.

## Current scope

- Wi-Fi PC audio with local pinned pairing, one confirmation after QR scanning, explicit disconnect, bounded recovery and phone-media priority.
- Button-only gesture activation, independent permission switches, held Android volume, play/pause/next/previous and local mute.
- Native AOA/WinUSB cable audio: heard on the OnePlus 12 in 0.1.43, without ADB carrying audio or USB tethering. Android 0.1.44 fixes single-attempt handover, learns Wi-Fi pairing after successful USB output and preserves a selected VPN.
- Security changes at command authority, exported activity, JSON work bounds, network packet rates, UI callback queues and pre-authentication admission. Existing clients with unsigned commands get audio-only permission.

## Gates

| Gate | Current evidence / required acceptance |
| --- | --- |
| Android build/lint | 0.1.45: 383 tests passed, lint 0 errors / 20 warnings; exact APK hash is recorded in its build manifest |
| Windows and Java interoperability | Exact final 0.1.45 revision/hashes/checks belong to its build manifest; prior 0.1.44 checks remain scoped to that artifact |
| Installed Host / shortcut | 0.1.44 installed; all copied file hashes matched; launch through the actual desktop shortcut opened the window and bound port 49372 |
| Installed APK | 0.1.44/code68 installed on the pilot; signer preserved and APK hash matched the prepared artifact |
| Wi-Fi sound | Previously heard; repeat on the final APK/Host with actual PC media |
| Cable sound | Owner confirmed USB working in 0.1.44; final 0.1.45 auto-pick still needs a real cable reattachment |
| USB to Wi-Fi | Old candidate worked intermittently; new recovery/cancellation tests passed; three physical unplug cycles remain required |
| Explicit disconnect | Earlier phone acceptance and current retry cancellation test exist; check stopped service and no automatic restart on final build |
| Phone-media priority | Owner confirmed phone playback pauses PC and returns automatically in 0.1.44; repeat after 0.1.45 update |
| Gestures | Previous user accepted volume/3/4/5/6 taps and tones; final signed-command build needs current physical acceptance |
| Fresh ordinary USB setup | **BLOCKED / unaccepted:** generic UsbDk control fallback failed; pilot used an already working WinUSB control interface |
| CatchWave Output | **BLOCKED:** unsigned SYS/INF/catalog built and validated; Microsoft account/certificate, kernel start/playback, installer/uninstaller and Host endpoint integration remain |
| Broader quality | Other OEMs, pre-API36 USB stream paths, long screen-off/battery tests, Bluetooth and controlled AudioRelay A/B are not accepted |

The candidate may be shared for an explicitly limited pilot; it must not be described as fulfilling universal ordinary-USB installation or a working own Windows playback device. Never mark a gate passed from a TCP connect, PCM counter or synthetic test alone.

## Short tester session

1. Start the matching Host and install the APK normally. Preserve existing app data; do not uninstall to change signers.
2. Pair locally, play PC media and hear it on Android. Leave its screen off briefly; note any dropout and export local diagnostics.
3. Allow and activate gestures deliberately. Hold the phone toward you, calibrate once, test held tilt and taps. Set it flat face-up; volume should stay unchanged.
4. Test native USB using [USB-SETUP.md](USB-SETUP.md). With Wi-Fi already connected, unplug three times across separate cable sessions. Sound should return without manual Connect; report each failed cycle rather than averaging it away.
5. Press Disconnect during streaming and during recovery. It must stop and remain stopped. Restart only through an explicit owner action.
6. While connected, play phone media, then pause/close it. PC sound should return automatically; manual Disconnect must cancel this return.

## Data and rollback

No audio/raw sensor/QR/credential logging or automatic uploads. Recognition is a separate opt-in microphone workflow with third-party HTTPS services. Share only bounded Diagnostics exports. PC permission approval compares the phone's public control-key fingerprint; private keys are never exported.

The pilot preserves the installed signer and first-install date. A local rollback APK restores the exact 0.1.43 DEX payload with an increased manifest versionCode, permitting an in-place update without deleting data. It is a local recovery artifact, not a new public source release. After using that rollback, a future update needs a higher versionCode.

## 0.1.45 notification and USB actions

Standard notification MAY_DUCK focus uses Android automatic attenuation rather than pausing PCM. An explicit duck callback uses 20% of the app track gain, preserving queued audio and owner mute/volume; full phone-media focus loss still pauses/discards PC frames and returns after quiet. A new connection does not seize initial focus from already playing phone media. No notification-listener access is required by this change.

Host auto-pick is limited to a previously authenticated physical route/device configuration. New drivers and unknown/multiple phones keep explicit setup. The phone can continue an active audio session automatically on accessory entry; a cold or explicitly disconnected session requires one private USB action. These new scenarios require hearing and cable acceptance, not just unit-test counters.
