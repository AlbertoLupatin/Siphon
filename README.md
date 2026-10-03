# Siphon

A tablet-first Android front end for [yt-dlp](https://github.com/yt-dlp/yt-dlp), built with Kotlin and Jetpack Compose.

Python, yt-dlp, ffmpeg and QuickJS ship inside the APK via
[youtubedl-android](https://github.com/JunkFood02/youtubedl-android); yt-dlp updates itself in-app.

## Features
- Two-pane tablet layout (new download | live queue), navigation rail; collapses to one column on phones
- Share a link to Siphon from any app → metadata + formats load automatically
- Presets (Best, 4K…480p, Audio original/M4A/MP3) or pick an exact format
- H.264/AAC MP4 compatibility mode, embedded metadata / thumbnail / chapters / subtitles, SponsorBlock
- Background queue (foreground service), 1–4 parallel downloads, cancel / retry, yt-dlp logs
- Library grid with search and filters; files saved to `Download/Siphon`

## Build
Requires the Android SDK (`ANDROID_HOME`) and JDK 17+.

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Release builds are R8-minified and signed with the local debug key (sideload only). See `app/proguard-rules.pro`
for the keep rules youtubedl-android needs.
