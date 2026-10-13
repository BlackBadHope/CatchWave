# LAN audio and gestures

Use the beta's Windows Host 0.1.46 and Android 0.1.45/code70. In Host, choose **«Источник звука»** before connecting, then direct the desired Windows player to that same output in Windows Sound settings. The selected existing output is captured through shared WASAPI loopback for both Wi-Fi and native USB. Selection persists locally; unavailable devices are not silently replaced. Disconnect the phone before changing source, then reconnect to the same Host without restarting it or scanning another QR. Default mode resolves the Windows multimedia output at each new session. Host creates no new sound card or default-route change. An existing active digital/virtual output can work without physical speakers; a Windows UNPLUGGED/DISABLED/NOTPRESENT endpoint cannot be opened. Windows master volume may not affect captured audio, so tilt adjusts Android media volume locally.

Pair by scanning the local Host QR and confirming on the phone. This pins the TLS certificate and supplies a random authentication token. Restarting the Host changes both. Camera supports scanning; Notifications support the playback foreground service. Native installation, permission and network/firewall decisions belong to the owner. No USB, ADB, root or player notification permission is needed for normal LAN operation.

Approve the phone's control-key fingerprint in Host after comparing it with phone Diagnostics, then allow control on the phone. A QR grants audio only. Effective commands need the approved signing key, a fresh receiver and current permission generation. Saved preferences never activate a disconnected session. A Host restart needs fresh QR pairing or a successful new USB session. The UI separates TCP, TLS, pairing, format and output/focus errors without displaying secrets.

**Keep control off outside deliberate use/testing.** Ordinary handling can trigger commands while active. Activation is button-only; posture guards affect held tilt while pickup preserves tap readiness. Reliability needs physical testing.

## Gestures

All commands, including local mute, start locked. With both control permissions, fresh audio and accel/gyro/orientation available, press **«Активировать управление»**. No table reference or activation gesture is required. Two short vibrations and an optional rising tone confirm activation. There is no timer. Press **«Выключить управление»** to turn it off; an optional descending tone confirms this change.

Phone position never activates or deactivates the mode. Pickup and screen rotation preserve activation and taps. Large changes briefly pause only held tilt and rebase its axis after about 0.3 seconds of quiet. Nearly flat placement blocks repeated volume. Sensor/session/permission/visibility faults and the explicit reset discard pending histories; recovered permissions never activate the mode automatically.

After button activation, hold still once for 1.5 seconds to set neutral. Hold the phone toward you, top edge up; tilt slowly about 30 degrees and hold. First volume step: 250 ms; repeats at most every 400 ms. Return within about 15 degrees to stop; entry is about 24 degrees with hysteresis. Posture rebasing affects only tilt. A sample gap stops the previous hold; delayed samples never create catch-up bursts. At volume limits/fixed volume, no pointless adjustments are issued. Native step size varies by device.

Shake or 3 taps:play/pause;4:next;5:previous;6:local mute/restore. Tap gently 0.2–0.5s apart, then wait 0.7 s after the series. More than 6 taps do not trigger a shorter prefix. Rotation/shake can invalidate a series. An ACK confirms Windows key dispatch; players can ignore it or lack next/previous support.

Gestures normally need CatchWave visible. Optional screen-off gestures retain sensors in the running foreground service and use a partial wake lock. Battery cost and OEM power management remain experimental. No boot/always-on daemon is installed. Disconnect explicitly to release resources; close the Host to end its Windows session. Bluetooth routes and long screen-off runs need separate measurement.

Diagnostics and export are in the folded **«Диагностика»** section on the phone and in the Host window. See [diagnostic fields and limits](DIAGNOSTICS.md). Control permissions and the mode button are separate; saved permissions alone do not activate commands.

## Sound and limits

Uncompressed PCM16 travels over authenticated pinned TLS with bounded buffers/stale-frame dropping. Supported Windows mix formats: mono/stereo PCM at 44100/48000/96000 Hz. No codec, resampling or internet relay is provided. Native USB is described in [USB-SETUP.md](USB-SETUP.md). Successful USB audio can learn this Host's Wi-Fi route; cable loss and transient network failures get bounded recovery, cancelled by manual disconnect. Phone media has priority; the connection is retained while PC output is paused and old PCM is dropped. Ordinary notification MAY_DUCK attenuates PC audio instead. Begin at comfortable phone media volume and check actual sound yourself.

Single-device manual acceptance does not establish AudioRelay parity, latency/failure-rate numbers, durable screen-off behavior or all-OEM support. See [validation](VALIDATION-LAN-HOLD.md) and [known issues](../KNOWN_ISSUES.md).
