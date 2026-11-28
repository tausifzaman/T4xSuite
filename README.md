<div align="center">

<img src="assets/logo.png" alt="T4xSuite Logo" width="112" height="112" />

# T4xSuite

**Open-Source, Non-Root Android HTTP/HTTPS Intercepting Proxy, HTTP Repeater, and Burp Suite Mobile Alternative**

[![Download Latest APK](https://img.shields.io/badge/Download-Latest%20APK-080A0E?style=for-the-badge&logo=android&logoColor=D6B56D&color=D6B56D&labelColor=080A0E)](https://github.com/tausifzaman/T4xSuite/releases/latest)
[![VirusTotal Clean](https://img.shields.io/badge/VirusTotal-0%2F65%20Clean-080A0E?style=for-the-badge&logo=virustotal&logoColor=55C99A&color=55C99A&labelColor=080A0E)](https://www.virustotal.com/gui/file/3ce448be31018026bb3d3eb421cb73fd79bb26c53451c97c380c61c2e2b86cf9?nocache=1)
[![Non-Root](https://img.shields.io/badge/Root-Not%20Required-080A0E?style=for-the-badge&color=55C99A&labelColor=080A0E)](https://github.com/tausifzaman/T4xSuite)
[![Open Source](https://img.shields.io/badge/Open%20Source-GitHub-080A0E?style=for-the-badge&logo=github&logoColor=D6B56D)](https://github.com/tausifzaman/T4xSuite)
[![Android 10+](https://img.shields.io/badge/Android-10%2B%20(API%2029--35)-080A0E?style=for-the-badge&logo=android&logoColor=D6B56D)](https://github.com/tausifzaman/T4xSuite/releases/latest)

</div>

---

## Overview

**T4xSuite** (in-app header: **T4X SUITE**) is a free, **open-source**, **non-root** native Android web security testing suite built as a lightweight **Burp Suite mobile alternative** for bug bounty hunters, penetration testers, security researchers, and Android developers.

It combines an isolated testing browser, an opt-in loopback HTTP/HTTPS MITM proxy, inline request/response interception, high-capacity traffic history, and a multi-tab raw HTTP/1.1 Repeater with built-in Decoder, Comparer, and Intruder tools directly on your Android device—with **zero root required**, **zero VPN service**, **zero trackers**, and **no desktop PC**.

---

## Download, Installation & Verification

1. Visit the **[GitHub Releases](https://github.com/tausifzaman/T4xSuite/releases/latest)** page.
2. Download the latest `.apk` file under **Assets**.
3. Open the APK on your Android device (Android 10 / API 29 or newer) and tap **Install** (enable *Install from unknown sources* if prompted).

You can also check for new releases anytime directly inside the app via **About > Check update**.


## Screenshots

<div align="center">

| Browser & Smart Search | Proxy & User CA Lab |
| :---: | :---: |
| <img src="assets/browser.jpg" alt="T4xSuite Testing Browser and Smart Address Bar" width="270" /> | <img src="assets/proxy2.jpg" alt="T4xSuite Loopback HTTP/HTTPS Proxy and CA Detection" width="270" /> |

| Traffic History | About & Update Checker |
| :---: | :---: |
| <img src="assets/history.jpg" alt="T4xSuite Live HTTP/HTTPS Traffic History" width="270" /> | <img src="assets/about.jpg" alt="T4xSuite About, Setup Tutorial and GitHub Release Checker" width="270" /> |

| Raw HTTP Repeater |
| :---: |
| <img src="assets/repeater.jpg" alt="T4xSuite Standalone Raw HTTP/1.1 Repeater" width="270" /> |

</div>

---

### Security, Privacy & VirusTotal Audit

T4xSuite is engineered with strict local-only boundaries, zero telemetry, and a minimal `AndroidManifest.xml`:

| Audit Check | Status / Details |
| :--- | :--- |
| **VirusTotal Scan** | **[0 Detections — 100% Clean](https://www.virustotal.com/gui/file/3ce448be31018026bb3d3eb421cb73fd79bb26c53451c97c380c61c2e2b86cf9?nocache=1)** |
| **APK SHA-256** | `3ce448be31018026bb3d3eb421cb73fd79bb26c53451c97c380c61c2e2b86cf9` |
| **Root Requirement** | **Non-Root** (works out of the box on stock, locked-bootloader Android devices) |
| **Trackers & Analytics** | **0 Trackers** (no Firebase, Google Analytics, Crashlytics, AdMob, or telemetry SDKs) |
| **Granted Permissions** | `android.permission.INTERNET` only |
| **Not Requested / Never Used** | No Storage (`READ/WRITE_EXTERNAL_STORAGE`), No VPN (`BIND_VPN_SERVICE`), No Accessibility Service, No Device Admin, No Location, Camera, Contacts, or Background Foreground Service |
| **Cloud Backup** | Disabled (`android:allowBackup="false"`) so sensitive session keys and CA material never upload to cloud backups |

---


## Key Features

### 1. Isolated Testing Browser
- **Smart URL & Google Search Bar:** Animated gold-accented address bar with a tap-to-focus welcome screen that distinguishes full `http://` and `https://` links, bare domains or `localhost` / IPv4 hosts (auto-prepending `https://` or loopback `http://`), and keyword searches (routed to Google Search).
- **Mobile & Desktop Viewport Switch:** Toggle between standard Mobile mode and a framed Desktop (`Linux x86_64`) viewport in one tap during an active session.
- **Zero-Bloat Cache & Data Control:** Uses `LOAD_NO_CACHE` to prevent unnecessary disk cache accumulation and provides a confirmed **Clear Data** button to wipe WebView cache, cookies, `WebStorage`, form autocomplete, and navigation history.

### 2. Non-Root Loopback HTTP/HTTPS Intercepting Proxy
- **High-Concurrency Zero-VPN Architecture:** Binds strictly to `127.0.0.1` on an ephemeral port (handling up to 64 concurrent connections and 32 I/O workers with explicit HTTP/1.1 ALPN negotiation) and routes only T4xSuite's built-in browser—no system-wide VPN profile, root privileges, or Magisk modules needed.
- **Opt-In HTTPS Inspection & Live CA Trust Check:** Generates an RSA-2048 local Certificate Authority encrypted at rest via Android Keystore AES-GCM, exports the public `.crt` certificate to `Download/T4X-SUITE` via MediaStore (requiring zero storage permissions), and automatically checks Android's User CA trust store to confirm installation status.
- **Inline Request & Response Interception:** Pause HTTP and inspected HTTPS traffic inline, modify raw headers or payloads with automatic `Content-Length` recalculation, or forward original bytes untouched.

### 3. Multi-Tab HTTP/1.1 Repeater & Security Toolkit
- **Multi-Session Tabs & Landscape Split Workspace (`Rotate`):** Run multiple independent Repeater sessions (`1`, `2`, `3`, `+`) with safe tab-close triggers, switch cleanly between Request and Response in Portrait mode, or toggle **Rotate** for a full-screen 50/50 side-by-side split view with a draggable/resizable floating HTTP keypad and physical OTG keyboard shortcuts (`Ctrl+Enter` / `F5` to send).
- **Auto cURL-to-Raw-HTTP & Instant 4-Mode Response Viewer (`Pretty | Raw | Hex | Render`):** Paste any `curl` command or type header-only requests directly without manual blank-line formatting, and inspect syntax-highlighted Pretty/Raw text, Hex dumps, or safe native Image renders with zero lag.
- **Built-in Decoder, Comparer & Intruder Lite:** Encode/decode Base64, URL, HTML, Hex, Unicode, and Gzip or inspect/tamper JWT tokens in **Decoder**; diff requests/responses line-by-line in **Comparer**; run `§marker§` Number Range or Wordlist fuzzing in **Intruder**; and copy any request as `cURL` or `Python requests` in one tap.

### 4. High-Capacity Traffic History, Filtering & Session Save/Load
- **Byte-Budgeted Exchange Inspector & Session Persistence:** Retains up to 10,000 newest-first exchanges (48 MiB memory ring buffer) with smooth paged rendering, sensitive header masking (`Set-Cookie`, `WWW-Authenticate`), one-tap **Send to Repeater**, and zero-permission Storage Access Framework **Save / Load Session** support.
- **Advanced Wildcard Filtering, In-Body Search & HAR Audit:** Filter traffic live by wildcard domain/URL include/exclude rules, asset type toggles (Images, JS/CSS, Fonts, CONNECT tunnels), or keyword search across URLs, headers, and bodies—plus an offline `.har` review viewer.

---

## HTTPS Interception Setup Guide

1. Open **T4xSuite** and go to the **Proxy** tab.
2. Tap **Generate CA** (or **Export CA certificate**) to save `T4X-SUITE-CA.crt` into `Download/T4X-SUITE`.
3. Open your Android **Settings > Security & privacy > More security settings > Encryption & credentials > Install a certificate > CA certificate** (menu wording varies by device) and select `T4X-SUITE-CA.crt`.
4. Return to **T4xSuite**—the status will automatically update to **CA stored & installed in Android**.
5. Tap **Start proxy**, enable **HTTPS inspection** and **Request/Response interception** as needed, and start testing in the **Browser** tab.

---

## Technical Specifications

| Specification | Details |
| :--- | :--- |
| **Application Name** | T4xSuite (`dev.t4xsuite`) |
| **Latest Release** | [v0.7.0](https://github.com/tausifzaman/T4xSuite/releases/latest) (`versionCode 7`) |
| **Supported OS** | Android 10 (API 29) to Android 15 (API 35) |
| **Root / Trackers** | **Non-Root** · **0 Trackers** · 100% Native Kotlin & Android Views |
| **Manifest Permissions** | `android.permission.INTERNET` only (`allowBackup="false"`) |
| **Cryptographic Storage** | Local CA private key encrypted at rest using Android Keystore AES-GCM |

---

## Legal Disclaimer

**T4xSuite** is an open-source security testing tool intended strictly for authorized penetration testing, educational research, and debugging applications or infrastructure that you own or have explicit permission to assess. Unauthorized interception or testing of third-party systems is prohibited. The developer assumes no liability for misuse.

---


## Developer

<div align="center">

<img src="https://github.com/tausifzaman.png" width="90" alt="Tausif Zaman"/>

## Tausif Zaman

*Developer · Security Researcher · Tool Builder*

[![Website](https://img.shields.io/badge/🌐_Website-tausifzaman.online-00C853?style=for-the-badge&logoColor=white)](https://tausifzaman.online)
&nbsp;
[![GitHub](https://img.shields.io/badge/GitHub-@tausifzaman-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/tausifzaman)



![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Python](https://img.shields.io/badge/Python-3776AB?style=flat-square&logo=python&logoColor=white)
![PHP](https://img.shields.io/badge/PHP-777BB4?style=flat-square&logo=php&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![Web%20Security](https://img.shields.io/badge/Web_Security-FF4444?style=flat-square&logo=hackerone&logoColor=white)

</div>
