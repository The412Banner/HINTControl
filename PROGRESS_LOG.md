# Banners HINT Control — Progress Log

Newest entry first. Times are US Eastern.

## Resume point
- Branch `feat/android-compose` (main = upstream zacharee/HINTControl 1.17.3, untouched; merge = user's go after device test).
- Release candidate: **25b021a34c444afd6596f4886144998fdf5ea3d1**, CI run **43814242612** green (gateway 36 tests, LogicTest 7, ContainerTest end-to-end, R8 release APK, apksigner v1+v2+v3 true, cert a40da80a…f5dc, 18 screenshots).
- APK `HINTControl-v1.0.0-pre1-25b021a3.apk`, 3,329,576 bytes, sha256 `764fed21464e19150ccd85709fa7440deda8883f8a730e2556202a52825557b3`. Staged on the Pocket FIT: `/sdcard/Download/HINTControl-android-compose-25b021a3.apk` (hash host == device). NOT installed (needs the user's yes). Upstream app dev.zwander.arcadyankvd21control is installed on the FIT; ours is a separate package.
- Next: publish v1.0.0-pre1 PRE-RELEASE with this exact APK (`gh release create --target 25b021a3… --prerelease`), verify, then user test. Stable/Latest only on the user's word.

## Lessons learned (don't repeat these)
- Polling that stops when the last screen unsubscribes also cancels whatever the poll loop was doing; side effects (history, widget data) must run in the app scope. Found by the end-to-end Robolectric test, not by screenshots.
- Robolectric downloads its Android runtime from Maven Central at test time; a 429 there fails the run. CI caches ~/.m2/repository/org/robolectric and pre-fetches with retries.
- On forks, workflow_dispatch only sees workflows on the default branch → build on push to feat/** instead.

## 2026-11-16 ~01:10 EST — Release candidate 25b021a3 (feat/android-compose, CI-green, NOT device-tested)
**Runs:** 43812310757 (72ff4fbb) failed — Robolectric runtime download HTTP 429 → cache + pre-fetch (dd668919); 43812576886 failed — end-to-end test: no history sample within 10 s; 43812859446 (ba8f953b) green with a split test; root cause: the poller is cancelled when the last screen stops collecting, cutting the history write → side effects moved to the app scope (c05f3885, run 43813505611 green, poller-recorded sample now asserted). Wi-Fi save and admin-login change also moved to the app scope so leaving the screen can't cancel them; warning cleanup (25b021a3, run 43814242612 green).
**Also:** navigation rail in landscape and on wide screens (Material's default used a bottom bar on short landscape windows, e.g. handhelds); verified in 18-shell-landscape.png.

## 2026-11-16 — First green build + screenshot review (feat/android-compose, CI-green, NOT device-tested)
**CI:** 43810959412 on 6ecb8760 green. aapt2: package com.banner.hintcontrol, versionCode 1, versionName 1.0.0-pre1, label "Banners HINT". Gateway tests 36 + LogicTest 7 pass.
**Screenshot review (Robolectric, demo data):** Home, Home dark, landscape, Signal live/history/cell, Devices, Devices login, Wi-Fi, More, Gateway details, Updates, Settings, Setup found/not found, icon — all render. Found and fixed in 08ac4701: Live score card not full width (cards now fill width), detail-row values shifted by the copy button, band-change rows overlapped; test clock now real time; added nav-shell screenshots (bottom bar portrait, rail landscape).
**Added after:** R8 keep rules for the widget ActionCallback and workers (created by name at runtime).

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
