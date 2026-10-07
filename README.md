# PhotoSweep

**Clean up your gallery and search the text inside your photos — entirely on your device.**

PhotoSweep scans your Android photo library, groups duplicate and near-duplicate shots,
flags blurry photos, screenshots and space-hogging files, and reads the text in every image
with on-device OCR so you can search your photos like documents.
No account, no cloud, no internet permission.

## Features

- 🔍 **Duplicate & similar photo detection** — perceptual hashing (dHash + aHash) combined
  with a color histogram; adjustable sensitivity slider
- ✅ **Auto-mark** — keeps the highest-resolution photo in each group and marks the rest
- 🗑️ **Safe deletion** — always goes through the system confirmation dialog
  (MediaStore delete request on Android 11+)
- 📝 **On-device OCR** — Google ML Kit text recognition, fully offline
- 🔎 **Text search** — type any words; matches photos whose text contains all of them,
  with context snippets ranked by relevance
- 📋 **Copy & export** — copy recognized text to the clipboard or export everything
  to a `.txt` file in Documents
- 🌫️ **Blurry photo finder** — Laplacian-variance sharpness score
- 📱 **Screenshot cleaner** — filter the gallery down to screenshots only
- 📦 **Large file finder** — the 50 biggest photos and how much space they take
- 🔒 **100% private** — no `INTERNET` permission in the manifest; nothing ever leaves your phone

## Tech stack

Kotlin · Jetpack Compose · Material 3 · ML Kit Text Recognition · Coil · Kotlin Coroutines

Single-activity, single-file architecture (`MainActivity.kt`) — no Room, Hilt, Navigation or ViewModel.
Min SDK 26 (Android 8.0) · Target SDK 34 · AGP 8.2.2 · Gradle 8.2 · JDK 17

## Build

1. Clone the repo and open it in Android Studio.
2. Let Gradle sync.
3. Run on a device or emulator, or **Build → Build APK(s)** →
   `app/build/outputs/apk/debug/app-debug.apk`

When prompted, grant **"Allow all"** photo access
(Android 14's "Select photos" option is treated as denied).

## How it works

Every photo is decoded once at thumbnail size and fingerprinted: a 9×8 difference hash,
an 8×8 average hash, a 4×4×4 RGB histogram and a Laplacian blur score.
Two photos are grouped when

    hamming(dHash) × 0.5 + hamming(aHash) × 0.3 + histogramDiff × 20

falls below the chosen threshold. OCR runs photo by photo in the background and
results become searchable immediately.

## Roadmap

Persistent cache & incremental scans · trash instead of delete · exact-duplicate detection
(file hash) · burst detection · video cleaner · multi-language OCR · smart search
(phone numbers, emails, dates) · on-device image categories

## License

MIT