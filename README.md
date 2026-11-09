# RecVault

**Continuous voice recording on Android, backed up to your own Google Drive, with an alarm that rings when a backup is missing.**

[Download the APK](https://womp-womp.github.io/RecVault/) · [Privacy policy](https://womp-womp.github.io/RecVault/privacy.html)

> I built RecVault so my Poppy could tell me his life story, in his own voice, as he went into hospice, and so not one word of it would ever be lost.
>
> "I use it; I wouldn't if I hadn't needed to 100% rely on it perfectly. Evidence provided when requested :)."

RecVault records in 15-minute chunks and uploads each finished chunk to Google Drive. A file only counts as backed up when Drive reports the same name, size and MD5 fingerprint as the file on the phone. If a recording is still unconfirmed after 75 minutes, or recording stops, or the phone is low on space, you get an alarm-sound notification that won't swipe away and that says why.

## In plain words

- **Records all day,** even with the screen off and the phone in your pocket.
- **Copies every recording to your own Google Drive** within about 15 minutes of it being made.
- **Double-checks every copy.** It asks Google what it actually stored and compares it to the phone, byte for byte.
- **Rings like an alarm clock if anything goes wrong:** a copy goes missing, recording stops, or the phone runs low on space. The alarm says why and won't swipe away until it's fixed.
- **Never deletes anything.** Your recordings stay on the phone even if you uninstall the app.
- **A dead battery costs seconds, not the recording.** Everything up to the moment the phone died still plays.
- **Private.** No ads, no tracking, no company servers. Your recordings go from your phone to your Drive and nowhere else.
- **You pick the quality.** Voice (about 350 MB a day), Standard (about 690 MB a day) or High (about 1.4 GB a day, keeps every laugh and breath). You can switch any time.

## What it guarantees

- **Nothing is deleted.** RecVault has no delete code. Recordings live in the public `Recordings/RecVault` folder and survive uninstalling the app.
- **Power loss costs seconds, not the chunk.** Audio is AAC in an ADTS stream, so every frame (21–32 ms of audio) stands alone. A half-written chunk still plays.
- **Verified, not assumed.** After uploading, RecVault re-lists the cloud folder and compares fingerprints. It never trusts an upload response alone.
- **No silent failure.** If the alarm is quiet, every recording older than 75 minutes is confirmed in the cloud. That rule is machine-checked (see below).
- **No ads, no analytics, no servers.** The only network traffic is between your phone and your own Google account. Scope is `drive.file`: RecVault can only see files it created.

## Technical details

| | |
|---|---|
| **Recording** | |
| Format | AAC-LC in an ADTS stream (`.aac`), mono, phone microphone |
| Quality (selectable) | Voice: 32 kbps @ 32 kHz · Standard (default): 64 kbps @ 44.1 kHz · High: 128 kbps @ 48 kHz. A change starts a new chunk immediately |
| Chunks | 15 minutes each, back to back; the switch gap is measured and shown in the app |
| Power-loss safety | ADTS frames (1024 samples, 21–32 ms) are self-contained, so a cut-off chunk plays up to its last written frame |
| Storage use (24/7) | Voice ~350 MB/day (~10.5 GB/month) · Standard ~690 MB/day (~21 GB/month) · High ~1.4 GB/day (~41 GB/month) |
| Saved to | `Recordings/RecVault/` (Android 12+) or `Music/RecVault/` (Android 10–11), public MediaStore folder that survives uninstall |
| **Backup** | |
| Destination | `My Drive › RecVault › <phone name>`, Drive API v3 resumable upload |
| Google permission | `drive.file` only: can create files and see only its own files |
| When | Right after each chunk closes, plus a WorkManager pass every 15 minutes (Android may delay it slightly) |
| Confirmed when | A fresh listing of the Drive folder shows the same name, size and MD5 as the phone; upload responses alone are never trusted |
| Retries | Automatic and idempotent: a crash or lost connection can cause a re-upload, never a loss |
| **Alarms** | sound + vibration, not swipeable, clear themselves when fixed |
| Not backed up | Any recording unconfirmed 75 minutes after it started |
| Recording off | The phone stopped the recorder, or Android blocked a restart (e.g. after reboot): tap to resume |
| Mic stuck | No audio written for 3 minutes (the recorder restarts itself after 90 seconds first) |
| Low storage | Under 2 GB free on the phone |
| Internal error | A backup pass or the alarm check itself failed |
| **App** | |
| Requires | Android 10+ (API 29), Google Play services for sign-in |
| Permissions | `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `READ_MEDIA_AUDIO`, `INTERNET`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Code it ships | Kotlin, AndroidX, WorkManager, Google Play services auth. No ad, analytics or crash-reporting SDKs |
| Size | 6.7 MB APK, APK Signature Scheme v2 |

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
