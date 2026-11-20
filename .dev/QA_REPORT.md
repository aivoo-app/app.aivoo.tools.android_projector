# QA Report — Android TV Projector

**Date:** 2026-11-20
**Branch:** `master`
**Commit:** `26ca11e7541bece9b2a00af834b9cb820f503692`
**Scope:** Full read-only QA of the Android TV app, embedded NanoHTTPD server, Compose UI, media pipeline, build/CI, manifest/resources, and documentation.
**Status:** Report only. No app source was changed.

---

## 0. Verification evidence

| Check | Result |
|---|---|
| `./gradlew assembleDebug` | **PASS** |
| `./gradlew testDebugUnitTest` | **PASS** — 5/5 tests in `FileUtilsTest` |
| `./gradlew lintDebug` | **FAIL** — **33 errors, 24 warnings, 2 hints** |
| Release APK signature | **Android Debug certificate** (`CN=Android Debug`) |
| Release APK version | `versionCode=1`, `versionName=1.0` |
| Manifest badging | `uses-feature: android.hardware.wifi` is **required** (implied by `ACCESS_WIFI_STATE`) |
| Launcher icon | only `mipmap-anydpi-v26`; no pre-API-26 fallback |
| Path traversal | confirmed NanoHTTPD decodes `%2F`; `..` escapes `cacheDir` |
| ExoPlayer thread check | confirmed `setVolume()` calls `verifyApplicationThread()` and throws off-main |

The lint failure is hidden from CI because CI only runs `assembleRelease`/`bundleRelease`, not `lint` or `test`. All 33 lint errors are `UnsafeOptInUsageError` from Media3 opt-in APIs in `LiveAudio.kt` and `MediaManager.kt`; the 24 warnings are mostly stale `targetSdk`/dependencies, cleartext traffic, WebView JS, Wi-Fi permission, fixed orientation, and KTX suggestions.

**Solid points:** clean package boundaries, cache-only/no-persistence design, `allowBackup=false`, leanback/touchscreen features not required, `FileProvider` configured, `FLAG_KEEP_SCREEN_ON`, PDF/image D-pad handlers present, unit tests exist, CI builds APK+AAB, and the server/renderer separation is understandable.

---

## 1. Critical (P0) — do not release before these are closed

### P0-1 — Unauthenticated, cross-origin-writable control plane

- **Evidence:** `server/MediaServer.kt:39-64` exposes upload/URL/media/now-playing/audio/PDF/video/files/listen routes with no token, password, PIN, or session check. `Access-Control-Allow-Origin: *` is set on `/nowplaying` (`:362`), `/audio/live` (`:404`), `/listen/*` (`:433`), `/pdf/state` (`:441`), `/pdf/command` (`:452`), `/video/state` (`:460`), `/video/command` (`:471`), and `/files` (`:485`). NanoHTTPD binds a wildcard server socket.
- **Impact:** Any device on the same Wi-Fi/Ethernet/VPN/hotspot network can upload files, change the URL, play/pause/seek video, turn pages, list files, read playback state, and trigger the APK installer. Any website visited on a phone can also send simple `multipart/form-data` or `application/x-www-form-urlencoded` POSTs to the TV (CSRF), even though it cannot read the response. The README's "LAN only" claim is not a security boundary.
- **Fix:** Add a high-entropy pairing token (at least 128-bit) embedded in the QR/URL and required on every endpoint (header or path), constant-time compare, token rotation on restart. Remove wildcard CORS; same-origin only. Add a CSRF token or require a custom header for state-changing requests. Optionally bind to the local network/allowlist client IPs. Never log the token.
- **Acceptance:** no-token request returns 401; wrong token returns 403; cross-origin POST from a different origin cannot mutate state; token appears in QR; CORS headers absent or origin-restricted.

### P0-2 — Path traversal in `/media/`

- **Evidence:** `server/MediaServer.kt:326-330` does `File(context.cacheDir, name)` where `name = uri.removePrefix("/media/")`. NanoHTTPD's `HTTPSession` decodes the URI with `decodePercent()` before storing it (confirmed with `javap`). Verified:
  - `GET /media/..%2Fshared_prefs%2Fupdate_prefs.xml`
  - decodes to `/media/../shared_prefs/update_prefs.xml`
  - resolves to `<app_data>/shared_prefs/update_prefs.xml`, outside `cacheDir`.
