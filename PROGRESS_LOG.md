# Banners HINT Control — Progress Log

Newest entry first. Times are US Eastern.

## Resume point
- Branch `feat/android-compose` (main = upstream zacharee/HINTControl 1.17.3, untouched).
- Modules: `gateway/` (plain Kotlin: TMI + Nokia + demo clients, discovery, signal helpers, tests), `app/` (Android, Compose) — app not written yet.
- Next: local `./gradlew -PgatewayOnly=true :gateway:test` (JDK 21), then the app module, CI workflow, screenshots, APK.

## Lessons learned (don't repeat these)
- (none yet)

## 2026-11-15 — Start of the Android rewrite (feat/android-compose, in progress)
**Why:** user asked for a professional Android-only Jetpack Compose remake of HINT Control with new features, testkey v1/v2/v3 APK and a release; later added an in-app updater that reads this repo's releases.
**Upstream checked:** zacharee/HINTControl main f5b14a5f (1.17.3, 2026-09-19). Already Compose Multiplatform. MIT.
**Upstream bugs found (fixed in the rewrite):**
1. `ChannelConfigLayout`: editing the 6 GHz channel or width wrote the 6 GHz settings into the 5 GHz band.
2. Nokia SIM status: lower-cased text compared with "Valid" → always false.
3. Nokia login sent the stored username/password instead of the typed ones.
4. `GPSData` read `lat`/`lon` while gateways send `latitude`/`longitude`.
5. Gateway admin password stored unencrypted; crash reports went to the upstream author's Bugsnag account.
**Kept on purpose:** Wi-Fi save body is byte-compatible with the old app (Ktor DefaultJson: encodeDefaults=true, nulls written, 6 GHz left out when absent) — verified by unit tests.
**No T-Mobile gateway on the home network** → real-gateway paths are unit-tested against recorded responses only; the UI is tested in demo mode.
