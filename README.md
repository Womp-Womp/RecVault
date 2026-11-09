# RecVault

**Continuous voice recording on Android, backed up to your own Google Drive, with an alarm that rings when a backup is missing.**

[Download the APK](https://womp-womp.github.io/RecVault/) · [Privacy policy](https://womp-womp.github.io/RecVault/privacy.html)

RecVault records in 15-minute chunks and uploads each finished chunk to Google Drive. A file only counts as backed up when Drive reports the same name, size and MD5 fingerprint as the file on the phone. If a recording is still unconfirmed after 75 minutes, or recording stops, or the phone is low on space, you get an alarm-sound notification that won't swipe away and that says why.

## What it guarantees

- **Nothing is deleted.** RecVault has no delete code. Recordings live in the public `Recordings/RecVault` folder and survive uninstalling the app.
- **Power loss costs seconds, not the chunk.** Audio is AAC in an ADTS stream, so every ~23 ms frame stands alone. A half-written chunk still plays.
- **Verified, not assumed.** After uploading, RecVault re-lists the cloud folder and compares fingerprints. It never trusts an upload response alone.
- **No silent failure.** If the alarm is quiet, every recording older than 75 minutes is confirmed in the cloud. That rule is machine-checked (see below).
- **No ads, no analytics, no servers.** The only network traffic is between your phone and your own Google account. Scope is `drive.file`: RecVault can only see files it created.

## Verification

| What | How |
|---|---|
| Backup logic | 17 theorems in Lean 4 (`lean/RecVault.lean`), no `sorry`, standard axioms only: `alarm_silent_sound`, `stale_quiet_sound`, `reach_keeps_local`, `cache_never_empty`, `verified_progress`, and more |
| Kotlin matches the proofs | `core/` tests re-check each theorem on the real code: 24,576 exhaustive + 100,000+ stale-list + 20,000 randomized cases |
| OneDrive fingerprint | QuickXorHash checked against rclone's published test vectors |
| The APK you download | SHA-256 printed on the [download page](https://womp-womp.github.io/RecVault/); Android also rejects any APK whose signature doesn't cover every byte |

What is **not** proven: Android itself, Google's servers, the Kotlin compiler, the microphone hardware. The alarm exists for exactly those.

```
lean lean/RecVault.lean          # checks the proofs (core Lean 4, no Mathlib)
./gradlew :core:test             # runs the theorem-mirroring tests
./gradlew :app:assembleRelease   # builds the APK (needs your own signing key)
```

## Install

1. Download the APK from the [download page](https://womp-womp.github.io/RecVault/) on your phone.
2. Tap it. Allow installs from that source when Android asks; if Play Protect warns about an unknown app, choose **More details › Install anyway**.
3. Open RecVault, allow microphone + notifications + audio, tap **Let RecVault run unrestricted (battery)**, then **Connect Google Drive**, then **Start recording**.

Google sign-in is tied to the official build's signing key. If you build it yourself, register your own OAuth Android client in Google Cloud with your package name and SHA-1.

## Known limits

- Android 14+ won't let any app turn the mic on by itself after a reboot or update. RecVault rings **"RECORDING IS OFF, tap to resume"** instead.
- If the phone force-kills the app entirely, the app can't warn you. Set battery to **Unrestricted**. A cloud-side dead-man's switch is planned.
- OneDrive support is written but not enabled in this build.

## Support

RecVault is free. If it helps you: [Venmo @Timothy-Lee-427](https://venmo.com/u/Timothy-Lee-427) · [Cash App $timwlee](https://cash.app/$timwlee)

Released into the public domain under the [Unlicense](LICENSE).
