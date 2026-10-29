![Build](https://github.com/aivoo-app/app.aivoo.tools.android_projector/actions/workflows/build.yml/badge.svg)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)

# Android TV Local Media Server

An Android TV app that runs an embedded HTTP server on the TV. A remote browser uploads a file (PDF, video, image) or submits a URL; the TV displays the content full-screen. All uploads live only in `cacheDir` and are wiped on exit/crash (no persistence).

## Table of contents
- [Features](#features)
- [Architecture](#architecture)
- [Module / package structure](#module--package-structure)
- [Tech stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Getting started](#getting-started)
- [Build & run](#build--run)
- [Usage (end-to-end)](#usage-end-to-end)
- [Configuration](#configuration)
- [Navigation & input](#navigation--input)
- [Lifecycle & cleanup](#lifecycle--cleanup)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Known limitations / open questions](#known-limitations--open-questions)
- [Contributing](#contributing)

## Features
- Embedded NanoHTTPD server on port `8080`, LAN only.
- Home screen shows the TV's local IP + port and upload status.
- Multipart file upload endpoint (`POST /upload`) and URL submission (`POST /url`).
- Renderers: PDF (page-by-page), video (ExoPlayer), image slideshow, WebView for URLs.
- D-pad/remote-driven navigation and playback controls.
- No persistence: `cacheDir` is cleared on create and destroy.

## Architecture
```
[TV startup]
  -> App.onCreate clears cacheDir
  -> MainActivity starts NanoHTTPD (port 8080) in a coroutine
  -> Compose shows HomeScreen with IP:port
  -> Browser POSTs file/URL to /upload or /url
  -> MediaServer saves file to cacheDir, detects type, calls onUpload
  -> MainActivity state machine switches to renderer screen
  -> User views content; D-pad controls
  -> onDestroy: stop server, release player, clear cacheDir
```
Data flow: network (browser) -> NanoHTTPD handler -> file system (`cacheDir`) -> renderer (Compose screen).

## Module / package structure
```
app/src/main/java/com/aivoo/tools/androidprojector/
├── App.kt
├── MainActivity.kt
├── model/UploadedFile.kt
├── util/NetworkUtils.kt, FileUtils.kt
├── server/MediaServer.kt
├── pdf/PdfRendererWrapper.kt
├── media/MediaManager.kt
└── ui/HomeScreen.kt, PdfViewerScreen.kt, VideoPlayerScreen.kt, ImageSlideshowScreen.kt, WebViewScreen.kt
## Tech stack
- Kotlin 2.0.0
- Android Gradle Plugin 8.13.1, Gradle 9.3.1
- Jetpack Compose (BOM 2024.10.01) + `androidx.tv:tv-material:1.0.0`
- Activity Compose 1.9.3
- Media3 ExoPlayer + UI 1.4.1
- NanoHTTPD 2.3.1 (+ apache-fileupload)
- AndroidX Lifecycle 2.8.6, Coroutines 1.9.0
- minSdk 21, targetSdk/compileSdk 34

## Prerequisites
- JDK 17+ (project builds on JDK 25 with the add-opens in `gradle.properties`)
- Android SDK with platform `android-34` and build-tools
- Network access for first Gradle dependency resolution

## Getting started
```bash
git clone <repo>
cd app.aivoo.tools.android_projector
echo "sdk.dir=/path/to/Android/sdk" > local.properties
```

## Build & run
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Use the committed `./gradlew` wrapper, not a system Gradle.

## Usage (end-to-end)
1. Launch the app on the TV. Home screen shows `http://<TV_IP>:8080`.
2. From a phone/laptop on the same LAN, open that URL.
3. Upload a PDF/video/image, or submit a URL.
4. The TV switches to the appropriate viewer; use the remote (D-pad) to navigate (Left/Right = prev/next for PDFs/images).
5. Back exits to Home. Closing the app wipes all uploads.

CLI test: `curl -F 'file=@test.pdf' http://<TV_IP>:8080/upload`

## Configuration
- Port fixed at `8080` (`MainActivity` / `MediaServer` default).
- Cache wipe in `App.onCreate` and `MainActivity.onDestroy`.

## Navigation & input
- All screens use `Modifier.focusable()` + `Modifier.onKeyEvent` for D-pad.
- `HomeScreen` URL field uses the system IME.

## Lifecycle & cleanup
- `App.onCreate` clears `cacheDir`.
- `MainActivity.onDestroy` stops the server, releases ExoPlayer, clears `cacheDir`.
- Kotlin daemon disabled in `gradle.properties` for JDK 25 compatibility.

## Testing
No automated tests yet. Suggested: unit tests for NanoHTTPD handlers and `PdfRendererWrapper`; instrumentation via Compose/Espresso on Android TV emulator; manual uploads via browser/curl on real TV hardware.

## Troubleshooting
- Kotlin daemon crashes on JDK 25: `kotlin.daemon.enabled=false` is set; run `./gradlew --stop` and retry.
- `Unresolved reference ContentType`: NanoHTTPD defines its own; ours is `app.aivoo.tools.androidprojector.model.ContentType` (aliased `AppContentType` in `MediaServer`).
- No IP / 0.0.0.0: check network interface and permissions.
- Upload fails: ensure client on same LAN; check logcat `MediaServer`.

## Known limitations / open questions
- Fixed port 8080 (no auto-fallback).
- No multi-file queueing; last upload wins (images accumulate into slideshow).
- No chunked/resumable uploads; partial uploads deleted on error.
- Unsupported codecs/PDF features surface as errors (no fallback library).
- WebView has no custom back handling.

## Contributing
1. Create a feature branch.
2. Keep package boundaries (`ui`, `server`, `media`, `pdf`, `model`, `util`).
3. Run `./gradlew assembleDebug` before opening a PR.
4. Match existing Kotlin/Compose style; D-pad accessibility required for new screens.
```
## CI / GitHub Actions
A workflow (`.github/workflows/build.yml`) builds Debug and Release APKs on every push/PR.
- Artifacts: download `app-debug` / `app-release-unsigned` from the Actions tab.
- Push a tag like `v1.0.0` to create a GitHub Release with the APKs attached.
- To use: create a GitHub repo, then `git remote add origin <url>` and `git push -u origin main`.

## License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file.
