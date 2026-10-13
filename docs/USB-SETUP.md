# Native USB audio candidate

CatchWave's cable transport uses Android Open Accessory (AOA) and Windows WinUSB. Product audio does not use ADB, port forwarding or a USB modem. AOA changes the phone's USB function while connected; MTP file transfer can be unavailable until unplugging it. Developer ADB is used only to install/read diagnostic builds on the pilot phone.

## Current acceptance boundary

USB sound was heard on one OnePlus 12 with Android 16 and Host 0.1.42 / Android 0.1.43. The pilot already had a working WinUSB control interface for initial AOA negotiation. This does not certify an untouched PC/phone: the generic UsbDk control fallback returned INVALID_PARAMETER. Fresh ordinary MTP setup and restoring its original binding are still release gates. Do not use Zadig, disable driver enforcement or replace every phone interface as an undocumented workaround.

## Pilot connection

1. Install matching candidate APK and portable Host; keep all Host files together.
2. Connect a data-capable USB cable. Open CatchWave and select USB on the PC.
3. If Host lacks its pinned signed UsbDk component, choose its explicit component setup. Normal administrator consent may be requested; setup is not automatic at startup. A restart can be necessary.
4. Allow CatchWave access to the USB accessory on Android and press **«Подключить звук по USB»**. Host may request separate administrator consent to bind only the selected accessory audio interface to Microsoft's inbox WinUSB. The ADB sibling interface is excluded.
5. Check that phone Diagnostics says **Подключение: USB**, that Host reports USB authorization, and listen to actual PC media. Receiving counters alone do not confirm sound.

After successful authenticated USB output, 0.1.44 remembers the same Host's Wi-Fi address and certificate automatically. With Wi-Fi already connected, removing the cable starts that pinned LAN route without another QR scan. Transient failures have up to three retries within 20 seconds; **«Отключиться»** cancels all retries. Certificate and protocol errors do not silently re-pair. An active VPN is preserved. A short gap while TLS/AudioTrack reconnect is possible.

A Host started with no eligible LAN advertises loopback for USB-only operation; that route cannot provide Wi-Fi fallback. A Host restart changes its pairing identity: scan its current QR or establish another successful USB session.

## Failure evidence

Use local Diagnostics export on both sides. Safe events include USB bootstrap/TLS stages, fixed USB I/O error codes, USB_TO_WIFI, USB_WIFI_ROUTE_READY, WIFI_ROUTE_BOUND, WIFI_RETRY and RECOVERY_EXPIRED. Never share the private pairing QR, credential preferences, raw USB bytes or full logcat. A stalled initial setup is different from an authenticated cable losing data.

Use [public test gates](PUBLIC-TEST.md) for the latest candidate's physical status. The current source does not certify every Android OEM or an Android version below the API36 stream implementation.
