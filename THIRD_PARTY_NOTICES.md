# Third-party sources

CatchWave is distributed under GPL-3.0-or-later, with complete corresponding source alongside the APK.

Fingerprint.java and Hanning.java adapt the SongRec fingerprinting algorithm by Marin M. and contributors, via the Audile project by Aleksey Saenko and contributors. The adaptation replaces Rust FFT, binary serialization, JNI and Android integration with Java; it retains the original algorithm and window coefficients.

- https://github.com/marin-m/SongRec (Audile identifies upstream revision 5afbf7361fcd72a3edaaeed24dc0ea150fd79385, tag 0.7.3)
- https://github.com/AudileTeam/Audile (the exact checked-out commit is recorded in SOURCE_PROVENANCE.txt)
- Audile files: core/recognition/native/songrecfp/src/fingerprinting/{algorithm.rs,signature_format.rs,hanning.rs}
- Network request/response format was informed by Audile's Shazam recognition implementation.
- No Audile application branding, private credentials, telemetry or native binaries are used.

The Gradle wrapper is from Audile's Gradle distribution setup. Its embedded license is copied verbatim to LICENSES/Gradle-wrapper.txt. Gradle is Apache-2.0 licensed: https://github.com/gradle/gradle/blob/v8.11.1/LICENSE
JUnit, Robolectric and org.json are test-only dependencies; none is bundled in the production APK.
The LAN receiver uses the QR runtime libraries below.

LAN audio/gestures: the receiver, gesture engine, TLS protocol and Windows host are original GPL-3.0-or-later code using public Android/Windows APIs. No AudioRelay code or extracted protocol is incorporated. Audio Share (mkckr0/audio-share), Apache-2.0, revision 342751fe675367483170b002ec6054e243966dc0, was evaluated as a conceptual reference; no source was copied/adapted and no dependency is bundled. See docs/LAN-SETUP.md.

QR runtime dependencies:
- Android scanner: JourneyApps ZXing Android Embedded 4.3.0, Apache-2.0, https://github.com/journeyapps/zxing-android-embedded/tree/v4.3.0. License: LICENSES/ZXing-Android-Embedded-4.3.0.txt.
- Android QR decoder: ZXing Core 3.5.3, Apache-2.0, https://github.com/zxing/zxing/tree/zxing-3.5.3. License and notice: LICENSES/ZXing-Core-3.5.3.txt and LICENSES/ZXing-Core-3.5.3-NOTICE.txt.
- Windows QR encoder: QRCoder 1.6.0, MIT, https://github.com/Shane32/QRCoder/tree/v1.6.0. License: LICENSES/QRCoder-1.6.0.txt.
- QRCoder resolves Microsoft System.Drawing.Common 6.0.0 and Microsoft.Win32.SystemEvents 6.0.0 (MIT). The Windows x64 self-contained candidate bundles .NET / WindowsDesktop 9.0.17. Preserve the runtime package's MIT licenses and third-party notices: LICENSES/DotNet-Runtime-9.0.17.txt, LICENSES/DotNet-Runtime-9.0.17-NOTICES.txt and LICENSES/DotNet-WindowsDesktop-9.0.17.txt. Runtime source: https://github.com/dotnet/runtime and https://github.com/dotnet/winforms; exact framework versions are in CatchWave.Host.runtimeconfig.json.
- Android runtime coordinates are pinned in app/build.gradle; QRCoder is pinned in windows/CatchWave.Host/CatchWave.Host.csproj. No AndroidX library is pulled into this release graph.

QR rendering and decoding happen locally. No AudioRelay source, binary, private protocol or extracted credentials are used. LICENSES/qr-license-sources.json records public pinned-version license URLs and content hashes.
The repository records exact upstream scanner/encoder source archive URLs and SHA256 values in third-party-source/provenance.json. Fetch those unmodified public archives when assembling a binary source delivery; they are not vendored in Git. Preserve corresponding source and dependency licenses when distributing binaries.

CatchWave Output development uses the MIT-licensed Microsoft SysVAD sample pinned at 2dc3fd3a0cc84a2933f2194e7ec0871584979071. Its generated source delivery preserves LICENSE-Microsoft. The unsigned driver is a separate development artifact, never part of an automatic Host installation. No UsbDk binary is bundled; any optional external installer is separately identified and hash-pinned in the USB setup tooling.
