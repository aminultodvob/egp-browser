# e-GP Browser

A secure, premium Android browser for Bangladesh's public e-GP procurement portal, **https://www.eprocure.gov.bd/**. It shows the real portal in a hardened WebView and adds what phones need for long procurement workflows: desktop-site mode, tap-friendly menus, uploads, authenticated downloads, pop-up windows and session handling.

> **Independent app.** Not made by, affiliated with or endorsed by the Government of Bangladesh, BPPA, CPTU or the e-GP portal. It does not modify or automate the portal and never bypasses CAPTCHA, OTP, signatures or payment checks.

## User flow

Install from **Google Play** → open → **sign in with Google** (the browser stays locked until then) → **7-day free trial** (timed by the server) → **৳250 one-time lifetime access via PayStation** (bKash, Nagad, Rocket, cards). The purchase is restored on any phone where the user signs in with the same Google account.

> ⚠ Charging through PayStation inside a Play-distributed app conflicts with Google Play's Payments policy for Bangladesh. The publisher accepted this risk. See [docs/PLAY_STORE.md](docs/PLAY_STORE.md).

| | |
|---|---|
| App | Kotlin 2.2, Android Views + Material 3 (brand light and dark themes), WebView. Package `bd.egpmobile.browser`, min SDK 26, target SDK 36 |
| Backend | **NestJS 11** (Fastify adapter), TypeScript, Node.js 20+ |
| Database | **PostgreSQL on Neon** (Singapore), TLS with certificate verification |
| Scheduler | **cron-job.org** → `POST /api/cron/reconcile` every 10 minutes |
| Hosting | Render (free API and static site) via `render.yaml`; self-hosted alternative in `deploy/` |
| Build | AGP 8.13.2, Gradle 8.14.6, JDK 17 |

## Features

* Brand design from the supplied logo: splash, adaptive and themed icon, logo top bar with a secure address pill, floating bottom bar, bottom-sheet menu, smooth animations, light and dark themes
* **Desktop / Mobile** rendering modes; **tap-to-hover** for the portal's hover menus
* Uploads (system picker, single and multiple); downloads with session cookies, notifications and a downloads screen
* Pop-ups, zoom, full screen, offline banner with auto-retry, error screens, form-resubmission guard
* Strict security: exact host allowlist, HTTPS only, certificate errors never bypassed, no JS bridges, least-privilege WebView, no sensitive logging ([docs/SECURITY.md](docs/SECURITY.md))
* Accounts: Google sign-in (Credential Manager), rotating refresh tokens in Android Keystore-encrypted storage, server-signed entitlement verified in the app, account deletion in the app and on the web
* Payments: server-created ৳250 invoice, PayStation checkout in a Custom Tab, PAID only after server-to-server verification, idempotent fulfilment, refunds revoke access, scheduled reconciliation

## Project layout

```
app/                Android app (src/main, src/test, src/androidTest)
backend/            NestJS API: src/http (controllers, guards), src/payments, src/auth, migrations, tests
site/               generated public website (policies, account deletion), served by Render
render.yaml         Render Blueprint (API + static site)
deploy/             self-hosted alternative (Docker Compose + Caddy)
branding/ store/    logo, Play icon and feature graphic
docs/               DEPLOYMENT, PLAY_STORE, BACKEND, SECURITY, TESTING, RELEASE_CHECKLIST, LIMITATIONS
tools/              touch-hover behavioural harness
```

## Build the app

Requirements: JDK 17 and the Android SDK (Platform 36). Set `sdk.dir` in `local.properties` or `ANDROID_HOME`.

```bash
./gradlew assembleDebug                      # debug APK (talks to http://10.0.2.2:8080 by default)
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest          # device or emulator
./gradlew bundleRelease -PEGP_API_BASE_URL=https://<api-host>/ -PEGP_GOOGLE_WEB_CLIENT_ID=... -PEGP_ENTITLEMENT_PUBLIC_KEY=...
```

Public build settings (not secrets): `EGP_API_BASE_URL`, `EGP_GOOGLE_WEB_CLIENT_ID`, `EGP_ENTITLEMENT_PUBLIC_KEY`, `EGP_SUPPORT_EMAIL`, `EGP_DEVELOPER_NAME`, `EGP_PUBLISHER_WEBSITE`, `EGP_POLICY_EFFECTIVE_DATE`. Release signing: `keystore.properties` (git-ignored) or `EGP_KEYSTORE_*` variables.

## Backend

```bash
cd backend
npm ci
npm test                                     # 46 tests on an embedded PostgreSQL
cp .env.example .env                         # fill in (npm run keygen for secrets); never commit .env
npm run build && node --env-file=.env dist/migrate.js && node --env-file=.env dist/server.js
```

API, data model and PayStation integration: [docs/BACKEND.md](docs/BACKEND.md). Step-by-step free deployment (Neon, Render, cron-job.org, Google sign-in, PayStation): [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Before releasing

Work through [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md) and [docs/PLAY_STORE.md](docs/PLAY_STORE.md).
