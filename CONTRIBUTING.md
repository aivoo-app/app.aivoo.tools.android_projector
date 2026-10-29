# Contributing

Thanks for your interest in contributing!

## Getting started
1. Fork the repo and clone it.
2. Open in Android Studio, sync Gradle.
3. Create a feature branch: `git checkout -b feature/my-change`.

## Build
```
./gradlew assembleDebug
```

## Guidelines
- Keep package boundaries (ui, server, media, pdf, model, util).
- All new TV screens must be fully D-pad navigable.
- Run `./gradlew assembleDebug` before opening a PR.
- Write/update tests where applicable.

## Pull requests
- Describe the change and motivation.
- Reference related issues.
- Keep PRs focused and small.