- **Impact:** An unauthenticated LAN client can read app-private files (`shared_prefs`, `files`, `databases`, `no_backup`) and any cache file, not just uploaded media. It can also read `update.apk` if present.
- **Fix:** Canonicalize and confine:
  - reject names containing `/`, `\`, `..`, NUL, or empty segments;
  - resolve `File(base, name).canonicalFile` and require `path == base.path + separator` or `file.parentFile == base`;
  - serve only names present in `uploadedFiles`;
  - add unit tests for raw and percent-encoded traversal.
- **Acceptance:** `/media/../shared_prefs/...`, `/media/..%2F...`, `/media/%2e%2e%2f...`, double-encoded variants, and absolute-path attempts all return 404/403.

### P0-3 — Upload size is enforced after the whole body is written; disk-exhaustion DoS

- **Evidence:** `server/MediaServer.kt:265-290` calls `session.parseBody(files)` first, then checks `tmpFile.length() > MAX_UPLOAD_BYTES` at `:273`. `MAX_UPLOAD_BYTES` is 2 GB (`:23`). The temp file is then copied to a second file (`:286-290`), so peak usage is roughly 2x the upload. There is no free-space check, no request `Content-Length` pre-check, and temp cleanup only happens on some paths.
- **Impact:** Multiple concurrent or large uploads can fill the TV's cache, cause ANRs/OOM, break playback, and leave temp files behind on exceptions. A 2 GB limit is far beyond typical TV cache capacity.
- **Fix:** Reject oversized `Content-Length` in `serve()` before `parseBody`; enforce a much lower configurable cap (e.g. 256-512 MB); stream/copy with a hard byte limit; check `StatFs` available bytes; delete temp and partial output on every failure path; consider a custom `TempFileManagerFactory` with its own cleanup.
- **Acceptance:** an oversized request is rejected before temp write; disk-full and copy-failure paths leave no temp/partial files; concurrent uploads do not exhaust storage.

### P0-4 — APK auto-install from an unauthenticated upload; any ZIP is treated as an APK

- **Evidence:** `MainActivity.kt:113-124` auto-launches `ACTION_VIEW` package installation for any uploaded file ending `.apk`. `MediaServer.kt:279-283` classifies **any** file whose first two bytes are `PK` as `.apk`, so DOCX/XLSX/ZIP/JAR all become `.apk`.
- **Impact:** Any LAN peer can push an arbitrary APK and trigger an install prompt on the TV. Users who are used to accepting TV prompts may install malware. Ordinary ZIP documents produce a confusing "App not installed" flow. This is a serious unauthenticated remote-code-install vector.
- **Fix:** Remove auto-install entirely until P0-1 auth and an explicit TV-side confirmation exist. Do not infer APK from `PK` alone; validate the ZIP contains `AndroidManifest.xml` and, ideally, verify the APK signature/package. Prefer an explicit "Install shared APK?" dialog with package name/version. Reconsider whether `REQUEST_INSTALL_PACKAGES` is needed at all for this app.
- **Acceptance:** no APK installer starts from an unauthenticated upload; ZIP/JAR/DOCX are not treated as APKs; TV shows a confirmation with package identity.

### P0-5 — Release is debug-signed; CI has no stable signing key; versionCode never changes

- **Evidence:** `app/build.gradle.kts:25-27` uses `signingConfigs.getByName("debug")` for `release`; `:19-20` pins `versionCode = 1`, `versionName = "1.0"`. CI (`.github/workflows/build.yml`) has no keystore secret and no versionCode/versionName injection. The local release APK is signed `CN=Android Debug` (`apksigner` confirmed). No git tags exist yet.
- **Impact:** GitHub Actions runners generate a fresh random debug keystore per run. Release APKs from different CI runs are signed with different certificates, so installing one over another fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "App not installed". The in-app updater therefore breaks after the first CI release. A constant `versionCode=1` also prevents proper upgrade ordering and can confuse installers.
- **Fix:** Create a real release keystore, store it as encrypted GitHub secrets, configure a `release` signingConfig from env, and verify the signer in CI. Derive `versionCode`/`versionName` from the git tag (monotonic), and use semantic-version comparison rather than string inequality.
- **Acceptance:** two consecutive CI releases install over each other; `apksigner verify` shows the same non-debug cert; versionCode strictly increases; tag `v1.2.3` produces `versionName=1.2.3` and a higher versionCode.

### P0-6 — ExoPlayer volume is changed from a NanoHTTPD worker thread; listen/stop can 500 and leave the TV muted

- **Evidence:** `MainActivity.kt:129-136` does:

  ```kotlin
  }, { listening ->
      mediaManager.player?.volume = if (listening) 0f else 1f
      mediaManager.onStateChanged = { ... }
  }
  ```

  This callback is invoked by `MediaServer.handleListen()` (`MediaServer.kt:429-435`) on a NanoHTTPD worker. `ExoPlayerImpl.setVolume()` calls `verifyApplicationThread()`; the default `throwsWhenUsingWrongThread` is `true` (confirmed in Media3 1.4.1 bytecode), so it throws `IllegalStateException` when a player exists.
- **Impact:** In the normal flow (open a video, then click "Listen" on the phone), `/listen/start` returns HTTP 500, the TV is **not muted**, and `onStateChanged` is never installed. `/listen/stop` can also 500, leaving the TV muted with no way to unmute from the web UI. Audio sync then has stale `currentPlaying` state and may never initialize correctly.
- **Fix:** Marshal all player access to the main/application thread (`runOnUiThread`, a `Handler`, or `mediaManager` methods that post). Install `onStateChanged` once in `MediaManager.createPlayer()`, not inside the listen callback. Track listeners separately from playback. On `/listen/stop` or last-client disconnect, restore volume to 1.0.
- **Acceptance:** `/listen/start` and `/listen/stop` return 200 with a player active; TV mutes/unmutes reliably; browser close or `pagehide` restores volume; no `IllegalStateException` in logcat.

### P0-7 — Replacing PDF/video/URL while that viewer is open leaves a stale renderer/player/WebView

- **Evidence:**
  - `ui/PdfViewerScreen.kt:26` uses `remember { PdfRendererWrapper(file) }` with **no `file` key**. `LaunchedEffect(pageIndex)` at `:31` also will not rerun if the new PDF starts at the same page index. The old renderer keeps the old file.
  - `ui/VideoPlayerScreen.kt:15` uses `remember { mediaManager.createPlayer() }`; `DisposableEffect(path)` at `:17` disposes the old player and creates a new one, but the `AndroidView` at `:22-31` was created with the old player and has no `update` block. The new player is not attached to `PlayerView`; the old released player remains attached.
  - `ui/WebViewScreen.kt:14-22` creates the WebView in `factory` and calls `loadUrl(url)` only once. There is no `update` block, so a second submitted URL does not load.
- **Impact:** Upload a second PDF while viewing one, and the old pages remain. Upload a second video while viewing one, and you get a black screen / released-player errors, with audio possibly playing without video. Submit a second URL, and the old page remains. These are core product flows.
- **Fix:** Key the PDF screen by file (`key(file)` or `remember(file)`), key/recreate the player per path, and add `AndroidView(update = { it.player = player })`; for WebView, add `update = { it.loadUrl(url) }` and `onDispose { it.destroy() }`. Reset page/index/position state on content replacement.
- **Acceptance:** while viewing PDF/video/WebView, upload a second item or submit a second URL; the new item renders; no stale/released player; Back returns Home; no crash.

### P0-8 — Stale playback/audio state after leaving a video

- **Evidence:** `server.currentFile` is set only in `MediaServer.handleUpload()` for `ContentType.VIDEO` (`MediaServer.kt:292-295`) and is never cleared on Back or content change. `MainActivity.kt:179-191` switches to `Content.None`/PDF/images without resetting `server.currentFile`, `currentPlaying`, `currentPositionMs`, or `videoEventId`. The 100 ms loop (`MainActivity.kt:79-87`) keeps publishing position while a video is open, but `currentPlaying` is only updated via the listen callback (which is broken per P0-6).
- **Impact:** After returning Home or opening a PDF, `/nowplaying` still advertises the old video. A browser that clicks "Listen" can start playing the old video's audio while the TV shows Home/PDF. The TV may be muted with no video. `/video/state` remains inconsistent.
- **Fix:** On Back, content replacement, and player release, clear `currentFile`, `currentPlaying`, `currentPositionMs`, `videoDurationMs`, and bump `videoEventId`; stop/reset audio sync clients. Define one source of truth for player state. Either implement `/video/state` properly or remove it.
- **Acceptance:** after Back/Home/PDF, `/nowplaying` returns `file:null`; no stale audio; no muted TV; `/video/state` consistent or removed.

---

## 2. High (P1) — functional, security, or release quality

### P1-1 — Update flow logic and error handling

- `util/UpdateManager.kt:51-55`: `isUpdateAvailable()` compares `tagName != installed` as strings. Default installed is `"v" + BuildConfig.VERSION_NAME` giving `v1.0`; CI tags are `v0.0.N`, so this is treated as an update/downgrade. No semantic-version ordering.
- `util/UpdateManager.kt:57-81`: `download()` does not check `conn.responseCode`; a 404/HTML error page can be written to `update.apk`, then installation fails.
- `MainActivity.kt:201-204`: `markInstalled()` is called **before** `installApk()`; if the user cancels or the installer is unavailable, the update is marked installed and never offered again.
- `util/UpdateManager.kt:87-94`: `installApk()` calls `startActivity()` without catching `ActivityNotFoundException` / `SecurityException`; on a TV with no package installer this can crash the coroutine/app.
- No download progress, no error UI, no checksum/signature verification, no `conn.disconnect()`, no `User-Agent`.
- **Fix:** semantic version compare; check HTTP status; download to a temp file then install; mark installed only after a successful install callback (or after user confirms); catch installer exceptions; show progress/error; verify APK signature/checksum; add a "skip this version" option.

### P1-2 — TV Back handling and focus are likely broken

- No `BackHandler` anywhere. PDF/images rely on `Modifier.onKeyEvent` inside a `focusable()` Box; video/WebView put `onKeyEvent` on the `AndroidView` wrapper while `PlayerView`/WebView child views may consume Back. If the Compose key handler does not receive Back, the Activity finishes and the app exits instead of returning Home.
- No `FocusRequester`/`LaunchedEffect` to request focus on screen entry. On Android TV, entering a new screen with no focused child can leave D-pad events with nowhere to go; PDF/image/video remote controls may appear unresponsive.
- **Fix:** Use `BackHandler` from `androidx.activity.compose` on every screen; for WebView, first `canGoBack()`; for video, pause/stop then release; for PDF/images, back to Home. Request focus on the primary control/root on first composition. Test with a real D-pad remote.

### P1-3 — PDF/image rendering on the main thread; crash and OOM paths

- `ui/PdfViewerScreen.kt:31-33` calls `renderer.renderPage(pageIndex, 1920)` directly in a `LaunchedEffect` on the main dispatcher. A large/complex page blocks the UI thread; Back and remote input lag.
- `pdf/PdfRendererWrapper.kt:15-19` opens the `ParcelFileDescriptor`/`PdfRenderer` in `init`; corrupt or password-protected PDFs throw during composition and crash the app. There is no error state.
- `PdfRendererWrapper.renderPage()` does not use `try/finally` around `page.close()`; a render exception leaks the page.
- `ui/ImageSlideshowScreen.kt:23-25` calls `BitmapFactory.decodeFile()` on the main thread; large photos can ANR/OOM. No `inSampleSize`/downsampling, no EXIF rotation, no null/error handling. A corrupt or HEIC image returns null and the screen shows "Loading..." forever.
- PDF/image labels are white text with no scrim (`PdfViewerScreen.kt:59-64`, `ImageSlideshowScreen.kt:49-50`); on a white PDF page or bright image they can be invisible.
- PDF is rendered at fixed 1920 px width; on a 4K TV it is upscaled/blurry. No zoom/pan (removed intentionally after a crash, but now small text is unreadable).
- **Fix:** render/decode off-main; cache/recycle bitmaps; catch renderer/init errors and show an error screen; add downsampling and EXIF rotation; use a scrim/background for overlay text; size PDF bitmaps from the actual display width with a safe cap; add zoom/pan or a safe "open in external viewer" fallback.

### P1-4 — Video playback lacks error/buffering/audio-focus handling and lifecycle pause

- No `Player.Listener.onPlayerError` handling; unsupported codecs surface as a black screen. No buffering indicator.
- `VideoPlayerScreen.kt:15-20` creates a player and `playFile()`; `DisposableEffect(path)` releases it, but there is no `onPause`/`onStop` pause. If the user presses Home, the server/activity may remain alive and video/audio continues.
- No `setAudioAttributes(..., handleAudioFocus=true)`; other TV audio can overlap.
- `MediaManager.createPlayer()` does not guard against an existing player; repeated creation could leak players.
- `onVideoCommand` seek forward (`MainActivity.kt:152`) does not clamp to duration (ExoPlayer clamps, but server state can be ahead).
- **Fix:** add error/buffering UI, audio attributes, lifecycle-aware pause/release, guard `createPlayer`, clamp seeks, and update `server.currentPositionMs` only from the player.

### P1-5 — WebView hardening and lifecycle

- `ui/WebViewScreen.kt:16-20` enables JavaScript and DOM storage for arbitrary URLs submitted by any LAN peer; lint warns `SetJavaScriptEnabled`.
- No `onDispose { webView.destroy() }` leading to a WebView leak.
- No `update` block, so a second URL is ignored (P0-7).
- No `WebChromeClient`, so JS dialogs, file chooser, fullscreen, and some video playback do not work.
- No `shouldOverrideUrlLoading`, so redirects to `intent://`, `market://`, `file://`, etc. are not blocked; `allowFileAccess` is not explicitly disabled.
- No error page/progress; Back always exits the viewer and does not traverse WebView history.
- **Fix:** add update/destroy, `WebChromeClient`, safe URL scheme allowlist, disable file/content access where possible, safe browsing, `mediaPlaybackRequiresUserGesture` as appropriate, error/progress UI, and Back history handling. Consider a desktop user agent for TV projection.

