<h1 align="center">Jellyfin for Android</h1>
<h3 align="center">Enhanced fork of the <a href="https://jellyfin.org">Jellyfin Project</a> Android client</h3>

---

<p align="center">
<img alt="Logo Banner" src="https://raw.githubusercontent.com/jellyfin/jellyfin-ux/master/branding/SVG/banner-logo-solid.svg?sanitize=true"/>
<br/>
<br/>
<a href="https://github.com/zhan19/jellyfin-android">
<img alt="GPL 2.0 License" src="https://img.shields.io/github/license/zhan19/jellyfin-android.svg"/>
</a>
<a href="https://github.com/zhan19/jellyfin-android/releases">
<img alt="Current Release" src="https://img.shields.io/github/release/zhan19/jellyfin-android.svg"/>
</a>
<br/>
<a href="https://github.com/jellyfin/jellyfin-android">
<img alt="Upstream" src="https://img.shields.io/badge/upstream-jellyfin%2Fjellyfin--android-blue.svg"/>
</a>
</p>

Jellyfin Mobile is an Android app that connects to Jellyfin instances and integrates with the [official web client](https://github.com/jellyfin/jellyfin-web).

This repository is an **enhanced fork** of [jellyfin/jellyfin-android](https://github.com/jellyfin/jellyfin-android) that adds several native features on top of the upstream web-wrapper client.

## ✨ Native Features

### 📱 Shorts — Vertical Video Feed

A TikTok-style vertical shorts feed built entirely with native Android components:

- Full-screen vertical video feed with swipe-up/swipe-down navigation
- Streams videos directly from your Jellyfin server (movies, episodes, music videos)
- Library picker — choose which Jellyfin library feeds the shorts stream
- Smart queue management with session deduplication and stream URL caching
- Favorite & played state synced with your Jellyfin server
- Implemented with Jetpack Compose, ExoPlayer (Media3), and Kotlin Coroutines

See the implementation in [`app/src/main/java/org/jellyfin/mobile/shorts/`](app/src/main/java/org/jellyfin/mobile/shorts/).

### ⬇️ Downloads — Native Download Manager

- Queue-based downloads with background `WorkManager` workers
- Download progress notifications
- File type detection and range-based resumable downloads

See the implementation in [`app/src/main/java/org/jellyfin/mobile/downloads/`](app/src/main/java/org/jellyfin/mobile/downloads/).

### 🖥️ Session Browser

Browse and manage active playback sessions on your Jellyfin server from the app.

## 🧱 Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Android XML views (web wrapper)
- **Media:** ExoPlayer / Media3
- **DI:** Dagger (Hilt-style modules)
- **Async:** Kotlin Coroutines & Flow
- **Persistence:** Room
- **Build:** Gradle with version catalogs, Detekt

## Build Process

### Dependencies

- Android SDK

### Build

1. Clone or download this repository

   ```sh
   git clone https://github.com/zhan19/jellyfin-android.git
   cd jellyfin-android
   ```

2. Open the project in Android Studio and run it from there or build an APK directly through Gradle:

   ```sh
   ./gradlew assembleDebug
   ```

### Deploy to device/emulator

   ```sh
   ./gradlew installDebug
   ```

*You can also replace the "Debug" with "Release" to get an optimized release binary.*

## Release Flavors

There are two flavors (variants) of the Jellyfin for Android app:

- The **proprietary** version comes with Google Chromecast support
- The **libre** version comes without Google Chromecast support

## License

Licensed under the [GPL 2.0 License](LICENSE.md), same as the upstream Jellyfin Android client.

## Contributing & Upstream

This fork tracks the upstream [jellyfin/jellyfin-android](https://github.com/jellyfin/jellyfin-android) project. For upstream-specific issues (web client, translations, general app behavior), please refer to the upstream repository.
