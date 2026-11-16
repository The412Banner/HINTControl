# Banners HINT Control — Progress Log

Newest entry first. Times are US Eastern.

## Resume point
- Branch `feat/android-compose` (main = upstream zacharee/HINTControl 1.17.3, untouched).
- Modules: `gateway/` (plain Kotlin; 36 tests pass locally with JDK 21: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 ./gradlew -PgatewayOnly=true :gateway:test`) and `app/` (Android, Compose; CI only — no Android SDK on the device).
- CI: `.github/workflows/build.yml` runs on push to `feat/**`/`fix/**` (forks can't dispatch workflows that aren't on the default branch). Artifacts: `HINTControl-apk` (testkey v1/v2/v3 APK + .sha256) and `screenshots` (Robolectric renders of every screen with demo data).
- First app compile: run 43810534903 on 2886e943 (in progress at time of writing).
- Next: fix compile errors, review screenshots, stage APK in /sdcard/Download, ask before install, prerelease v1.0.0-pre1 from the tested APK (gh release create by hand, so the released file == the tested file).

## Lessons learned (don't repeat these)
- (none yet)

## 2026-11-16 — App module written (feat/android-compose, compiling in CI)
**Built:** a8a240be (app) + 2886e943 (CI push trigger). Not compiled before this run.
**What's in it:** first-run detection (192.168.12.1 + phone's router), login (password encrypted with the Android Keystore), multiple gateways, Home dashboard (score ring, 5G/LTE gauges, bands with frequencies, uptime, devices count, 30-min trend), Signal (live + placement mode with tone/vibration and best-score tracking, history charts 1 h–30 d with drag-to-read, CSV export, band-change list, cell/tower details incl. eNB from ECGI, supported bands, gateway GPS), Devices (search, filters, sort, nicknames, New badge, private-MAC note), Wi-Fi (network cards, hide/show (#140), guest, QR share, editor with validation, radios/channel/width/power, band steering, review-and-save with safety warnings), reboot with progress and downtime, scheduled reboot (exact alarm → short foreground service), alerts (offline only when on the gateway's network, restart, weak SINR, new device), event log, gateway details with masked SIM numbers, shareable report, Glance widget, Quick Settings tile, launcher shortcuts, automation broadcasts with token (replaces the GPL Tasker library), API explorer, in-app updater (GitHub releases, pre-release channel, SHA-256 + package check, PackageInstaller), local crash log, themes (Material You, dark, pure black).
**Decision (mine):** Tasker plugin library dropped — it is GPL-3.0 (checked github.com/joaomgcd/TaskerPluginSample LICENSE) and would bind this MIT app's APK; automation intents instead.

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
