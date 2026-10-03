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
- Built-in player (Media3): chapters (seek bar segments + list, SponsorBlock-aware), subtitle and
  audio-language tracks, speed, ±10 s / double-tap seek, resume where you left off, fit/fill,
  picture-in-picture, background audio, keyboard shortcuts (space, ←/→, J/K/L, C)
- AirPods and other Bluetooth headphones via the media session: squeeze to play/pause,
  pause when they're taken out, lock-screen controls, system output switcher
- AirPlay audio to HomePods and other AirPlay speakers (experimental, see below)

## Design
"Siphon brewer": the app is named after the vacuum coffee maker, so the palette borrows its materials:
espresso surfaces, a brass primary, glass blue for progress, sage for audio and cherry red for errors.
Light mode is cool lab glass rather than warm paper. Titles use Fraunces (soft optical axis),
everything else Manrope. The one bold element is the liquid fill: a download's thumbnail
fills from the bottom as it progresses. Tokens live in `ui/theme/Theme.kt`.

Layouts: download and queue side by side at ≥1100dp (tablet landscape), a single centered column below that.

### Screenshot renders
Every screen is rendered on the JVM (Roborazzi + Robolectric) at tablet landscape/portrait and phone sizes,
in light and dark:

```bash
./gradlew :app:recordRoborazziDebug   # writes app/screenshots/*.png
```

## AirPlay
Android has no AirPlay, so `airplay/RaopClient.kt` implements a classic AirPlay (RAOP) sender:
RTSP session, uncompressed ALAC over RTP, NTP timing replies, sync packets, retransmits, volume, and
RSA/AES encryption for speakers that require it. Speakers are found over mDNS (`_raop._tcp`). The player's
decoded audio is tapped after speed changes (`AirPlayTap`), resampled to 44.1 kHz stereo and streamed,
while local output is muted. The speaker buffers about 2 s, so for video the sound trails the picture.

Speakers that ask for a password, or HomePods with speaker access limited to people sharing the
home, refuse the connection; set *Allow Speaker & TV Access* to everyone on the same network.

Test against a local receiver:

```bash
shairport-sync -a SiphonTest -o stdout > out.pcm   # listens on :5000
SIPHON_RAOP=127.0.0.1:5000 ./gradlew --no-daemon :app:testDebugUnitTest --tests '*RaopClientTest*'
```

## Build
Requires the Android SDK (`ANDROID_HOME`) and JDK 17+.

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Release builds are R8-minified and signed with the local debug key (sideload only). See `app/proguard-rules.pro`
for the keep rules youtubedl-android needs.