### P1-6 — Audio sync design is fragile and partly dead

- The landing page (`MediaServer.kt:221-257`) plays `/media/<video>` in an `<audio>` element, not `/audio/live`; `AudioBroadcaster`, `LiveAudioProcessor`, and `/audio/live` (`MediaServer.kt:366-427`) are effectively dead code in the current UI. `LiveAudioProcessor` is still installed in `MediaManager.kt:24`, adding overhead and lint errors.
- `audio.play()` is called inside `setInterval`, not directly in the click handler. Chrome may allow it after user interaction; iOS Safari may block audible autoplay. The rejection is swallowed by `.catch(()=>{})`.
- Sync correction only happens when `eventId` changes (`MediaServer.kt:234-247`), not continuously. There is no drift correction during steady playback, no latency calibration, and no handling for buffering.
- Setting `audio.currentTime` immediately after `load()` can fail before metadata is loaded in some browsers.
- A single `listenerActive` boolean and one `volume=0` state are shared by all clients; one client's Stop unmutes everyone. There is no heartbeat/disconnect timeout, so closing the browser without pressing Stop can leave the TV muted indefinitely.
- `/audio/live` uses a WAV header with `0x7FFFFFFF` sizes, blocks a worker thread in `q.take()`, has no format-change handling, and is not used by the page.
- `serveNowPlaying`/`serveVideoState` manually construct JSON; safe only because names are generated. `currentPlaying` can be stale because it is not updated by the 100 ms position loop.
- **Fix:** decide whether audio sync is a supported feature. If yes: unlock audio in the click handler; continuously correct drift with a tolerance; track multiple clients with heartbeats and auto-unmute when none remain; handle `pagehide`/`visibilitychange`; either finish `/audio/live` (and use it for all formats) or remove it; use a JSON serializer; stop sync on content change.
- If no: remove `LiveAudio.kt`, the audio processor, `/audio/live`, the Listen UI, and `listenerActive`.

### P1-7 — Range handling, MIME types, and file naming

- `server/MediaServer.kt:334-351` parses `Range` naively:
  - suffix ranges `bytes=-500` are wrong;
  - multiple ranges `bytes=0-1,2-3` are ignored/truncated;
  - invalid/unsatisfiable ranges return 200/206 with negative lengths instead of 416;
  - `fis.skip(start)` is assumed to skip fully;
  - responses use `newChunkedResponse()` **and** add a `Content-Length` header, producing both `Transfer-Encoding: chunked` and `Content-Length`, which is invalid HTTP and potentially problematic for browser seeking/playback.
- All videos are saved as `.mp4` and all images as `.jpg` (`MediaServer.kt:526-539`); `mimeFromName()` then serves MKV/WebM/AVI as `video/mp4` and PNG/GIF/WebP as `image/jpeg`. Browsers may fail audio sync for MKV/WebM, and file downloads have wrong MIME.
- `detectType()` (`MediaServer.kt:506-524`) misses BMP, HEIC/AVIF (HEIC contains `ftyp` and is misclassified as VIDEO), MPEG-TS, MP3/WAV/OGG; it silently returns UNKNOWN on error. `FileUtils.typeFromMime("text/*")` maps to `ContentType.URL`, which is semantically wrong.
- Uploaded files lose their original filename; TV status and `/files` show only `upload_<timestamp>.<ext>`. This is a significant usability issue for a projector app.
- **Fix:** implement proper Range parsing (single range, suffix, 416, `Content-Range`), use `newFixedLengthResponse` with explicit lengths (no chunked+Content-Length), set `Accept-Ranges`/`Content-Length` correctly, add `HEAD`, preserve sanitized original names and correct extensions/MIME by sniffing, add HEIC/BMP/MPEG-TS handling, and add unit tests.

### P1-8 — Server/file state, lifecycle, and networking

- `server/MediaServer.kt:37` exposes `uploadedFiles` as a plain `mutableListOf`; `handleUpload()` adds without synchronization while `/files` reads under `synchronized(uploadedFiles)`, so the add is not actually protected.
- `uploadedFiles` is never pruned when `currentPdfFile`/`currentVideoFile` are deleted (`MainActivity.kt:96,103`). `/files` therefore lists deleted files and links 404. Images accumulate forever until app exit; there is no delete/clear endpoint.
- `MediaServer` holds an Activity `Context` (`MainActivity.kt:91`); use `applicationContext` to avoid leaks if the server outlives the activity.
- `App.clearCache()` runs on the main thread in `App.onCreate` and `MainActivity.onCreate/onDestroy`; with a large cache this can block startup/shutdown. The README claim that cache is wiped "on exit/crash" is false: `onDestroy` is not guaranteed on process death/force-stop; only the next launch wipes it.
- `NetworkUtils.getIpAddress()` picks the first non-loopback IPv4 interface (`util/NetworkUtils.kt:10-21`), which can be `tun0`/`p2p0`/VPN/cellular rather than Wi-Fi/Ethernet. It runs once in `onCreate`; if the network changes, the displayed IP/QR is stale. It returns `0.0.0.0` with no warning. `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` are declared but unused.
- Port is hardcoded twice (`MainActivity.kt:61`, `MediaServer.kt:14`), with no fallback. The README says 8080 while the app uses 7071.
- `MainActivity` shows no status on `StartScreen`; if `s.start()` fails (port busy), the error is invisible and the user can tap Start repeatedly. The Start button is not disabled during startup, so a double tap can create two server instances; `server` is assigned before `start()` (`MainActivity.kt:168-170`).
- `MainActivity` has no `onPause`/`onStop` handling and no `onSaveInstanceState`; process death loses all Compose state while the server may have been running.
- **Fix:** use `CopyOnWriteArrayList`/synchronized add+remove; delete/clear file records with files; add `/delete`/`/clear`; use application context; move cache cleanup off the main thread and be precise in docs; select the correct network interface (prefer Wi-Fi/Ethernet, ignore VPN/p2p), observe connectivity, show a warning for `0.0.0.0`, make the port configurable with fallback; pass status to `StartScreen`; guard start; add lifecycle/state persistence.

### P1-9 — Manifest/resource issues

- `ACCESS_WIFI_STATE` causes an implied **required** `android.hardware.wifi` feature (confirmed by `aapt2 dump badging`). Android TV devices with Ethernet only may be filtered out. Add `<uses-feature android:name="android.hardware.wifi" android:required="false" />` and remove unused `ACCESS_WIFI_STATE`/`ACCESS_NETWORK_STATE` if not needed.
- `mipmap-anydpi-v26/ic_launcher.xml` is the only `ic_launcher`; API 21-25 has no default density-bucket icon, so the launcher icon is missing/blank on those versions. Add `mipmap-anydpi`/density fallbacks or raise minSdk.
- `android:banner="@drawable/banner"` is a solid-color shape with no logo/text; the TV launcher tile is blank. Add a real 320x180 banner.
- Missing `monochrome` adaptive-icon layer; lint `MonochromeLauncherIcon`.
- `android:allowBackup="false"` is deprecated on API 31+; add `dataExtractionRules`/`fullBackupContent` if needed.
- `android:configChanges` omits `uiMode`, `keyboard`, `navigation`, `locale`, `layoutDirection`; activity recreation can reset state.
- `android:usesCleartextTraffic="true"` is global; scope it with a network security config.
- `android:screenOrientation="landscape"` is discouraged on Android 16+; acceptable for TV but note it.
- `REQUEST_INSTALL_PACKAGES` needs a clear justification; if auto-install is removed, only the in-app updater needs it.
- `file_paths.xml` includes `external-cache-path` but the app never uses external cache; harmless but unnecessary.

