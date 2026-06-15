# Release Guide

This project publishes installable APK files through GitHub Releases.

## Local Signed Release

Create a local keystore outside the repository:

```powershell
keytool -genkeypair -v -keystore release/network-panel-release.jks -alias network_panel -keyalg RSA -keysize 2048 -validity 10000
```

Create `release/keystore.properties` locally:

```properties
storeFile=network-panel-release.jks
storePassword=your-store-password
keyAlias=network_panel
keyPassword=your-key-password
```

Then build:

```powershell
.\gradlew.bat assembleRelease
```

The APK will be under:

```text
app/build/outputs/apk/release/
```

Do not commit keystores, passwords, or APK files.

## GitHub Release

Add these repository secrets before publishing releases:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Create `RELEASE_KEYSTORE_BASE64` from the local keystore:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release/network-panel-release.jks"))
```

Then push a version tag:

```powershell
git tag v1.0.11
git push origin main
git push origin v1.0.11
```

GitHub Actions will build a signed release APK and attach it to the GitHub Release. Visitors can download the APK directly from the Releases page.
