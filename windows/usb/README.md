# Ordinary USB cable (0.1.36 development candidate)

CatchWave uses Android Open Accessory (AOA) bulk data, with the same pinned TLS/audio/control protocol as Wi-Fi. Product transport does not invoke ADB, require developer options, or require USB tethering. ADB is used separately during development to install and inspect the application.

## Setup

1. Install the matching Android APK and Windows Host. Use a data-capable cable and a phone with `android.hardware.usb.accessory` support.
2. On Windows, click **Подключить по USB** and explicitly confirm installation of the USB component when offered. Host downloads the exact pinned, independently signature-verified UsbDk 1.0.22 x64 package, checks its digest, and invokes the normal UAC installer. Alternatively, explicitly run `Install-UsbComponent.ps1`, which also verifies Authenticode locally. UsbDk is a USB redirector; it is not a virtual audio-output driver. Existing shared UsbDk installations are preserved. A PC restart can be required. No test signing or phone security changes are used.
3. Click **Подключить по USB** in Host. Select the phone when multiple supported devices are attached. CatchWave only redirects the selected device.
4. On Android, select CatchWave in the USB accessory prompt, allow USB access, and click **Подключить звук по USB**. The app also has a **Подключить по USB** button in its audio screen.
5. For automatic USB → Wi-Fi, first save the QR of the same running Host. Both network address/port and pinned identity must match. Removing the cable starts one Wi-Fi connection without scanning again. Explicit **Отключиться** cancels setup, both routes, and queued callbacks. A new Host identity never inherits control permission silently.

Wi-Fi stays available while USB is active. TLS and PCM sessions restart during handover, so a short audible pause is possible; seamless/gapless playback is not claimed. Gesture activation remains button-only and requires fresh sensor calibration after a new transport session.

## Verification boundary

Local Windows native layout/descriptor/cancellation checks, existing TLS protocol checks, Android transport generation/trust/bootstrap checks, Android full unit suite and lint passed for this candidate. **Physical AOA audio, unplug-to-Wi-Fi timing, reconnect, permission denial and screen-off USB remain unverified.** On the development PC, the signed component is installed, but the live root-hub stack does not yet include UsbDk and `\\.\UsbDk` returns Windows error 2. The Host reports `USB_DRIVER_RESTART_REQUIRED`. This is not a completed USB release.

Primary protocol documentation: [Android AOA](https://source.android.com/docs/core/interaction/accessories/aoa), [Android accessory API](https://developer.android.com/develop/connectivity/usb/accessory). Dependency: [UsbDk](https://github.com/daynix/UsbDk), Apache-2.0. No third-party binary is committed to this repository.

An owner-invoked uninstall of a component installed solely for CatchWave is `msiexec /x {6D4A6ED0-CF41-4615-A4B3-BDA018C3C1CD} /passive /norestart`. Do not uninstall a shared component. Removal may also require a restart of USB devices/PC.