### P1-10 — Build, CI, and release hygiene

- CI does **not** run `testDebugUnitTest` or `lintDebug`; the lint failures are therefore invisible. Add both as required checks, fix the Media3 opt-in errors with `@OptIn(UnstableApi::class)` or a lint baseline.
- CI installs `build-tools;34.0.0`, but AGP 8.13.1's documented default/minimum is Build Tools 35.0.0; local builds use 35/36. Pin 35.0.0 (or 36.0.0) to avoid CI SDK auto-download/failure.
- CI runs the release job on every push to `main`/`master` and on every PR, creating draft `v0.0.<run>` releases and requiring `contents: write`. PRs from forks will have a read-only token and can fail. Split build/test from release; only release on `v*` tags; add `concurrency` and a job timeout.
- CI has no signing secrets and no versionCode/versionName injection; see P0-5.
- `release` uses `isMinifyEnabled = false` and debug signing; no R8/resource shrinking. Not a blocker, but the APK is 9.5 MB and the debug key is not production-grade.
- `compileSdk`/`targetSdk` 34 are behind current (36); lint `OldTargetApi`/`GradleDependency`.
- Dependencies are old: Compose BOM 2024.10.01, Media3 1.4.1, activity-compose 1.9.3, lifecycle 2.8.6, core-ktx 1.13.1, coroutines 1.9.0, zxing 3.5.3, tv-material 1.0.0. Some may have security/bug fixes; review and upgrade.
- `androidx.tv:tv-foundation` and `androidx.tv:tv-material` are unused; `lifecycle-viewmodel-compose` is unused; `nanohttpd-apache-fileupload` may be unused (NanoHTTPD core handles multipart); `androidTestImplementation` has no tests. Remove unused dependencies.
- `kotlinOptions` is deprecated in Kotlin 2.0; migrate to `compilerOptions`.
- `org.gradle.jvmargs`/add-opens are tuned for JDK 25; CI uses JDK 17. Not a bug, but document the supported JDK matrix.
- `.gitignore` lacks `*.jks`, `*.keystore`, `.kotlin/`, `captures/`; add them before introducing signing files.

### P1-11 — Documentation and repository hygiene

- `README.md:48` opens a code fence for the module tree and **never closes it before `## Tech stack`**; the closing fence is at `:131`. As a result, most of the README renders as a code block.
- `README.md:26` and `:98` say port **8080**; the app uses **7071** (`MainActivity.kt:61`, `MediaServer.kt:14`). The `curl` example will fail.
- `README.md:103` says "`HomeScreen` URL field uses the system IME"; there is no URL field on the TV Home screen; URL entry is in the web page.
- `README.md:111` says "No automated tests yet"; `app/src/test/.../FileUtilsTest.kt` exists and passes.
- `README.md:134` says artifacts are `app-release-unsigned`; CI uploads `app-release`.
- `README.md:6` / `:106` claims uploads are wiped "on exit/crash"; `onDestroy` is not guaranteed on process death/force-stop. Only the next launch wipes cache.
- `README.md:26` says "LAN only"; the server has no auth and binds all interfaces.
- The module tree omits `media/LiveAudio.kt`, `ui/UpdateDialog.kt`, `util/QrCodeUtils.kt`, `util/UpdateManager.kt`, and others.
- The troubleshooting note about `AppContentType` is stale; the code uses fully qualified names.
- No API endpoint documentation, no browser/TV compatibility matrix, no screenshots, no audio-sync limitations, no APK auto-install security note.
- `CHANGELOG.md` only says "Initial open-source release" despite many features/fixes.
- `SECURITY.md` asks for private reports but gives no contact address/link.
- `CONTRIBUTING.md` and the PR template do not require tests/lint; add `./gradlew testDebugUnitTest lintDebug`.
- CI section says Debug and Release are built, but CI only builds Release APK/AAB; update or add a debug job.

---

## 3. Medium/low/tiny details

### Server / API

- `MediaServer.serve()` returns `Error: ${e.message}` to clients, leaking internal paths/details. Return a generic error and log details.
- `serveLanding()` uses `newFixedLengthResponse(..., "text/html", html)`. NanoHTTPD's `ContentType` defaults to **US-ASCII** when no charset is supplied; the em dash in `- audio sync -` (`MediaServer.kt:161`) will be replaced by `?`. Use `"text/html; charset=utf-8"` and `"application/json; charset=utf-8"`.
- `serveNowPlaying()`/`servePdfState()`/`serveVideoState()`/`serveFiles()` manually build JSON. Names are generated so it is currently safe, but any future original filename/URL with quotes/backslashes will break it. Use a JSON writer.
- `serveFiles()` only strips `"`, not `\`, control characters, or invalid UTF-8; future filenames can break JSON.
- `handleUpload()` uses `files["file"] ?: files.values.firstOrNull()`; a form with no file but another field can produce a bogus empty upload.
- `handleUpload()` accepts an empty file: `detectType()` returns UNKNOWN, `FileInputStream.read(hb)` returns -1, and it saves an empty `.bin` as "File shared".
- `handleUpload()` adds to `uploadedFiles` before `onUpload`; if the callback throws, the file is listed but may not be usable.
- `handleUpload()` deletes the temp file only on some paths; copy exceptions leave both temp and partial output.
- `handleUrl()` only checks `^https?://.+`; it accepts localhost/private/link-local/URLs with credentials. That may be intended for projection, but it allows loading router/admin/internal pages in the TV WebView. Add an optional host policy or warning.
- `handleListen()` sets `listenerActive = start` before `onListenChanged`; if the callback throws, the flag is already changed. `listenerActive` is never read anyway.
- `serveAudioLive()` blocks a NanoHTTPD worker in `q.take()` indefinitely when no audio is available; use `poll(timeout)` and send silence, or remove the endpoint.
- `wavHeader()` declares `0x7FFFFFFF` for RIFF/data sizes; some browsers may reject an "infinite" WAV. If kept, use a streaming-friendly format or chunked media source.
- `AudioBroadcaster.push()` copies every audio buffer even when there are zero clients; skip work when `clients.isEmpty()`.
- `AudioBroadcaster.register()` has no client cap; many `/audio/live` clients can exhaust threads/memory. `clients` queues are 16 chunks; slow clients drop audio.
- `serveMedia()` has no `Cache-Control: no-store`, `ETag`, `Last-Modified`, or `Content-Disposition`; temporary files can be cached by the browser.
- `serveLanding()` has no `Cache-Control`, CSP, `X-Content-Type-Options`, or `Referrer-Policy`. Add security headers; avoid `innerHTML` for file lists (use DOM nodes/textContent) even though names are currently safe.
- No `HEAD` support for `/media/`; some clients may issue HEAD.
- No `OPTIONS`/CORS preflight handling; if cross-origin is ever needed, implement it deliberately.
- No rate limiting/connection cap; NanoHTTPD's default async runner can be exhausted by many concurrent uploads/streams.
- The server binds all interfaces; "LAN only" is not enforced. Use a token and optionally a `ServerSocketFactory`/bind address.
- The landing page has no upload progress, no delete/clear, no current-content indicator, no video progress bar, no PDF page indicator (the `/pdf/state` endpoint exists but the UI never uses it), and no "stop server" control.
- The video panel's "Listen" button has no active state; clicking twice silently does nothing; `stopBtn` does not restore TV volume if `/listen/stop` fails.
- `videoCmd`/`pdfCmd` always show "Video: action"/"PDF: action" even on HTTP 500; they ignore the response body/status.
- `syncTimer` async callbacks can overlap if a fetch takes >100 ms; guard with a busy flag.
- `refreshFiles()` uses `innerHTML`; safe today, fragile if filenames become user-controlled.
- `audio.currentTime` is set immediately after `load()`; can throw/behave inconsistently before metadata.
- `audio.play()` failures are swallowed; no user-facing message for autoplay blocking.

