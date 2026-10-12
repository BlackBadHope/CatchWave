# Ordinary USB cable (0.1.39 development candidate)

CatchWave uses Android Open Accessory (AOA) bulk data, with the same pinned TLS/audio/control protocol as Wi-Fi. Product transport does not invoke ADB, require developer options, or require USB tethering. ADB is used separately during development to install and inspect the application.

## Setup

1. Install the matching Android APK and Windows Host. Use a data-capable cable and a phone with `android.hardware.usb.accessory` support.
2. On Windows, click **Подключить по USB** and explicitly confirm installation of the USB component when offered. Host downloads the exact pinned, independently signature-verified UsbDk 1.0.22 x64 package, checks its digest, and invokes the normal UAC installer. Alternatively, explicitly run `Install-UsbComponent.ps1`, which also verifies Authenticode locally. UsbDk is a USB redirector; it is not a virtual audio-output driver. Existing shared UsbDk installations are preserved. A PC restart can be required. No test signing or phone security changes are used.
3. Click **Подключить по USB** in Host. Select the phone when multiple supported devices are attached. AOA control uses the selected registered WinUSB interface when available; UsbDk remains the enumeration/negotiation fallback. Audio data uses WinUSB accessory interface zero, never the adjacent ADB interface. If required, accept the ordinary Windows administrator prompt to associate Microsoft's signed inbox winusb.inf with this accessory interface. No custom kernel binary, trusted certificate, test signing or phone security change is installed. Existing non-WinUSB accessory drivers are preserved.
4. On Android, select CatchWave in the USB accessory prompt, allow USB access, and click **Подключить звук по USB**. The app also has a **Подключить по USB** button in its audio screen.
5. For automatic USB → Wi-Fi, first save the QR of the same running Host. Both network address/port and pinned identity must match. Removing the cable starts one Wi-Fi connection without scanning again. Explicit **Отключиться** cancels setup, both routes, and queued callbacks. A new Host identity never inherits control permission silently.

Wi-Fi stays available while USB is active. TLS and PCM sessions restart during handover, so a short audible pause is possible; seamless/gapless playback is not claimed. Gesture activation remains button-only and requires fresh sensor calibration after a new transport session.

## Verification boundary

The development phone successfully entered AOA through WinUSB controls. UsbDk's first data write then returned USBD_STATUS_INVALID_PARAMETER, including with an independent page-aligned buffer, so it is no longer the audio data backend. Microsoft's inbox WinUSB binding was installed on the selected accessory interface. An AOA switch or open handle is not audible playback acceptance. Physical audio on this new backend, unplug-to-Wi-Fi timing, permission denial, screen-off USB and a phone with debugging disabled still require acceptance. Android 0.1.37 QR auto-connect and bounded disconnect checks are retained; this Host change does not rebuild Android. This remains a development candidate.

Binding snapshots and installer diagnostics live in %ProgramData%\CatchWave\USB. To restore the selected accessory's recorded prior binding, explicitly run the same Host as administrator with --usb-setup restore <accessory-instance-id> <phone-instance>. The helper validates the accessory identity and phone ancestry, restores an originally unconfigured interface using the null driver, and removes only CatchWave's added interface GUID. It never removes the shared Microsoft WinUSB package, modifies an ADB interface, or restores another device. Keep the snapshot when updating Host.

Primary protocol documentation: [Android AOA](https://source.android.com/docs/core/interaction/accessories/aoa), [Android accessory API](https://developer.android.com/develop/connectivity/usb/accessory). Dependency: [UsbDk](https://github.com/daynix/UsbDk), Apache-2.0. No third-party binary is committed to this repository.

An owner-invoked uninstall of a component installed solely for CatchWave is `msiexec /x {6D4A6ED0-CF41-4615-A4B3-BDA018C3C1CD} /passive /norestart`. Do not uninstall a shared component. Removal may also require a restart of USB devices/PC.
