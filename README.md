# SpendPop — SMS → Google Sheets expense tracker (Android, Kotlin + Compose)

## Build
1. Open in Android Studio (latest), let Gradle sync (accept the AGP/Gradle upgrade prompt if offered).
2. Variants: `personalDebug` (auto SMS capture, sideload only) and `playDebug` (no SMS permission; paste-SMS import).
3. Google Cloud: create project -> enable **Google Sheets API** -> OAuth consent screen -> create an **Android OAuth client**
   (package name + SHA-1 of your signing key). The app requests only `drive.file` (non-sensitive): it can only touch the
   sheet it creates itself.
4. Change `applicationId` in `app/build.gradle.kts` to your own ID before the first Play upload (permanent).

## Security architecture
| Layer | Control |
|---|---|
| SMS intake | `RECEIVE_SMS` only (no READ_SMS); receiver guarded by `BROADCAST_SMS`; alphanumeric bank sender IDs only, OTP/promo dropped |
| Data minimisation | Raw SMS never stored or logged; only parsed fields; reference stored masked, dedupe via SHA-256 fingerprint |
| At rest | Room + SQLCipher; random passphrase wrapped by Android Keystore (AES-256-GCM); `allowBackup=false` |
| In transit | HTTPS only, cleartext blocked by network security config, tokens fetched on demand (never stored) |
| Sheet safety | `RAW` writes + formula-injection stripping on merchant text; least-privilege `drive.file` scope |
| App access | Biometric/PIN lock, FLAG_SECURE (no screenshots/recents), private lock-screen notifications |
| Build | R8 minify, non-debuggable release, keystore kept outside repo (`keystore.properties`) |

## Keeping it updatable
- Bump `versionCode`/`versionName` for every release; keep the SAME signing key (use Play App Signing + back up the upload key).
- New bank format = add a pattern in `SmsParser` (Core.kt), add a unit test, ship.
- DB changes = bump `Db` version + write a Room migration (schemas exported to `app/schemas`). Never use destructive fallback.
- Play builds check for updates in-app (flexible update flow). Roll out with staged releases (5% -> 20% -> 100%).
- Keep dependencies current; run `./gradlew dependencyUpdates` or enable Dependabot.

## Play Store (Android only)
**Read first:** Google's current SMS policy says apps may only declare SMS permissions if they are the default SMS/Phone/
Assistant handler (with limited exceptions reviewed in Play Console). A "money-management" exception, as your spec assumes,
is not stated on the policy page I could find, so do not count on it. That is why this project has two flavors:
`personal` (SMS, sideload) and `play` (no SMS permission, paste-SMS import, fully policy-safe).

1. Create a Google Play developer account (one-time fee, identity verification). Personal accounts may need a closed test
   with a minimum number of testers for a set period before production access; check the current requirement.
2. Generate an upload key: `keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`;
   create `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword). Never commit either file.
3. Build: `./gradlew bundlePlayRelease` -> `app/build/outputs/bundle/playRelease/app-play-release.aab`.
4. Play Console -> Create app (free, App). Enrol in Play App Signing when uploading.
5. Complete: Store listing (icon 512px, feature graphic 1024x500, 2+ phone screenshots), **Privacy policy URL** (must state
   on-device parsing, what goes to Google Sheets, no sale/sharing), **Data safety** form, Content rating, Target audience,
   Financial features declaration, Ads = none, App access.
6. Testing track: Internal -> Closed -> Production. Attach the .aab, add release notes, submit for review.
7. Only if you insist on SMS in the Play build: declare it in **App content -> Sensitive app permissions -> SMS/Call log**,
   with a demo video; expect rejection unless you qualify. Do not ship SMS permissions in the `play` flavor otherwise.
8. Every update: bump versionCode -> build AAB -> upload -> staged rollout.
