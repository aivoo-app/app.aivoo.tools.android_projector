# Changelog

## [Unreleased]

### Security
- Added a per-launch 128-bit pairing token required on every HTTP route.
- Removed wildcard CORS headers.
- Confined `/media/` to canonicalized, allow-listed upload names to prevent path traversal.
- Added pre-parse `Content-Length` checks, a 512 MB upload cap, free-space checks, and temp/partial cleanup.
- Removed APK auto-install from unauthenticated uploads; unknown files are downloads only.
- Blocked non-`http(s)` navigation in the WebView.

### Correctness
- Keyed PDF/video/WebView screens by file/URL so replacements render correctly.
- Added activity-level Back handling and WebView history back.
- Cleared video state when leaving a video or changing content.
- Added file delete/clear endpoints and UI.
- Added PDF/image off-main rendering, downsampling, EXIF rotation, error states, and contrast overlays.
- Improved Range parsing and fixed-length media responses.
- Improved media type detection (BMP, HEIC/AVIF, MPEG-TS) and MIME/extension handling.
- Added ExoPlayer audio attributes, error/buffering UI, and lifecycle pause.
- Added network-change IP/QR refresh and port fallback.

### Build / CI
- CI now runs unit tests and lint.
- CI uses Build Tools 35.0.0 and release signing secrets for tags.
- Version code/name derived from `vX.Y.Z` tags.
- Added unit tests for path safety, ranges, versions, URLs, and media sniffing.

### Cleanup
- Removed dead audio broadcast pipeline and unused dependencies.
- Removed dead file/state helpers and unused manifest fields.
- Added legacy launcher icon, TV banner, and monochrome adaptive icon.

## [1.0.0] - 2026-10-29
- Initial open-source release.