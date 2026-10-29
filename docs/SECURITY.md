# Security policy

e-GP Browser displays the official Bangladesh e-GP portal (`https://www.eprocure.gov.bd/`). It does not modify the portal, automate submissions, or store credentials. This document lists each security control and where it is enforced.

## Threat model in one paragraph

The browser holds an authenticated e-GP session (the `JSESSIONID` cookie) for users who submit tenders, payments and contract documents. The main risks are: a non-e-GP page running with access to that session, the session leaking to another host, network interception, malicious files, and sensitive data ending up in logs or backups. The controls below address each one.

## Controls

| # | Control | Enforced in | How |
|---|---------|-------------|-----|
| 1 | **Allowlist of trusted hosts** | `UrlValidator.decide`, `BrowserWebViewClient` | Only `www.eprocure.gov.bd` and `eprocure.gov.bd` (exact match, default port) load inside the app. Look-alike hosts, other ports, IPv6 literals and URLs with embedded credentials are rejected. Users can add extra hosts in Settings, for example a payment provider they have verified. Entries must be exact, valid host names; wildcards are refused. |
| 2 | **Two enforcement layers** | `shouldOverrideUrlLoading` + `shouldInterceptRequest` | Link clicks and server redirects are checked in `shouldOverrideUrlLoading`. Any other main-frame load (history, pop-up initial loads, JavaScript navigation) is checked again in `shouldInterceptRequest`, which returns an empty 403 for anything not allowed. |
| 3 | **HTTPS only** | `UrlValidator`, `network_security_config.xml`, manifest | `http://` portal links are rewritten to `https://` before loading. `cleartextTrafficPermitted="false"`, `usesCleartextTraffic="false"` and `MIXED_CONTENT_NEVER_ALLOW` block HTTP everywhere, including subresources. |
| 4 | **Certificate errors are fatal** | `BrowserWebViewClient.onReceivedSslError` | Always calls `handler.cancel()`. There is no "proceed anyway" button. Only system CAs are trusted, so user-installed CAs cannot intercept traffic. |
| 5 | **External links need confirmation** | `MainActivity.onExternalNavigation`, `ExternalIntents` | Non-e-GP links, `mailto:`, `tel:` and `intent:` open in another app only after a dialog that names the destination. `intent:` URIs are sanitized: `BROWSABLE` is forced; component, selector and URI-grant flags are stripped; intents aimed at this app are refused. |
| 6 | **Dangerous schemes blocked** | `UrlValidator` | `javascript:`, `file:`, `content:`, `data:`, `blob:`, `ftp:` and every other scheme are never navigated to. |
| 7 | **No JavaScript bridges; one UI-only script** | `BrowserController.configure`, `TouchHoverSupport`, `assets/touch_hover.js` | `addJavascriptInterface` is never called. The only injected script makes the portal's hover menus open on tap. It runs only in the official origins `https://www.eprocure.gov.bd` and `https://eprocure.gov.bd` (never user-added hosts). It reads no cookies, storage or form fields and makes no network requests (`TouchHoverScriptTest` enforces this). |
| 8 | **Least-privilege WebView** | `BrowserController.configure` | `allowFileAccess=false`, `allowContentAccess=false`, geolocation off. Camera, microphone and location requests are denied (`BrowserChromeClient`). Safe Browsing is on (manifest and settings). |
| 9 | **Session cookies stay on e-GP** | `DownloadHandler`, `DownloadPolicy.validate` | The `Cookie` header is attached to a download only when its URL is HTTPS on a trusted host. Downloads from other hosts are offered as external links instead. |
| 10 | **No executable downloads** | `DownloadPolicy.isExecutable` | APK, EXE, MSI, scripts, JAR and similar files are refused by both extension and MIME type. Files are never opened automatically. |
| 11 | **Upload isolation** | `FileChooserLauncher` | Uses the system picker, so no storage permission is needed. Only `content://` results are passed to the page; `file://` results are refused so a picker app cannot attach this app's private files. |
| 12 | **No sensitive logging** | `SafeLog`, `proguard-rules.pro`, `BrowserChromeClient.onConsoleMessage` | All logging goes through `SafeLog`, which is a no-op in release builds. URLs are logged only through `UrlValidator.redact`, which drops query strings, fragments, credentials and `;jsessionid`. Page console output is swallowed. R8 strips `Log.v/d/i` in release builds. `LoggingPolicyTest` fails the build's test run if code bypasses these rules. |
| 13 | **No passwords stored** | — | The app has no password storage, form-filling or credential code. WebView form-data saving is deprecated and off on Android 8+. Android's system Autofill service (for example Google Password Manager) is the user's own choice and is not controlled by this app. |
| 14 | **No backup of session data** | Manifest, `data_extraction_rules.xml` | `allowBackup="false"`, and cloud backup and device transfer exclude every domain, so cookies cannot be restored onto another device. |
| 15 | **Debugging off in release** | `MainActivity.onCreate` | `WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)`; the release build type is not debuggable. |
| 16 | **Form resubmission is confirmed** | `MainActivity.onFormResubmission` | Reloading a page produced by a POST asks before resending, so a tender or payment form is not submitted twice by accident. |
| 17 | **Client certificates only for e-GP** | `MainActivity.onClientCertRequest` | If the portal requests a TLS client certificate, the system KeyChain picker is shown only for trusted hosts on port 443; otherwise the request is cancelled. |
| 18 | **Clear logout path** | `SessionManager`, menu → *Sign out of this device* | Clears cookies, DOM storage, HTTP-auth data and history after a confirmation dialog. The dialog tells users to press the portal's own Logout first, because this app never calls e-GP endpoints itself. |
| 19 | **No CAPTCHA, OTP or signature bypass** | — | The app does not read, solve or modify CAPTCHA, OTP, digital-signature or payment steps. It only renders them. |

