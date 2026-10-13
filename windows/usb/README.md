# Native USB cable — 0.1.44 experimental candidate

CatchWave uses Android Open Accessory (AOA) bulk data and the same pinned TLS/audio/control protocol as Wi-Fi. ADB and USB tethering do not carry product audio. Development ADB is separate from this transport.

Read [the current USB setup and limits](../../docs/USB-SETUP.md) before installing a component. OnePlus 12 cable audio was physically heard in 0.1.43. Final 0.1.44 cable sound, three unplug-to-Wi-Fi cycles and screen-off USB still need device acceptance.

**Fresh ordinary-device setup remains unaccepted.** The generic UsbDk AOA control fallback failed on the pilot. Its successful initial AOA transition used an already working WinUSB control interface. Installing UsbDk alone is not proof that a new user's MTP phone can enter accessory mode; do not advertise universal cable setup yet.

When explicitly requested, Host or `Install-UsbComponent.ps1` downloads the pinned, digest-checked and Authenticode-verified UsbDk 1.0.22 x64 package and invokes normal UAC. Existing shared installations are preserved. This USB redirector is independent of the unsigned CatchWave Output audio driver. A restart may be required; no automatic restart, test signing or phone security changes are performed. No UsbDk binary is bundled.

For an actual selected AOA device, Host can bind Microsoft's inbox WinUSB to that device's data interface only. Existing ADB and unrelated phone interfaces are excluded. Binding snapshots live in `%ProgramData%/CatchWave/USB`; preserve them across updates. The explicit administrative restore command is `CatchWave.Host.exe --usb-setup restore <accessory-instance-id> <phone-instance>`. It validates accessory identity and phone ancestry and restores only the recorded selected interface. It does not remove the shared Microsoft driver package.

An owner-invoked uninstall of a UsbDk component installed solely for CatchWave is `msiexec /x {6D4A6ED0-CF41-4615-A4B3-BDA018C3C1CD} /passive /norestart`. Preserve a shared component; uninstall may require a restart.

Primary references: [Android AOA](https://source.android.com/docs/core/interaction/accessories/aoa), [Android accessory API](https://developer.android.com/develop/connectivity/usb/accessory), [UsbDk](https://github.com/daynix/UsbDk) (Apache-2.0).
