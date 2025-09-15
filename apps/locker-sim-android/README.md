# LockerSim (Android)

**What it is:** A lab‑only Android app that simulates mobile ransomware *safely*:
- AES‑GCM encrypt/decrypt inside the app‑private sandbox (`getExternalFilesDir("locker")/data`)
- Beacon bursts to a lab host (`POST /beacon`, `User-Agent: Locker-Beacon/1.0 (SIMULATION)`)
- One‑shot beacon after boot via `BootReceiver` + `WorkManager`
- Offline ransom note screen (`assets/note.html`, JS disabled)

## Build
- Android Studio (2025.x), Kotlin.
- `compileSdk=36`, `targetSdk=36`, `minSdk=34`.
- Release signing: `app/keystore/locker-lab.jks` (lab only).
- Build: **Debug** --> `app/build/outputs/apk/debug/app-debug.apk`.

## Components
- `AndroidManifest.xml` (permissions: `INTERNET`, `RECEIVE_BOOT_COMPLETED`; `usesCleartextTraffic=true` for lab host only)
- `beacon/BeaconWorker.kt` (OkHttp POST; `bursts`, `intervalSec` via `Data`)
- `boot/BootReceiver.kt` (queues one beacon on `BOOT_COMPLETED`)
- `crypto/Crypto.kt` (AES-GCM, 12B IV, 128b tag, AAD = filename)
- `data/FileRepo.kt` (seed/list/encrypt/decrypt in sandbox dir)
- `ransom/RansomNoteActivity.kt` + `assets/note.html` (offline view)
- `MainActivity.kt` + `res/layout/activity_main.xml` (buttons: Seed/List/Encrypt/Decrypt/Beacons)

## Safety
- Sandbox‑only encryption; no access to user libraries unless a user‑granted SAF tree is added.
- Cleartext traffic enabled only to the lab host for demonstrator clarity.
