# LAN audio and gestures

Build the matching Android/Windows tree using the READMEs. The Host streams the current default Windows multimedia endpoint. It creates no CatchWave sound card or default-route change. Loopback may observe an existing AudioRelay virtual output; explicit endpoint selection is not supported. Windows master volume may not affect captured audio, so tilt adjusts Android media volume locally.

Pair by scanning the local Host QR and confirming on the phone. This pins the TLS certificate and supplies a random authentication token. Restarting the Host changes both. Camera supports scanning; Notifications support the playback foreground service. Native installation, permission and network/firewall decisions belong to the owner. No USB, ADB, root or player notification permission is needed for normal LAN operation.

Enable management in the Host, then on the phone. Effective control needs a connected fresh receiver and matching current permission generation. Stale connections cannot authorize new Windows commands or held volume steps. Saved permission preferences do not activate a disconnected session. Retry a failed session by disconnecting/reconnecting; a Host restart needs a new QR. The UI distinguishes TCP, TLS, pairing, format and output/focus errors without displaying secrets.

**Keep control off outside deliberate use/testing.** Ordinary handling can trigger commands while it is active. The posture-change neutral guard from versionCode 41 is retained; versionCode 47 uses button-only activation. Reliability still needs physical testing.

## Gestures

All commands, including local mute, start locked. With both control permissions, fresh audio and accel/gyro/orientation available, press **«Активировать управление»**. No table reference or activation gesture is required. Two short vibrations and an optional rising tone confirm activation. There is no timer. Press **«Выключить управление»** to turn it off; an optional descending tone confirms this change.

Phone position never activates or deactivates the mode. Leaving/entering a flat posture and other large posture changes require fresh neutral and invalidate pending commands while preserving activation. Sensor gaps over 150 ms, stale/invalid data, permission or visibility/session loss, and the explicit reset button turn control off and discard pending histories. Recovered sensors or permissions never reactivate it automatically.

After activation or a posture transition, hold still for 1.5 seconds to set neutral; reset neutral when changing posture. Tilt slowly about 30 degrees and hold. First local volume step: 250 ms; repeats:at most every 400 ms. Return within about 15 degrees to stop. Entry threshold is about 24 degrees, with hysteresis. Direction reversal needs a new stable dwell. A sample gap over 150 ms stops the previous hold; delayed samples never create catch-up bursts. At volume limits/fixed volume, no pointless native adjustments are issued. Native step size varies by device.

Shake or 3 taps:play/pause;4:next;5:previous;6:local mute/restore. Tap gently 0.2–0.5s apart, then wait 0.7 s after the series. More than 6 taps do not trigger a shorter prefix. Rotation/shake can invalidate a series. An ACK confirms Windows key dispatch; players can ignore it or lack next/previous support.

Gestures normally need CatchWave visible. Optional screen-off gestures retain sensors in the running foreground service and use a partial wake lock. Battery cost and OEM power management remain experimental. No boot/always-on daemon is installed. Disconnect explicitly to release resources; close the Host to end its Windows session. Bluetooth routes and long screen-off runs need separate measurement.

Diagnostics and export are in the folded **«Диагностика»** section on the phone and in the Host window. See [diagnostic fields and limits](DIAGNOSTICS.md). Control permissions and the mode button are separate; saved permissions alone do not activate commands.

## Sound and limits

Uncompressed PCM16 travels over authenticated pinned TLS, with bounded buffers/stale-frame dropping. Supported default Windows mix formats:mono/stereo PCM at 44100/48000/96000 Hz. No codec, resampling, endpoint selector, USB transport or internet relay is provided. Audio focus can pause another receiver. Begin at comfortable phone media volume and check sound yourself.

Single-device manual acceptance does not establish AudioRelay parity, latency/failure-rate numbers, durable screen-off behavior or all-OEM support. See [validation](VALIDATION-LAN-HOLD.md) and [known issues](../KNOWN_ISSUES.md).