### MainActivity / state

- `Content.Error` (`MainActivity.kt:39`) is effectively unreachable: `when (uploaded.type)` covers all five enum values plus `else`, but `detectType()` never returns URL and UNKNOWN is handled by the "File shared" branch. Unsupported files never show `ErrorScreen`.
- `onUpload` status is set to `"Received: ..."` before the `when`; for non-APK unknown files it becomes `"File shared: ..."` and the TV stays on the previous screen with no visible confirmation.
- `currentPdfFile`/`currentVideoFile` are only deleted on replacement, not on Back; old files remain in cache until app exit. Images are never deleted.
- `imageFiles` is never cleared; every image upload for the session accumulates, including after returning Home. This may be intended ("images accumulate"), but there is no way to clear the list.
- `pdfPage` is global even when no PDF is displayed; remote PDF commands can mutate it while on Home/other content. `pdfPageCount` remains from the last PDF.
- `server?.pdfPageCount ?: Int.MAX_VALUE` at `MainActivity.kt:141`: if the server is null, `next` can increment indefinitely. In practice the server is non-null after start, but the fallback is misleading.
- `server = s` is assigned before `s.start()`; if start fails, `server` is non-null but not running. The next Start tap constructs another instance. The old instance may hold a partially bound socket.
- `serverStarted` is set only after a successful start; `status` is not passed to `StartScreen`, so bind failures are invisible.
- The 100 ms `LaunchedEffect(content)` loop runs for all content types, not just video; it checks `is Video` each tick. It also continues while the app is backgrounded (composition remains), using battery.
- `videoDurationMs` can be `C.TIME_UNSET` (`Long.MIN_VALUE + 1`), which would be serialized as a huge negative number if `/nowplaying` ever includes duration.
- `currentPlaying` is not updated by the polling loop; only the (broken) listener callback and video command handler update it. A video can be playing while `/nowplaying.playing` is false.
- `onVideoCommand` sets `server.videoEventId` on every command, even invalid actions; harmless but noisy.
- `onVideoCommand` `seekfwd` does not clamp to duration; ExoPlayer clamps, but the server state may briefly be ahead.
- `onPdfCommand`/`onVideoCommand` use `runOnUiThread`, which is correct; `onListenChanged` does not (P0-6).
- `onUpload` uses `runOnUiThread`; if the activity is destroyed, the runnable may still execute against stale state. Minor.
- `App.clearCache` is called in `App.onCreate`, `MainActivity.onCreate`, and `MainActivity.onDestroy`; redundant and main-thread.
- `MainActivity` does not pause playback in `onPause`/`onStop`; pressing Home can leave audio playing.
- No `onSaveInstanceState`/`rememberSaveable`; process death resets to StartScreen while the old server may still be running.
- No `onBackPressedDispatcher`/`BackHandler`; see P1-2.
- `qrBitmap` is never recycled; one 512x512 bitmap per activity, minor.
- `updateInfo` dialog has no progress/error state; tapping Update makes the dialog disappear and the app appears idle during download.

### UI / Compose

- `PdfViewerScreen.kt:26`: `remember { PdfRendererWrapper(file) }` opens a file in composition; this is a side effect and can throw. Use `produceState`/`DisposableEffect` with error handling.
- `PdfViewerScreen.kt:31-33`: `renderPage` on main thread; no `try/catch`; no bitmap recycle.
- `PdfViewerScreen.kt:59-64`: white page label on a white PDF page is invisible; add a scrim/background.
- `PdfViewerScreen.kt:32`: fixed 1920 px width; 4K TVs see upscaled content. Use actual display width and a safe max bitmap size.
- `PdfViewerScreen.kt:43-49`: `pageIndex` and `renderer.pageCount` can disagree if a remote command changes page while the renderer is stale; key the screen by file.
- `ImageSlideshowScreen.kt:20-29`: main-thread decode; no downsampling; no EXIF rotation; no null/error handling; old bitmaps are not recycled on index change (only on dispose).
- `ImageSlideshowScreen.kt:49-50`: white "1 / N" label over bright images; add a scrim.
- `VideoPlayerScreen.kt:15-20`: `remember` + `DisposableEffect(path)` creates a new player but `AndroidView` keeps the old one; no `update` block. Use `key(path)`/`update`.
- `VideoPlayerScreen.kt:22-31`: `useController = true` shows the touch-oriented PlayerView controller; no custom TV focus treatment.
- `VideoPlayerScreen.kt:35-39`: Back is handled on the wrapper, but PlayerView may consume it; use `BackHandler`.
- `WebViewScreen.kt:14-22`: no update/destroy/chrome client/error handling; see P1-5.
- `WebViewScreen.kt:8`: `LocalContext` import is unused.
- `StartScreen.kt`: no status/error display, no focus request, no loading state, all text hardcoded.
- `HomeScreen.kt`: no focusable actions, no current-content/status detail, no warning for `0.0.0.0`, no stop/restart, no version, no QR retry.
- `ErrorScreen.kt`: no `BackHandler`/focus request; Back may exit the app; message may be long with no scroll.
- `UpdateDialog.kt`: no progress/error/skip; focus behavior on TV not explicitly managed.
- All UI strings are hardcoded in Kotlin; `strings.xml` only has `app_name`. Add string resources/localization.
- No `contentDescription` on PDF/image content (some are null); QR has one. Add accessibility labels for remote controls.
- No explicit TV focus indicators/scale; Material3 phone components are used despite `tv-material` being a dependency. Consider `androidx.tv:tv-material` components or custom focus modifiers.
- No overscan safe-area handling; TV bezels can clip the PDF page label and Home text. Use window insets/5% padding.
- No landscape/portrait adaptation beyond fixed landscape; TV is landscape, but large-screen/4K density differences remain.
- `themes.xml` uses `android:Theme.Material.NoActionBar`; Compose draws its own theme. Fine, but no TV-specific theme.

### Util / model / resources

- `FileUtils.kt` is production-dead; only `FileUtilsTest` uses it. Either use it for extension/MIME detection or remove it and its tests. `typeFromMime("text/*") -> URL` is wrong.
- `UploadedFile.id`, `size`, and `mimeType` are never read; `id` is unused. Add `originalName` if preserving filenames.
- `App.instance` and `App.cacheDir()` are unused; remove or use.
- `QrCodeUtils.generate()` runs on the main thread and uses `setPixel` in a 512x512 loop; acceptable but can be moved/optimized with a `createBitmap` + `setPixels`.
- `NetworkUtils.getIpAddress(context)` ignores `context`; no `ConnectivityManager`, no IPv6, no VPN/p2p filtering, no network-change callback.
- `UpdateManager` uses `api.github.com` without a `User-Agent`; Java's default usually works, but set one explicitly.
- `UpdateManager.checkLatest()` does not close the `HttpURLConnection`/reader explicitly; `download()` does not call `disconnect()`.
- `UpdateManager.download()` writes to a fixed `cacheDir/update.apk`; concurrent downloads can collide.
- `UpdateManager` has no APK checksum/signature verification before calling the installer.
- `UpdateManager.isUpdateAvailable()` defaults to `v1.0`; CI tags are `v0.0.N`; this can offer a downgrade on first launch. Use `BuildConfig.VERSION_CODE`/semver.
- `PdfRendererWrapper.close()` is not synchronized; if called while rendering, behavior is undefined. Render on a single dispatcher and cancel safely.
- `PdfRendererWrapper.renderPage()` ignores the `height` parameter when `width=0` and can allocate very tall bitmaps for extreme page aspect ratios. Cap total pixels.
- `LiveAudio.kt` is unused by the current UI but still compiled and linted; remove or finish.
- `AudioProcessor` methods need `@OptIn(UnstableApi::class)`; otherwise lint fails.
- `MediaManager` needs `@OptIn(UnstableApi::class)` for `DefaultRenderersFactory`, `DefaultAudioSink`, `AudioSink`, `onPositionDiscontinuity`.
- `MediaManager.createPlayer()` adds a listener every time; if called twice without release, old listeners/players can leak.
- `MediaManager.playFile()` uses `MediaItem.fromUri("file://$path")`; paths with spaces/# need encoding. Use `Uri.fromFile(file)`.
- `MediaManager.release()` does not clear `onStateChanged`; set it to null.
- `MediaServer` companion `URL_PATTERN` is fine; `MAX_UPLOAD_BYTES` is 2 GB; `TAG` logging is debug-level.
- `MediaServer` `uploadedFiles` is public and mutable; make it private with accessor methods.
- `MediaServer` `currentFile` is public; make state access explicit.
- `MediaServer` `serve()` logs every request at debug; if a token is added, ensure it is not logged.
- `MediaServer` route matching uses `uri ==` / `startsWith`; a trailing slash (`/upload/`) 404s. Fine, but document exact routes.
- `MediaServer` has no `/health`, `/version`, `/stop`, `/delete`, `/clear` endpoints. Add if useful for ops/UX.
- `MediaServer` has no `Content-Security-Policy`; the landing page is inline JS/CSS. Add a CSP or move assets to files.
- `MediaServer` `serveLanding()` HTML has odd indentation around the Files/Video panels (`:142-147`); cosmetic but makes maintenance harder.
- `MediaServer` `serveLanding()` uses `backdrop-filter`, which may not work on older smart-TV browsers; fallback is a translucent card.
- `MediaServer` `serveLanding()` uses `<div class="mode">` not `<button>`; not keyboard accessible in the phone browser. Use buttons.
- `MediaServer` `serveLanding()` `<input type="file" required>` with no `accept`; intentional for any file, but the hint says "PDF, video, image" while APK/any file is accepted. Align copy.
- `MediaServer` `serveLanding()` `pattern="https?://.+"` may need escaping under the HTML `v` flag in some browsers; server-side validation is the real gate.
- `MediaServer` `serveLanding()` `fetch('/upload')` has no progress; use XHR or a progress-capable uploader.
- `MediaServer` `serveLanding()` `refreshFiles()` uses `innerHTML`; use DOM APIs.
- `MediaServer` `serveLanding()` has `lang="en"`; fine.
- `MediaServer` `serveLanding()` footer uses `&middot;`; safe ASCII.

