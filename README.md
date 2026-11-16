# Banners HINT Control

An Android app to view and control **T-Mobile Home Internet** gateways, rebuilt from the ground up in Jetpack Compose.

Based on [HINT Control](https://github.com/zacharee/HINTControl) by Zachary Wander (MIT). This fork is Android-only and focuses on a cleaner app, more tools and safer defaults. Not made by or affiliated with T-Mobile.

## Supported gateways

| Gateway | API | Status, signal | Devices, Wi-Fi, reboot | Change admin login |
| --- | --- | --- | --- | --- |
| Arcadyan KVD21, TMOG4AR | TMI v1 (port 8080) | ✓ (no login needed) | ✓ | ✓ |
| Sagemcom FAST 5688W | TMI v1 | ✓ | ✓ | ✓ |
| Sercomm TMOG4SE | TMI v1 | ✓ | ✓ | ✓ |
| Nokia 5G21 | web CGI | ✓ | ✓ (no add/remove networks) | — |

The gateway communication is ported from HINT Control. It is covered by unit tests against recorded responses, but this fork has **not yet been tested on real gateway hardware**. Reports are welcome.

A built-in **demo gateway** lets you try every screen without one.

## Features

**Home**
- Signal score (0–100) and quality for 5G and LTE, bands with frequency (e.g. `n41 · 2.5 GHz`)
- RSRP, SINR, RSRQ and RSSI gauges, signal bars, cell IDs
- Uptime, network details, connected-device counts and a 30-minute SINR trend
- Several gateways, each with its own saved login; switch from the title bar

**Signal**
- Live readings with a rolling chart
- **Placement mode**: one reading per second, a tone and a buzz that rise with the score, and the best spot remembered. Walk the gateway around the house and stop where it's highest.
- History from 1 hour to 30 days, drag across a chart to read exact values, min/avg/max, band changes, CSV export
- Cell details: PCI, EARFCN/NR-ARFCN, CQI, bandwidth, eNB/gNB (eNB worked out from the ECGI when the gateway doesn't report it), TAC, operator, supported bands, and the location the gateway reports

**Devices**
- Search, filter (online, Wi-Fi, wired, new) and sort
- Give devices your own names, see when they were first seen, spot new ones, and see when a device uses a private (randomised) MAC

**Wi-Fi**
- Edit network name, password, security, bands, visibility (hide or show the network name) and guest mode
- Share any network as a QR code
- Per-band radio switch, channel, width and transmit power; band steering
- Every change is listed for review before saving, with warnings for risky ones (all radios off, 5 GHz off, renaming the network you're on)

**Gateway tools**
- Reboot with live progress and measured downtime
- **Scheduled reboot** (days and time), sent by the phone while it's on the gateway's network
- Change the admin username and password (TMI gateways)
- Gateway details with SIM numbers hidden until you tap, and a shareable status report for support

**Alerts and background**
- Notifications when the gateway goes offline (only while the phone is on its network), restarts, gets a weak signal, or a new device joins
- Event log of restarts, outages, band changes and saved settings
- Home-screen widget and Quick Settings tile with live 5G/LTE readings
- Launcher shortcuts: live signal, devices, reboot
- Automation: Tasker, MacroDroid, Automate and others can reboot or refresh through a token-protected broadcast

**App**
- Material You, light/dark, pure black, layouts for phones, tablets and landscape handhelds
- **In-app updates**: checks this repository's GitHub releases, shows the release notes, downloads the APK, checks its SHA-256 and package name, then hands it to Android's installer. Test builds (pre-releases) can be switched on or off.
- API explorer (developer options) for raw gateway requests; passwords and tokens are hidden in the output
- Local crash log you can share; nothing is uploaded

## Fixed compared with HINT Control 1.17.3

- Changing the 6 GHz channel or width overwrote the 5 GHz settings.
- Nokia: SIM status always showed "not active"; login sent the stored credentials instead of the typed ones.
- Gateway GPS position was never read (`latitude`/`longitude` vs `lat`/`lon`).
- The gateway admin password was stored unencrypted; it is now encrypted with the Android Keystore.
- Crash reports no longer go to a third-party service.

## Install

Download the APK from [Releases](https://github.com/The412Banner/HINTControl/releases). Builds are signed with the public AOSP test key (v1 + v2 + v3), so every build installs over the previous one. Android 8.0 or newer.

The app uses a different package name (`com.banner.hintcontrol`) from the original, so both can be installed side by side.

## Automation intents

Turn on **More → Automation**, then send a broadcast:

| | |
| --- | --- |
| Action | `com.banner.hintcontrol.action.REBOOT` or `com.banner.hintcontrol.action.REFRESH` |
| Package | `com.banner.hintcontrol` |
| Extra | `token` = the token shown in the app |

## Building

GitHub Actions builds every push to `feat/**` and `fix/**` (`.github/workflows/build.yml`): gateway and app unit tests, release APK, test-key signing with v1/v2/v3 verification, and screenshots of every screen rendered with demo data (Robolectric + Roborazzi).

Locally: `./gradlew :app:assembleRelease` (Android SDK, JDK 17+). The gateway module alone builds without the Android SDK: `./gradlew -PgatewayOnly=true :gateway:test`.

Project layout:
- `gateway/` — plain Kotlin: TMI and Nokia clients, demo gateway, auto-detection, signal ratings, band table, tests
- `app/` — Android app: Compose UI, Room history, DataStore settings, WorkManager and alarm jobs, Glance widget, tile, updater

## Credits

- [HINT Control](https://github.com/zacharee/HINTControl) by Zachary Wander — MIT License (see `LICENSE`). Gateway API flow, data models and safety warnings.
- AndroidX / Jetpack Compose, Kotlin, OkHttp, ZXing — Apache 2.0.

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2023 Zachary Wander; modifications (c) 2026 The412Banner.
