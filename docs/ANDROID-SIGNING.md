# Android release signing

Starting with **0.1.47-beta / versionCode 72**, distribution uses a dedicated RSA3072 release key. Gradle produces an unsigned release APK and never falls back to the Android debug key. Supported Android versions are API30+; distribution uses APK Signature Scheme v3 for that whole range.

Public certificate SHA256:
`1bab52430a1ec3716a3f75235ae6dae748dd0a0d0d6ebc2d450805ddfeb4a326`

The public `signing/certificate-lineage.bin` links the old pilot signer
`548aa4666356dbc11e78db1561d5a1980dc92b9cadb872ed6053537376e2d498`
to the release signer. Installed-data trust is retained; rollback authority for the old key is disabled. The code70 → code72 update was installed on the pilot without uninstalling: UID and first-install date were preserved, and the pulled installed APK matched the signed release artifact.

## Maintainer build

Build/test/lint normally, then sign the unsigned APK separately:

```powershell
.\tools\Sign-Release.ps1 -InputApk .\app\build\outputs\apk\release\app-release-unsigned.apk -OutputApk .\dist\CatchWave-Android.apk
```

The Windows helper requires the maintainer's private signing profile: a PKCS12 key, a CurrentUser-DPAPI encrypted password and the public lineage. It takes secrets through a temporary process environment value and clears it afterwards. These files and protected backups stay outside the repository and public packages. Only public certificates/lineage are committed. Another maintainer's unrelated key cannot update the published application.

Keep the release key for subsequent updates. Losing it would break this update channel. A private protected backup has been prepared; its contents are not distributed.

## Recovery

The pilot has a local rollback APK carrying the unchanged 0.1.45 DEX payload, versionCode73 and the **new** release signer/lineage. Its signature, manifest and DEX equality were checked before the update. This permits a code rollback without deleting data or restoring authority to the old key; the rollback has not been installed for acceptance. After installing code73, a later update must use a higher versionCode. Public beta packages contain no rollback APK or private keys.