### Build / CI / docs / repo

- `README.md` code fence, port, tests, artifact name, URL-field claim, LAN-only claim, crash-wipe claim, module tree, stale `AppContentType` note.
- `CHANGELOG.md` not maintained.
- `SECURITY.md` no contact.
- `CONTRIBUTING.md`/PR template no test/lint requirement.
- CI no test/lint, no signing, no version injection, release on PRs, `contents: write`, no concurrency/timeout, `fetch-depth: 0` unnecessary, build-tools 34 vs AGP 8.13 default 35.
- `.gitignore` no `*.jks`/`*.keystore`/`.kotlin/`/`captures/`.
- `gradle.properties` `kotlin.daemon.enabled=false` slows builds; CI uses JDK 17 where the JDK 25 workaround is unnecessary. Document.
- `build.gradle.kts` `kotlinOptions` deprecated; `targetSdk`/`compileSdk` old; `isMinifyEnabled=false`; debug signing.
- `proguard-rules.pro` keeps NanoHTTPD; if minify is enabled later, verify all reflection-based code.
- `androidTestImplementation` deps unused; no `androidTest` sources.
- `tv-material`/`tv-foundation` unused; `lifecycle-viewmodel-compose` unused; `nanohttpd-apache-fileupload` likely unused.
- `README` says "Gradle 9.3.1" correctly; wrapper matches.
- `README` says JDK 25; CI uses JDK 17. Fine, but clarify supported matrix.
- No screenshots/GIF; no API docs; no browser/TV compatibility matrix; no troubleshooting for port 7071/audio sync.
- No release/versioning policy; no semantic version.
- No dependency update automation (Dependabot/Renovate).
- No `LICENSE` headers needed for MIT, but third-party notices for NanoHTTPD/zxing/Media3 may be appropriate.
- No `CODEOWNERS`, no issue config; optional.
- `.opencode/` is ignored by an uncommitted `.gitignore` change; not a product issue.

---

## 4. Proper plan (no changes yet)

### Phase 0 — Security and release blockers (must ship before any new release)

1. **Pairing/auth**
   - Generate a random token on server start; include it in the QR/URL.
   - Require it on every endpoint (header or path); constant-time compare.
   - Remove `Access-Control-Allow-Origin: *`; same-origin only.
   - Add CSRF protection for POST endpoints.
   - Optionally bind/allowlist local clients.
2. **Path confinement**
   - Canonicalize `/media/` names; reject `..`, separators, NUL; allowlist `uploadedFiles`.
   - Add traversal tests (raw, `%2F`, `%2e%2e`, double-encoded).
3. **Upload hardening**
   - Reject `Content-Length` above a configurable cap before `parseBody`.
   - Stream with a hard limit; check free space; clean temp/partial files on all paths.
   - Lower the default cap; document it.
4. **APK sharing**
   - Remove auto-install from unauthenticated uploads.
   - If kept, require TV confirmation and validate APK contents/signature; do not treat every ZIP as APK.
5. **Release signing/versioning**
   - Add a real release keystore via GitHub secrets.
   - Derive monotonic `versionCode`/`versionName` from the tag.
   - Verify signer in CI.
6. **Thread affinity**
   - Move volume/callback setup to the main thread.
   - Install `onStateChanged` once in `MediaManager.createPlayer()`.
   - Auto-unmute when no listeners remain.
7. **CI gates**
   - Run `testDebugUnitTest` and `lintDebug`; fix `@OptIn(UnstableApi::class)` or add a baseline.
   - Only release on `v*` tags; separate PR builds.
   - Pin Build Tools 35.0.0/36.0.0; add concurrency/timeouts.

### Phase 1 — Correctness and state

1. Key PDF/video/WebView screens by file/URL; add `AndroidView(update=...)`; destroy WebView; reset page/index/position.
2. Add `BackHandler` and `FocusRequester` to every screen; WebView history back; video pause/stop on Back.
3. Clear `currentFile`/playback state on Back/content change; stop audio sync; make `/nowplaying`/`/video/state` consistent or remove dead endpoints.
4. Move cache cleanup off the main thread; use application context in `MediaServer`; fix `uploadedFiles` thread safety and stale entries; add `/delete`/`/clear` and delete files with records.
5. Pass status/errors to `StartScreen`; guard double-start; configurable port with fallback; show `0.0.0.0` warning; observe network changes.
6. Add `onPause`/`onStop` lifecycle handling; `rememberSaveable`/ViewModel for process death; pause video when backgrounded.

### Phase 2 — Media pipeline

1. PDF/image decode/render off-main; error states; bitmap downsampling/recycle; EXIF rotation; cap bitmap size; render at actual screen width; add scrim for labels; add zoom/pan or external fallback.
2. Implement correct Range parsing and fixed-length responses; 416 for invalid ranges; `HEAD`; correct MIME by sniffing; preserve original filenames/extensions; add HEIC/BMP/MPEG-TS/audio handling or explicit rejection.
3. Audio sync: unlock audio in click handler; continuous drift correction; latency handling; multiple-client heartbeat; auto-unmute; `pagehide` cleanup; decide `/audio/live` vs `/media`; fix MIME/format support.
4. Video: error/buffering UI, audio focus, lifecycle pause, player update per path, clamp seeks.
5. WebView: update/destroy, `WebChromeClient`, scheme allowlist, disable file access, error/progress, Back history, safe browsing.

### Phase 3 — Quality, CI, docs

1. Remove dead code/deps (`LiveAudio` if unused, `FileUtils`, unused tv/lifecycle deps, dead fields/endpoints).
2. Upgrade `targetSdk`/`compileSdk`/dependencies; review Media3/Compose/NanoHTTPD security fixes.
3. Add unit tests for `detectType`, path sanitization, Range parsing, URL validation, version comparison, JSON escaping.
4. Add Compose/instrumentation tests for screen replacement, Back, focus, upload, playback.
5. Fix README (fence, port, tests, artifacts, URL-field claim, LAN-only, crash-wipe, module tree), CHANGELOG, SECURITY contact, CONTRIBUTING/PR test/lint.
6. Document API endpoints, browser/TV support, audio-sync limitations, APK security, and release/versioning process.

### Phase 4 — TV UX/accessibility

1. Focus indicators/initial focus, D-pad tests, Back behavior, overscan padding.
2. Status/progress/delete/clear/current-content UI; original filenames; no "Loading..." forever.
3. Localization/string resources; content descriptions; TV banner; proper launcher icon for all API levels; monochrome layer.
4. Consider `tv-material` components or custom focus styling; verify on 1080p/4K and slow TVs.

### Phase 5 — Verification before release

