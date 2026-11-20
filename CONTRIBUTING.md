# Contributing

Thanks for your interest in contributing!

## Getting started
1. Fork the repo and clone it.
2. Open in Android Studio, sync Gradle.
3. Create a feature branch: `git checkout -b feature/my-change`.

## Build & test
```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

## Guidelines
- Keep package boundaries (`ui`, `server`, `media`, `pdf`, `model`, `util`).
- All new TV screens must be fully D-pad navigable and request focus on entry.
- Handle Back with `BackHandler`; WebView must traverse history first.
- Do not log pairing tokens or uploaded file contents.
- Keep `/media/` confined to canonicalized, allow-listed upload names.
- Do not auto-install APKs from uploads.
- Run the test/lint/build commands above before opening a PR.
- Write/update unit tests for pure logic (paths, ranges, versions, URLs, media sniffing).

## Pull requests
- Describe the change and motivation.
- Reference related issues.
- Keep PRs focused and small.
- Include test evidence for behavior changes.