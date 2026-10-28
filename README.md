# e-GP Browser

A secure, premium Android browser for Bangladesh's public e-GP procurement portal, **https://www.eprocure.gov.bd/**. It shows the real portal in a hardened WebView and adds what phones need for long procurement workflows: desktop-site mode, tap-friendly menus, uploads, authenticated downloads, pop-up windows and session handling.

> **Independent app.** Not made by, affiliated with or endorsed by the Government of Bangladesh, BPPA, CPTU or the e-GP portal. It does not modify or automate the portal and never bypasses CAPTCHA, OTP, signatures or payment checks.

## Two builds

| | **play** | **direct** |
|---|---|---|
| Channel | Google Play | Your website (APK download) |
| Price | Free | 7-day free trial, then **৳250 one-time lifetime** via PayStation |
| Account | None | Google sign-in |
| Server | None | [`backend/`](backend) (Node.js + PostgreSQL) |
| Bundle | `./gradlew bundlePlayRelease` | `./gradlew assembleDirectRelease` |

Payment code lives only in `app/src/direct` and is not compiled into the Play build. See [docs/PLAY_STORE.md](docs/PLAY_STORE.md).

| | |
|---|---|
| Package | `bd.egpmobile.browser` |
| Min / target SDK | 26 (Android 8.0) / 36 (Android 16) |
| App | Kotlin 2.2, Android Views + Material 3 (brand light and dark themes), WebView |
| Build | AGP 8.13.2, Gradle 8.14.6, JDK 17 |
| Backend | Node.js 20+, TypeScript, Fastify 5, PostgreSQL 13+ |

## Features

* Brand design from the supplied logo: splash screen, adaptive and themed icon, logo top bar with a secure address pill, floating bottom bar with a gradient Home button, bottom-sheet menu, smooth fades and slide-ins, light and dark themes
* **Desktop / Mobile** rendering modes (desktop user agent derived from the installed WebView version, plus a wide viewport)
* **Tap-to-hover**: the portal's mouse-hover menus open with a first tap; a second tap follows the link
* Uploads through the system picker (single and multiple); downloads through DownloadManager with session cookies, notifications and a downloads screen
* Pop-up windows, zoom, full screen, offline banner with auto-retry, error screens, form-resubmission guard
* Strict security policy: exact host allowlist, HTTPS only, certificate errors never bypassed, no JS bridges, least-privilege WebView, no sensitive logging ([docs/SECURITY.md](docs/SECURITY.md))
* Direct build: Google sign-in, server-timed 7-day trial, PayStation checkout in a Custom Tab, server-verified lifetime unlock, a signed offline entitlement, purchase restore on any phone, account deletion, and in-app privacy, terms and refund policies

## Project layout

```
app/src/main/        shared browser (MainActivity, BrowserController, WebView clients, downloads, uploads, policies…)
app/src/play/        free access gate (Google Play)
app/src/direct/      account, trial, PayStation purchase (AccessActivity, AccessRepository, EntitlementVerifier…)
app/src/test*/       JVM unit tests      app/src/androidTest*/  instrumented tests
backend/             API, migrations, tests, Dockerfile
branding/  store/    your logo and banner, Play icon 512 and feature graphic
docs/                SECURITY, BACKEND (API and deployment), PLAY_STORE, RELEASE_CHECKLIST, TESTING, LIMITATIONS
tools/               touch-hover behavioural harness
```

## Build

Requirements: JDK 17 and the Android SDK (Platform 36, Build-Tools 36). Set `sdk.dir` in `local.properties` or `ANDROID_HOME`.

```bash
./gradlew assemblePlayDebug assembleDirectDebug        # debug APKs
./gradlew testPlayDebugUnitTest testDirectDebugUnitTest lintPlayDebug lintDirectDebug
./gradlew connectedPlayDebugAndroidTest                # device or emulator; includes live portal checks
./gradlew bundlePlayRelease                            # Play upload bundle (.aab)
./gradlew assembleDirectRelease -PEGP_API_BASE_URL=https://api.example.com/ ...   # see RELEASE_CHECKLIST.md
```

Outputs are under `app/build/outputs/` (`apk/<flavor>/<type>/`, `bundle/playRelease/`).

### Signing

Release builds are unsigned unless signing is configured, either in `keystore.properties` (git-ignored; see `keystore.properties.example`) or with `EGP_KEYSTORE_FILE`, `EGP_KEYSTORE_PASSWORD`, `EGP_KEY_ALIAS` and `EGP_KEY_PASSWORD`. Use a Play **upload key** for the Play build (enroll in Play App Signing) and your own key for the website APK. Back up keystores. Never commit them.

### Direct-build configuration (public values, not secrets)

`EGP_API_BASE_URL` (HTTPS), `EGP_GOOGLE_WEB_CLIENT_ID`, `EGP_ENTITLEMENT_PUBLIC_KEY`, `EGP_SUPPORT_EMAIL`, `EGP_DEVELOPER_NAME`, `EGP_PUBLISHER_WEBSITE`, `EGP_POLICY_EFFECTIVE_DATE`. Pass them as `-P` properties or environment variables. Until they are set, the direct build shows "Service not configured" and policy placeholders. Debug builds default to `http://10.0.2.2:8080/` (a backend on the emulator host).

## Backend

```bash
cd backend && npm ci && npm test      # 38 tests on an embedded PostgreSQL
```

Setup, API reference and PayStation integration: [docs/BACKEND.md](docs/BACKEND.md). Step-by-step production deployment (DigitalOcean Bangalore + Docker Compose + Caddy HTTPS, Google sign-in setup, PayStation go-live): [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md), with files in [`deploy/`](deploy).

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Load the portal; offline banner and auto-retry |
| `WRITE_EXTERNAL_STORAGE` (Android 8–9 only) | Save downloads to `Downloads/e-GP`; requested when a download starts |

No camera, microphone, location, contacts or broad storage access.

## Before releasing

Work through [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md). It lists credentials to create, decisions to confirm (refund window, data retention, use of the "e-GP" name) and what has and hasn't been verified.
