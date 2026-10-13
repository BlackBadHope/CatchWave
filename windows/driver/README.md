# CatchWave Output — driver development

Status: **x64 WDK build, INF/API validation and Inf2Cat signability checks passed on 2026-10-13; kernel loading, Microsoft signing and physical playback are pending**. The portable Host does not create a Windows playback endpoint yet. The generated SYS/INF/catalog are unsigned development artifacts, never installed automatically. The owner currently has no Hardware Dev Center account or EV certificate; this blocks ordinary public driver deployment.

The profile creates a distinct `ROOT\CatchWaveAudio` device/service with one `CatchWave Output` render endpoint. It derives from the MIT-licensed [Microsoft SysVAD sample](https://github.com/microsoft/Windows-driver-samples/tree/2dc3fd3a0cc84a2933f2194e7ec0871584979071/audio/sysvad); it does not reuse AudioRelay. Capture endpoints and Bluetooth/USB headset sideband features are disabled. The raw-render file logging registry override is removed; recording stays disabled in code. Host capture uses Windows shared WASAPI loopback.

1. Obtain the pinned Microsoft tree (`2dc3fd3a0cc84a2933f2194e7ec0871584979071`), including `audio/sysvad` and the upstream `LICENSE`.
2. Run `python prepare-source.py --upstream <tree> --output <fresh-directory>`. The original tree is preserved; a file hash manifest is created.
3. With VS 2022 and WDK installed, run `./build.ps1 -Source <fresh-directory>`. Alternatively use `-PortableKit <kit-directory>` with the pinned WDK/SDK 10.0.26100.6584 packages, Microsoft WDK VS build-tool payload and MSVC 14.44.35207 x64 Spectre libraries described in `toolchain-lock.json`. This uses a private copy of the MSBuild integration; installed Visual Studio is preserved. The x64 MSBuild executable is required by the x64 INF verifier. Build failure stops the pipeline. No driver is installed, no signing bypass is enabled.
4. Validate the generated INF/catalog, device start/stop/uninstall, sleep/reconnect, Windows volume, shared loopback PCM and real phone playback on a suitable driver test target. A build or test tone alone does not establish audible forwarding.
5. Obtain a [Microsoft-signed driver package](https://learn.microsoft.com/en-us/windows-hardware/drivers/dashboard/code-signing-reqs) before normal Windows deployment. Installation must create only the CatchWave root device, verify the bound package and preserve an uninstall/previous-package rollback. That installer and endpoint selection in Host are still to be completed.

The prepared source also guards the pinned sample's eight unguarded sideband branches and removes its zero-endpoint capture loop. Spectre, buffer security, CFG, kernel API and INF validation remain enabled. Raw-render file recording remains disabled.

Do not use an AudioRelay hardware ID, change the phone's security settings, disable Secure Boot/signature enforcement, or promote this unsigned package to a working output device.