1. `./gradlew testDebugUnitTest lintDebug assembleRelease` all pass.
2. Security tests: no-token/wrong-token, traversal variants, CSRF, oversized upload, APK/ZIP confusion, CORS.
3. Functional tests: upload PDF/video/image/URL twice while viewing; Back on every screen; audio sync start/stop/client-close; port busy; `0.0.0.0`; network change; process death.
4. Browser matrix: Chrome Android, Safari iOS, Firefox, smart-TV browser; autoplay/audio sync; Range seeking.
5. TV matrix: D-pad remote, 1080p/4K, Ethernet-only, slow device; long-run soak for cache/memory/threads.
6. Release test: install two consecutive CI APKs over each other; verify signer/versionCode; update flow success/cancel/failure.

---

## 5. Suggested priority order

1. **P0-1 -> P0-2 -> P0-3 -> P0-4** (security hotfix; do not expose the server until done).
2. **P0-5 -> P0-6** (release and listen/unmute).
3. **P0-7 -> P0-8 -> P1-2** (core viewer/remote correctness).
4. **P1-1, P1-7, P1-8** (update, range/MIME, state/file lifecycle).
5. **P1-3, P1-4, P1-5, P1-6** (PDF/video/WebView/audio quality).
6. **P1-9, P1-10, P1-11** (manifest, CI, docs).
7. **Phase 4 UX/accessibility** and long-run testing.

---

## 6. Bottom line

The project builds and its unit tests pass, but it is **not ready for a public/untrusted LAN or a new release** until the unauthenticated control plane, path traversal, upload DoS, APK auto-install, debug signing/versioning, wrong-thread ExoPlayer access, and stale viewer/playback state are fixed. The lint failure is currently invisible to CI, and the README contains several claims that no longer match the code.

No app source was changed during this QA.

---

## 7. Phase 0 implementation status (2026-11-20)

Phase 0 blockers were implemented after the QA report.

### Implemented

- **Pairing token / auth (P0-1)**
  - `MediaServer` generates a 128-bit random token per launch.
  - All routes require the token as the first path segment: `/<token>/...`.
  - Requests without a valid token return `401`.
  - Landing page uses relative URLs (`upload`, `url`, `files`, `media/...`, `video/command`, etc.) so the token prefix is preserved.
  - Wildcard CORS headers removed; same-origin only.
  - `Referrer-Policy: no-referrer` and `Cache-Control: no-store` on the landing page.
  - `pagehide` handler stops audio sync and calls `/listen/stop`.
