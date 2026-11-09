# AbbasStudio — Android

The AbbasStudio video studio (compress, trim, convert, GIF maker, audio tools,
watermark, aspect-ratio formatter) as an Android app.

**How it works:** the studio itself is the web app (React + Vite, v1.5) running
100% on-device inside a WebView, loaded from local assets
(`app/src/main/assets/www/`). No video ever leaves the phone. The native shell
only adds what a WebView cannot do on its own:

- a video file picker for the studio's file input,
- saving processed videos to **Downloads/AbbasStudio/** (WebView cannot download
  `blob:` URLs, so a small JS bridge streams the finished file to Android in
  512 KB chunks — see `MainActivity.kt`).

Internet is only used for the CDN parts of the studio (FFmpeg.wasm, fonts).

## Build

Android Studio, or from the command line:

```sh
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

On Windows: `gradlew.bat assembleDebug`

Requirements: JDK 17, Android SDK with platform 34 and build-tools 34.0.0.

> **Gradle wrapper jar:** `gradle/wrapper/gradle-wrapper.jar` is a binary file and
> is intentionally not committed. Before the first local build, generate it once
> with an installed Gradle (`gradle wrapper --gradle-version 8.10.2`), or fetch
> it from the official Gradle repo:
> `curl -o gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v8.10.2/gradle/wrapper/gradle-wrapper.jar`
> (GitHub Actions does this automatically).

> **Launcher icon:** the adaptive icon lives in `mipmap-anydpi-v26` (Android 8+).
> On Android 7 devices the launcher may show a generic icon.

## GitHub Actions

Every push to `main` builds the debug APK automatically (`.github/workflows/android.yml`)
and publishes it on the **v1.5** release:

https://github.com/abbasultra350-afk/AbbasStudio/releases/download/v1.5/app-debug.apk

## Project

- `app/src/main/java/com/abbas/studio/MainActivity.kt` — WebView shell, file picker, download bridge
- `app/src/main/assets/www/` — the built web studio (v1.5)
- `app/build.gradle.kts` — AGP 8.5.2, Kotlin 2.0.20, minSdk 24, targetSdk 34
- `gradle/wrapper/` — Gradle 8.10.2 wrapper (no local Gradle install needed)

## Version

v1.5 — audio fix (native engine no longer muted; automatic audio verification
with FFmpeg remux repair) + renamed from LuminaStudio to AbbasStudio.
