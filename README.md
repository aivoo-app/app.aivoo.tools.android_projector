# Android TV Local Media Server

An Android TV app that runs an embedded HTTP server on the TV. A remote browser uploads a file (PDF, video, image, or another file type) or submits a URL; the TV displays the content full-screen. Uploads live only in `cacheDir` and are wiped when the app starts and when the activity is destroyed.

## Table of contents
- [Features](#features)
- [Security model](#security-model)
- [Architecture](#architecture)
- [Module / package structure](#module--package-structure)
- [Tech stack](#tech-stack)
- [Prerequisites](#prerequisites)
- [Getting started](#getting-started)
- [Build & run](#build--run)
- [Usage (end-to-end)](#usage-end-to-end)
- [HTTP API](#http-api)
- [Configuration](#configuration)
- [Navigation & input](#navigation--input)
- [Lifecycle & cleanup](#lifecycle--cleanup)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [Release & signing](#release--signing)
- [Contributing](#contributing)
- [License](#license)

## Features
- Embedded NanoHTTPD HTTP server on port `7071`, with automatic fallback to a free port if `7071` is busy.
- Per-launch 6-digit pairing PIN; the URL stays clean and the PIN is shown on the TV.
- One global remote session at a time: a second device sees a busy page and cannot access the upload UI.
- Home screen shows the actual `http://<TV_IP>:<port>/` URL, QR code, 6-digit pairing PIN, and session status.
- Multipart file upload (`POST /upload`) and URL submission (`POST /url`).
- PDF viewer (zoom/pan, double-tap, DPAD-center toggle), ExoPlayer video viewer, image slideshow, and WebView renderer.
- D-pad/remote navigation, activity-level Back handling, and focus requests.
- PDF page controls and video play/pause/seek/replay/stop controls from the web page.
- Audio sync: the phone browser plays the video's audio and periodically corrects drift.
- File list with per-file delete and clear-all.
- Upload cap of 512 MB, free-space check, and temp/partial file cleanup.
- No persistence: `cacheDir` is cleared on app start and activity destroy.

## Security model
- **Pairing PIN + global session lock:** the first browser pairs with the 6-digit PIN shown on the TV, then receives an HttpOnly session cookie. The URL stays clean. Only one browser session can control the TV at a time.
- **No wildcard CORS:** state-changing endpoints are same-origin only.
- **Global session lock:** the first browser to claim the session owns the TV; a second browser gets a busy page. Heartbeats expire after 15 seconds, and Back/Home/release frees the session.
- **No APK auto-install:** unknown files (including APKs/ZIPs) are stored as `.bin` downloads only. The app never launches a package installer from an upload.
- **Path confinement:** `/media/` accepts only generated, allow-listed names; canonical-path checks reject `..`, separators, and NUL.
- **Upload limits:** `Content-Length` is checked before parsing; 512 MB cap; free-space check; temp/partial files are removed on failure.
- **WebView:** non-`http(s)` URLs are blocked; file access is disabled.
- The server still binds all interfaces. The pairing PIN and global session lock are the access control; do not expose the TV to an untrusted network.

## Architecture
```
[TV startup]
  -> App.onCreate clears cacheDir
  -> MainActivity starts NanoHTTPD on 7071 (or a free fallback port)
  -> Compose shows StartScreen
  -> User starts the server
  -> HomeScreen shows http://<TV_IP>:<port>/ and a 6-digit pairing PIN
  -> Browser pairs with the PIN, then POSTs file/URL with the session cookie
  -> MediaServer saves file to cacheDir, detects type, calls onUpload
  -> MainActivity switches to the renderer screen
  -> User views content; D-pad/remote controls
  -> onDestroy: stop server, release player, clear cacheDir
```
Data flow: browser -> NanoHTTPD handler -> `cacheDir` -> Compose renderer.

## Module / package structure
```
app/src/main/java/com/aivoo/tools/androidprojector/
├── App.kt
├── MainActivity.kt
├── model/UploadedFile.kt
├── server/MediaServer.kt
├── pdf/PdfRendererWrapper.kt
├── media/MediaManager.kt
├── util/
│   ├── MediaTypeDetector.kt
│   ├── NetworkUtils.kt
│   ├── PathUtils.kt
│   ├── QrCodeUtils.kt
│   ├── RangeParser.kt
│   ├── UpdateManager.kt
│   ├── UrlUtils.kt
│   └── VersionUtils.kt
└── ui/
    ├── HomeScreen.kt
    ├── StartScreen.kt
    ├── PdfViewerScreen.kt
    ├── VideoPlayerScreen.kt
    ├── ImageSlideshowScreen.kt
    ├── WebViewScreen.kt
    ├── ErrorScreen.kt
    ├── Theme.kt
    └── UpdateDialog.kt
```

## Tech stack
- Kotlin 2.0.0
- Android Gradle Plugin 8.13.1, Gradle 9.3.1
- Jetpack Compose (BOM 2024.10.01), Material3 with a custom dark projector theme
- Activity Compose 1.9.3
- Media3 ExoPlayer + UI 1.4.1
- NanoHTTPD 2.3.1 (HTTP)
- ZXing core 3.5.3
- AndroidX Lifecycle 2.8.6, Coroutines 1.9.0
- minSdk 21, targetSdk/compileSdk 35

## Prerequisites
- JDK 17+ (the project also builds on JDK 25 with the add-opens in `gradle.properties`)
- Android SDK with platform `android-35` and build-tools 35.0.0
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
1. Launch the app on the TV and press **Start Server**.
2. The Home screen shows `http://<TV_IP>:<port>/`, a QR code, and a 6-digit pairing PIN.
3. From a phone/laptop on the same LAN, scan the QR code or open the URL, then enter the 6-digit pairing PIN.
4. Upload a PDF/video/image, submit a URL, or open the Files tab.
5. The TV switches to the appropriate viewer. Use the remote:
   - PDF/images: Left/Right or Page Up/Down.
   - Video: PlayerView controls; web page play/pause/seek/replay/stop.
   - Back: return Home; WebView first traverses its history.
6. The Files tab supports per-file delete and clear-all.

CLI test:
```bash
curl -c cookies.txt -F 'pin=123456' -F 'clientId=cli' http://<TV_IP>:<port>/session/claim
curl -b cookies.txt -F 'file=@test.pdf' http://<TV_IP>:<port>/upload
```

## HTTP API
All routes are under `/`; the first request must pair with the PIN.

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Minimal connecting page; claims the global session |
| GET | `/app` | Full upload/share UI (requires active session cookie) |
| POST | `/session/claim` | Pair with `pin` + `clientId`; sets `tv_session` cookie |
| POST | `/session/heartbeat` | Keep the session alive |
| DELETE | `/session` | Release the session |
| POST | `/upload` | Multipart file upload (`file` field) |
| POST | `/url` | URL submission (`url` field) |
| GET | `/media/<name>` | Serve an uploaded file with Range support |
| GET | `/files` | JSON list of uploaded file names |
| POST | `/files/delete` | Delete one file (`name` field) |
| POST | `/files/clear` | Delete all uploaded files |
| GET | `/nowplaying` | Current video state JSON |
| GET | `/video/state` | Current video state JSON |
| POST | `/video/command` | `play`, `pause`, `seekback`, `seekfwd`, `replay`, `stop` |
| GET | `/pdf/state` | Current PDF page JSON |
| POST | `/pdf/command` | `prev`, `next` |
| POST | `/listen/start` | Register an audio-sync listener |
| POST | `/listen/stop` | Unregister an audio-sync listener |

## Configuration
- Preferred port `7071`; if busy, a free ephemeral port is selected and shown in the URL/QR.
- Global session lock: one browser session at a time; 15-second heartbeat timeout.
- 6-digit pairing PIN generated per server start; 5 failed attempts trigger a 30-second cooldown.
- Upload cap: 512 MB; free-space headroom: 64 MB.
- Cache wipe in `App.onCreate` and `MainActivity.onDestroy`.

## Navigation & input
- Content screens request focus on entry.
- PDF/images use `focusable()` + `onKeyEvent` for D-pad/page keys; PDF supports pinch/double-tap/DPAD-center zoom.
- Activity-level `BackHandler` returns to Home; WebView handles history first.
- Video uses PlayerView controls; Back returns Home.

## Lifecycle & cleanup
- `App.onCreate` clears `cacheDir`.
- `MainActivity.onPause` pauses ExoPlayer.
- `MainActivity.onDestroy` stops the server, releases ExoPlayer, clears `cacheDir`.
- Process death is not guaranteed to run `onDestroy`; the next launch clears the cache.
- Kotlin daemon disabled in `gradle.properties` for JDK 25 compatibility.

## Testing
Run:
```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```
Unit tests cover:
- path/name safety (`PathUtilsTest`)
- HTTP Range parsing (`RangeParserTest`)
- semantic version comparison (`VersionUtilsTest`)
- HTTP URL validation (`UrlUtilsTest`)
- media magic-byte detection (`MediaTypeDetectorTest`)

Manual QA still required on a real TV/emulator:
- D-pad/Back behavior on every screen
- PDF/video/image replacement while viewing
- audio sync start/stop, drift, and browser autoplay
- 1080p/4K and Ethernet-only devices

## Troubleshooting
- Kotlin daemon crashes on JDK 25: `kotlin.daemon.enabled=false` is set; run `./gradlew --stop` and retry.
- `Unresolved reference ContentType`: NanoHTTPD defines its own; ours is `app.aivoo.tools.androidprojector.model.ContentType` (fully qualified in `MediaServer`).
- No IP / `0.0.0.0`: check network interface and permissions; the app listens for connectivity changes.
- Upload fails: ensure the client is on the same LAN and has paired with the current PIN; check logcat `MediaServer`.
- Audio sync is silent: mobile browsers may block audible autoplay. Use the audio controls once, then press Listen again.
- Video does not play: unsupported codec/container; the player shows an error overlay.

## Known limitations
- Preferred port is 7071, but the app falls back to a free port; the actual port is shown on the TV.
- No multi-file queueing; last upload wins (images accumulate into a slideshow).
- No chunked/resumable uploads; requests without `Content-Length` are rejected with `411`.
- Unknown files are downloads only; APK auto-install has been removed.
- WebView is not a full browser: no downloads, file chooser, or full Chrome feature set.
- Screen sharing is not included in this HTTP build. Browser screen capture requires HTTPS, and phone browsers generally cannot capture their screen from a web page. Use the TV’s built-in Miracast/AirPlay/Chromecast for phone mirroring.
- Audio sync is best-effort and depends on browser autoplay policy and network latency.
- Cache is not wiped on process death/force-stop; only on the next launch and normal activity destroy.
- No user accounts; the pairing PIN and global session lock are the access control.
- PDF zoom is capped at 5x and resets when the page changes.

## Release & signing
- CI builds `assembleRelease` and `bundleRelease` on pushes/PRs and runs unit tests + lint.
- Tag pushes matching `vX.Y.Z` create a GitHub Release.
- Release signing uses these repository secrets:
  - `KEYSTORE_BASE64`
  - `KEYSTORE_PASSWORD`
  - `KEY_ALIAS`
  - `KEY_PASSWORD`
- `versionCode`/`versionName` are derived from the tag:
  - `v1.2.3` -> `versionCode = 1002003`, `versionName = 1.2.3`.
- Local builds without the env vars fall back to debug signing.

## Contributing
1. Create a feature branch.
2. Keep package boundaries (`ui`, `server`, `media`, `pdf`, `model`, `util`).
3. Run `./gradlew testDebugUnitTest lintDebug assembleDebug` before opening a PR.
4. Match existing Kotlin/Compose style; D-pad accessibility is required for new screens.

## License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file.