## Verified hosts

Hosts were identified by fetching the public homepage and following its redirects (October 2026):

* `http://www.eprocure.gov.bd/` → `301` → `https://www.eprocure.gov.bd:443/` (the explicit `:443` is handled).
* The homepage also links to `www.bppa.gov.bd`, `www.lged.gov.bd`, `www.rhd.gov.bd`, `www.reb.gov.bd`, `www.bwdb.gov.bd`, `get.adobe.com`, `www.winzip.com` and others. These are informational sites and open externally after confirmation.

**Payment gateways and other external sign-in providers were not added.** Those flows are only reachable with an authorized account, and this project had no test credentials. Do not add hosts based on guesses. Before adding one under *Settings → Additional trusted sites*, confirm it from an official BPPA source or by observing the real redirect with an authorized training account.

## Known security limitations

* **DownloadManager forwards request headers on redirects.** If an e-GP download URL redirects to a different host, Android's DownloadManager re-sends the `Cookie` header to that host. Real browsers would not. The app cannot inspect redirects before DownloadManager follows them. The risk is limited to redirects chosen by the e-GP server itself.
* **WebView updates come from the Play Store.** The rendering engine's security depends on *Android System WebView* (or Chrome) being up to date on the device.
* **The server-side session survives "Sign out of this device"** until it expires, unless the user presses the portal's own Logout first.
* **Third-party cookies** are off by default and can be turned on in Settings. Turn them on only if a verified embedded payment or sign-in page requires them.
* **System Autofill** can offer to save the e-GP password if the user has a password manager enabled. This is a device-level choice outside this app's control.

## Accounts, payments and backend

| # | Control | Enforced in | How |
|---|---------|-------------|-----|
| 20 | **Server-authoritative access** | `backend/src/entitlement.ts`, `AccessPolicy` | Trial times come from the database clock. The app receives an ES256-signed entitlement and verifies it with the public key compiled into the app, so a local flag cannot unlock anything. |
| 21 | **Lock cannot be bypassed** | `BrowserController.navigationAllowed` | Every navigation (Home, Reload, address bar, history, deep links) passes one access check. Covered by `AccessLockTest`. |
| 22 | **Server-anchored clock** | `TrustedClock` | Offline time = server time + monotonic elapsed time. After a reboot (detected by boot count) the device clock is used, but never earlier than the last trusted time. |
| 23 | **Google sign-in without passwords** | `GoogleSignIn`, `backend/src/auth/google.ts` | Credential Manager returns a Google ID token. The backend checks signature (Google JWKS), issuer, audience, expiry and a verified email. |
| 24 | **Session tokens** | `TokenService`, `SecureStore` | 15-minute HS256 access tokens. 60-day refresh tokens are stored hashed and rotated on every use; reuse revokes the whole family. On the phone, tokens are AES-256-GCM encrypted with an Android Keystore key and excluded from backup. |
| 25 | **Payment proof only from PayStation's API** | `PaymentService.verify` | PAID requires `transaction-status` = success, the same invoice, exactly ৳250 and a fresh `trx_id`. IPNs (unsigned), redirects and app reports only trigger this check. Amounts are fixed on the server; a client-sent amount is rejected. |
| 26 | **Idempotent, race-safe fulfilment** | DB constraints, `SELECT … FOR UPDATE`, savepoint | Unique invoice and `trx_id`, one PAID order per user, one entitlement per user and kind. Duplicate IPNs grant once; a second payment is flagged for refund. |
| 27 | **Ownership checks** | `/api/payment/status` | Invoices are only visible to their owner (others get 404). Invoice numbers are server-generated and unguessable. |
| 28 | **Merchant secrets server-side only** | `backend/.env` / secret manager | PayStation credentials, signing key and HMAC secrets never appear in the app, git or logs (logger redacts auth headers; config errors list names only). |
| 29 | **Checkout URL allowlist** | backend `isTrustedCheckoutUrl`, `AccessActivity` | Only HTTPS checkout pages on `api.paystation.com.bd` / `sandbox.paystation.com.bd` are opened, in a Custom Tab, never in the e-GP WebView. |
| 30 | **Abuse limits** | `@fastify/rate-limit`, `trial_claims` | Per-IP and per-user rate limits. One trial per Google identity (survives account deletion) and per device (keyed hash of the app-scoped Android ID). |
| 31 | **Minimal data** | schema, privacy policy | No passwords, PINs, OTPs or card data are stored. The payer's phone number is passed to PayStation but not stored. Account deletion removes personal data and unlinks payment records. |
| 32 | **Return page hardening** | `/payment/return` | No scripts, `Content-Security-Policy: default-src 'none'`, `no-store`, `no-referrer`. It shows only the verified status. |
| 33 | **Scheduler endpoint secret** | `CronGuard` | `POST /api/cron/reconcile` requires `Authorization: Bearer <CRON_SECRET>` (at least 32 characters), compared in constant time. It can only re-check existing orders with PayStation. |
| 34 | **Verified database TLS** | `DATABASE_URL` with `sslmode=verify-full` | Connections to Neon use TLS 1.3 and verify Neon's certificate. The direct (non-pooled) URL is used only for migrations. |
| 35 | **Patched HTTP stack** | `backend/package.json` `overrides` | NestJS 11's Fastify adapter is forced onto Fastify 5.12.x, which fixes the advisories in 5.11.x. `npm audit` reports 0. |
| 36 | **Complimentary access is separate from payments** | migration 002, `admin.ts` | Admin-granted access has a reason and no order. A database constraint forbids access with neither. Revoking complimentary access never touches paid access. |
