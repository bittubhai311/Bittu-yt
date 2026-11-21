# App Info

**App Info** is a 100% local, privacy-focused native Android application built with **Kotlin**, **Jetpack Compose (Material 3)**, **Room SQLite Database**, and **WorkManager**. It scans installed applications via `PackageManager` and keeps a permanent on-device history of every app installation, version update, version downgrade, and uninstallation.

---

## Features

- **Installed App Inventory**: Lists all installed user and system apps with current `versionName`, `versionCode` (`longVersionCode`), install date, last update date, and version event badge count.
- **Permanent Version Changelog**: Detects and records `INSTALLED`, `UPDATED`, `DOWNGRADED`, and `UNINSTALLED` transitions in a local Room SQLite database (`version_tracker.db`).
- **App Detail Timeline**: Displays per-app metadata, chronological version timeline (`old -> new`), and shortcuts to launch the app or open Android System App Info.
- **Global History Feed**: Displays all version events grouped by date (`Today`, `Yesterday`, or `dd MMM yyyy`) with search and change-type filter chips.
- **Periodic Background Scanning**: Uses `WorkManager` (`PeriodicWorkRequest` at Android's 15-minute minimum interval with `ExistingPeriodicWorkPolicy.KEEP`) and posts grouped notifications on genuine updates or downgrades.
- **Single Local JSON Storage**: Automatically saves all tracked apps' names, metadata, and version history into one local JSON file (`Android/data/com.appinfo.android/files/app_info_data.json`) without creating per-app folders or requiring storage permissions.

---

## Tech Stack & Architecture

- **Language**: Kotlin (`2.2.10`)
- **UI**: Jetpack Compose + Material Design 3 + Dynamic Color (`compileSdk = 36`, `minSdk = 24`, `targetSdk = 36`)
- **Database**: Room (`2.7.0`) with KSP (`2.3.5`)
- **Background Work**: AndroidX WorkManager (`2.10.0`)
- **Navigation**: Jetpack Navigation Compose (`2.8.9`)
- **Build System**: Gradle `9.3.1` + Android Gradle Plugin `9.1.1` (Kotlin DSL)

---

## Building & Running the Project

### 1. Debug Keystore Setup (When Building Locally Outside AI Studio)

In Google AI Studio, `debug.keystore` is automatically decoded from `debug.keystore.base64` before each build. If you clone or export this repository to build locally in Android Studio or via CLI, decode `debug.keystore.base64` into `debug.keystore` at the project root if it does not already exist:

```bash
base64 -d debug.keystore.base64 > debug.keystore
```

### 2. Build Debug APK

Using the included Gradle Wrapper (or system `gradle`):

```bash
./gradlew assembleDebug
# Or in environments using system Gradle directly:
gradle assembleDebug
```

The compiled debug APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 3. Run Unit Tests

```bash
./gradlew :app:testDebugUnitTest
# Or with system Gradle:
gradle :app:testDebugUnitTest
```

### 4. Release Builds

Release signing in `app/build.gradle.kts` reads credentials from environment variables so no secrets are stored in source control:
- `KEYSTORE_PATH`: Path to your release `.jks` keystore
- `STORE_PASSWORD`: Keystore password
- `KEY_PASSWORD`: Key password (alias: `upload`)