- **Path traversal (P0-2)**
  - Added `util/PathUtils.isSafeMediaName()`.
  - `/media/` rejects empty names, `/`, `\`, NUL, and any `..`.
  - Resolves canonical file and requires `parentFile == cacheDir`.
  - Requires the name to be present in the server's uploaded-file allowlist.
  - Added `PathUtilsTest` with raw/encoded/separator cases.
- **Upload limits / cleanup (P0-3)**
  - `MAX_UPLOAD_BYTES` reduced from 2 GB to 512 MB.
  - `Content-Length` is checked before `parseBody()`.
  - Free-space check with 64 MB headroom before parsing.
  - Temp and partial output files are deleted on copy/parse failures.
  - UI hint updated to 512 MB.
- **APK auto-install removal (P0-4)**
  - Removed `ACTION_VIEW`/`REQUEST_INSTALL_PACKAGES` auto-install from upload handling.
  - Unknown files are saved as `.bin` downloads only; ZIP `PK` header no longer implies APK.
- **Release signing / versioning (P0-5)**
  - `app/build.gradle.kts` uses `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` when present; otherwise falls back to debug signing for local builds.
  - `versionCode`/`versionName` read from `VERSION_CODE`/`VERSION_NAME` env.
  - CI prepares a release keystore from `KEYSTORE_BASE64` for `v*` tags and fails if signing secrets are missing.
  - CI verifies the APK signer on tag builds.
  - Verified locally with a temporary QA keystore: `versionCode=1002003`, `versionName=1.2.3`, signer `CN=QA Release`.
- **Main-thread ExoPlayer access (P0-6)**
  - `/listen/start` and `/listen/stop` volume changes now run via `runOnUiThread`.
  - `mediaManager.onStateChanged` is installed once when the server starts, not inside the listen callback.
- **CI gates (P1-10 partial)**
  - CI runs `testDebugUnitTest` and `lintDebug`.
  - Build tools pinned to 35.0.0 (AGP 8.13.1 default).
  - Release job only runs on `v*` tags; PR/push builds produce artifacts only.
  - Added concurrency and a 30-minute timeout.
- **Lint**
  - Added `@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)` to `LiveAudio.kt` and `MediaManager.kt`.
  - `lintDebug` now reports **0 errors**, 25 warnings, 2 hints.
- **Docs**
  - README port updated from 8080 to 7071 and pairing-token URL documented.

### Verification

- `./gradlew testDebugUnitTest lintDebug` -> **PASS** (7 tests, 0 lint errors).
- `./gradlew assembleDebug assembleRelease` -> **PASS**.
- Release signing smoke test with a temporary keystore -> **PASS**.

### Remaining Phase 0 caveats

- Chunked upload requests without `Content-Length` are rejected with `411`, so the pre-parse size cap cannot be bypassed this way.
- Runtime auth/path tests require a device/emulator or a JVM-hosted NanoHTTPD harness; this implementation was verified by unit tests and code/bytecode inspection, not on a physical TV.
- `REQUEST_INSTALL_PACKAGES` remains in the manifest because the in-app updater still uses it; the upload auto-install path is removed.
- The `.dev/` directory is ignored by `.gitignore`, so this report is local-only unless force-added.

No further source changes were made after this Phase 0 pass.
---

## 8. Phase 1 implementation status (2026-11-20, partial)

Phase 1 was started after Phase 0. The following correctness/state items are implemented.

### Implemented

- **Stale viewer replacement (P0-7)**
  - `MainActivity` now keys PDF, video, and URL screens by file/path/URL:
    - `key(c.file)` for PDF
    - `key(c.file.absolutePath)` for video
    - `key(c.url)` for WebView
  - `PdfViewerScreen` now uses `remember(file)` and `DisposableEffect(renderer)`, so a new PDF creates/closes a new renderer.
  - `WebViewScreen` now destroys the WebView in `onDispose`.
  - This prevents a second upload/URL from leaving the old renderer/player/WebView attached.
- **Stale playback state (P0-8, partial)**
  - `LaunchedEffect(content)` now clears `currentFile`, `currentPlaying`, `currentPositionMs`, `videoDurationMs`, and bumps `videoEventId` whenever content is not video.
  - The 100 ms position loop now runs only while content is video, reducing background work.
  - `BackHandler` in `MainActivity` clears the same state immediately when leaving a content screen.
  - The web dashboard now pauses audio and resets `currentFile` when `/nowplaying` reports no file.
- **TV Back handling (P1-2, partial)**
  - Added `BackHandler` at the activity level for all content screens.
  - WebView has its own `BackHandler` enabled only when `WebView.canGoBack()` is true; otherwise the activity handler returns Home.
  - WebView no longer consumes Back directly, so history back works before exiting the viewer.
  - PDF/image/Start/Error screens request focus on first composition.
- **Lifecycle**
  - `MainActivity.onPause()` now pauses the ExoPlayer.
  - `MediaServer` now receives `applicationContext` instead of the Activity.
- **WebView cleanup**
  - `onDispose { webView.destroy() }` added.
  - `allowFileAccess = false` set.

### Verification

- `./gradlew testDebugUnitTest lintDebug assembleDebug` -> **PASS**.
- `./gradlew assembleRelease` -> **PASS** (after a local Kotlin/JDK 25 incremental-cache retry).
- `lintDebug` -> **0 errors**, 25 warnings, 2 hints.
- Unit tests -> 7/7 pass.

### Still open in Phase 1

- Port is still fixed at 7071; no fallback.
- No network-change receiver; IP/QR can still go stale if Wi-Fi/Ethernet changes.
- Cache clearing is still on the main thread.
- No `rememberSaveable`/ViewModel for process death.
- Video focus/DPAD behavior still needs on-device verification.
- `PdfRendererWrapper` still has no error state for corrupt/encrypted PDFs; that is Phase 2 (P1-3).
- Back behavior for PlayerView needs a physical remote test.
- The image list still accumulates for the session until deleted via the Files tab; it is no longer impossible to clear.

### Phase 1 continued (same day)

- **File management**
  - Added `POST /files/delete` and `POST /files/clear` (token-protected).
  - Added `MediaServer.onFilesChanged` callback.
  - `MainActivity` now reacts to deletions by:
    - closing PDF/video viewers if the displayed file is gone;
    - removing deleted images from the slideshow and going Home if none remain;
    - clearing stale `currentPdfFile`/`currentVideoFile` references.
  - Files tab now has per-file Delete buttons and a Clear all button.
- **WebView URL policy**
  - Added `shouldOverrideUrlLoading` for both modern and legacy APIs.
  - Non-`http(s)` URLs are blocked.
- **State API cleanup**
  - `/video/state` now reports `currentPositionMs`, `videoDurationMs`, `currentPlaying`, and `videoEventId`.
  - Removed dead `videoPositionMs`, `videoPlaying`, and `listenerActive` fields.
- **Verification**
  - `./gradlew testDebugUnitTest lintDebug assembleDebug` -> PASS.
  - `./gradlew assembleRelease` -> PASS (second run; local Kotlin/JDK 25 incremental cache needed a retry).
  - Lint -> 0 errors, 25 warnings, 2 hints.
  - Unit tests -> 7/7.

---

## 9. Phase 2-4 implementation status (2026-11-20)

### Phase 2 — media pipeline

- **PDF**
  - Render/decode moved off the main thread.
  - Renderer creation wrapped in `runCatching`; corrupt/encrypted PDFs show an error instead of crashing.
  - `PdfRendererWrapper.renderPage()` uses `try/finally`, caps bitmap size, and closes pages.
  - Render width now derives from the actual display width.
  - Page label has a dark scrim for contrast.
- **Images**
  - Decoding moved off the main thread.
  - Downsampling via `inSampleSize`.
  - EXIF rotation via `android.media.ExifInterface`.
  - Null/corrupt images show an error instead of “Loading...” forever.
  - Bottom label has a dark scrim.
- **Range / MIME**
  - Range parsing extracted to `RangeParser` with tests.
  - Fixed-length responses; no more chunked + `Content-Length`.
  - Correct `Content-Range`, `Accept-Ranges`, and 416 responses.
  - MIME/extension preservation by original extension for known types.
  - Added BMP, HEIC/AVIF, MPEG-TS detection; added GIF/BMP/WebP/HEIC/AVIF/TS/3GP/M4A MIME mappings.
- **Audio sync**
  - Drift correction now runs every poll, not only on `eventId` changes.
  - Added `syncBusy` guard against overlapping fetches.
  - Added `pagehide`/`visibility` cleanup and stop guards.
  - Added browser autoplay-blocked message and audio controls fallback.
  - Multiple listeners tracked with `listenerCount`; TV unmutes only when the last listener stops.
  - Removed the unused `/audio/live` / `AudioBroadcaster` / `LiveAudioProcessor` pipeline.
- **Video**
  - Added `Player.Listener` for loading/error/ended states.
  - Added error and buffering overlays.
  - Added ExoPlayer audio attributes with audio focus.
  - `createPlayer()` now returns the existing player if present.
  - `playFile()` uses `Uri.fromFile` to handle spaces/special characters.
  - Seek forward clamps to duration; duration uses a safe value instead of `C.TIME_UNSET`.
- **WebView**
  - Added `WebChromeClient` progress.
  - Added page start/finish/error overlays.
  - Added modern and legacy `shouldOverrideUrlLoading` scheme allowlist.
  - `allowFileAccess = false`.
  - WebView destroyed on dispose.
  - History-aware Back.

### Phase 3 — quality / cleanup

- Removed dead `LiveAudio.kt`, `FileUtils.kt`, `FileUtilsTest.kt`, unused TV/lifecycle dependencies, `nanohttpd-apache-fileupload`, and unused AndroidTest dependencies.
- Removed dead `videoPositionMs`, `videoPlaying`, `listenerActive`, `App.instance`, and `App.cacheDir()`.
- Added unit tests:
  - `PathUtilsTest`
  - `RangeParserTest`
  - `VersionUtilsTest`
  - `UrlUtilsTest`
  - `MediaTypeDetectorTest`
- CI now runs `testDebugUnitTest` and `lintDebug`.
- `lintDebug`: **0 errors**, 20 warnings, 2 hints.
- `assembleDebug` and `assembleRelease` pass.
- Release signing smoke test passes with a temporary keystore:
  - `versionCode=1002003`
  - `versionName=1.2.3`
  - signer `CN=QA Release`
- Updated README, CHANGELOG, SECURITY, CONTRIBUTING, PR template, CI workflow, and manifest resources.

### Phase 4 — TV UX / resources

- Added activity-level Back handling and WebView history back.
- Added focus requests for Start/PDF/images/Error.
- Added per-file Delete and Clear all in the Files tab.
- Added original display names for uploads.
- Added legacy launcher icon (`mipmap-anydpi`), TV banner layer-list, and monochrome adaptive icon.
- Removed unused `ACCESS_WIFI_STATE` and added `android.hardware.wifi` as not required for Ethernet-only TVs.
- Added `dataExtractionRules`.

### Phase 5 — verification

- `./gradlew testDebugUnitTest` -> PASS, 16 tests.
- `./gradlew lintDebug` -> PASS, 0 errors, 20 warnings.
- `./gradlew assembleDebug` -> PASS.
- `./gradlew assembleRelease` -> PASS.
- `git diff --check` -> clean.
- Release signer/version smoke test -> PASS.

### Not completed / requires devices or product decisions

- Chunked uploads without `Content-Length` are now rejected with `411`; this bypass is closed.

- No Compose/instrumentation tests (would require adding test deps and running on an emulator/device).
- No targetSdk/dependency major upgrade (kept compileSdk/targetSdk 34 and Compose BOM 2024.10.01 to avoid behavior/toolchain risk).
- No full localization/string-resource extraction; UI strings are still hardcoded.
- No `rememberSaveable`/ViewModel process-death state restoration.
- Cache clearing still runs on the main thread.

- WebView does not have a full Chrome feature set (downloads, file chooser, safe browsing toggle, user-agent tuning).
- Manual D-pad/Back, audio autoplay, browser matrix, 1080p/4K, Ethernet-only, and long-run soak testing still required.
- No commit was made.

### Final hardening pass

- Chunked `/upload` requests without `Content-Length` are rejected with `411`; the pre-parse size cap cannot be bypassed this way.
- `NetworkUtils` now prefers `wlan0`/`eth0`, ignores VPN/p2p/docker interfaces, skips link-local, and falls back to a site-local IPv4 address.
- Added `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and `Referrer-Policy: no-referrer` to the landing page; state/media responses are `no-store`.
- Added `HEAD` support for `/media/`.
- `pdfPage` and `status` use `rememberSaveable`; slideshow index is clamped when files are deleted.
- Video screen has its own `BackHandler`.
- WebView enables Safe Browsing on API 26+, disables content access, and keeps file access disabled.
- Final verification:
  - `testDebugUnitTest`: 16/16 pass.
  - `lintDebug`: 0 errors, 20 warnings, 2 hints.
  - `assembleDebug`: PASS.
  - `assembleRelease`: PASS; signed smoke test `1.2.3` / `1002003` / `CN=QA Release`.

### Localization / SDK / test additions

- Extracted the Compose UI strings into `res/values/strings.xml` and used `stringResource` in Start, Home, PDF, image, error, and update screens. Activity status/error strings use `getString`.
- Added a Compose instrumentation test for the Start screen (`app/src/androidTest/.../StartScreenTest.kt`) and the required Compose test dependencies.
- Upgraded `compileSdk`/`targetSdk` to **35**; CI installs `platforms;android-35`, build-tools 35.0.0.
- Replaced `android.media.ExifInterface` with AndroidX `androidx.exifinterface:exifinterface:1.3.7`.
- Final verification after these changes:
  - `testDebugUnitTest`: 16/16 pass.
  - `lintDebug`: 0 errors, 20 warnings, 2 hints.
  - `assembleDebug`: PASS.
  - `compileDebugAndroidTestKotlin`: PASS.
  - `assembleRelease`: PASS; `versionCode=1002003`, `versionName=1.2.3`, targetSdk 35, signer `CN=QA Release`.

### PDF zoom / pan

- Added pinch zoom, double-tap toggle, and DPAD-center zoom for PDF pages.
- Zoom is capped at 5x and resets on page change.
- Panning is bounded by `clipToBounds` and reset on page change.
- Final lint: 0 errors, 20 warnings, 2 hints.
- Updated `androidx.exifinterface` to 1.4.2, ZXing to 3.5.4, and AndroidX Test ext to 1.3.0; kept `annotation-experimental` at 1.4.1 because 1.6.0 requires minSdk 23.
