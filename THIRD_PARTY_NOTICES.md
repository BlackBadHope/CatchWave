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
The app has no runtime library dependencies beyond the Android platform.
