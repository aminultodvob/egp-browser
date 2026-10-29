# Testing

Results are labelled **PASS**, **FAIL** or **NOT RUN** (with the reason). Nothing is marked as passing unless it was actually executed.

## Download fix (1.2.2, 2026-10-29)

| Check | Result |
|---|---|
| `ContentSnifferTest` (PDF / ZIP-Office / OLE / images detected; HTML error pages recognised; names and MIME types fixed) | PASS (7 tests; 63 unit tests in total) |
| `DownloadPipelineTest`, live portal on an Android 14 emulator: the public e-GP Guidelines PDF is saved as a real PDF and opens in the built-in viewer | PASS |
| `DownloadPipelineTest`: a portal web page requested as a document is refused, nothing saved | PASS |
| All instrumented tests (lock, browser, portal, WebView security, downloads) | **14/14 PASS** |
| Lint | 0 errors |

## Current results (2026-10-29, single app + NestJS backend)

| Suite | Result |
|---|---|
| Backend (NestJS 11, embedded PostgreSQL): auth, 7-day trial, checkout, verification, PayStation client, **cron endpoint**, **complimentary access** | **46/46 PASS** |
| Backend against the real **Neon** database | PASS: migrations 001 and 002 applied; TLS 1.3 with a verified certificate; compiled server started on Neon; `/health` OK; protected routes 401 without login; forged Google token rejected; cron 401 without secret, 200 with secret; return page renders |
| Android unit tests | **56/56 PASS** |
| Android lint | 0 errors |
| Builds: `assembleDebug`, `assembleRelease`, `bundleRelease`, `assembleDebugAndroidTest` | PASS |
| Android instrumented tests | **NOT RUN this time**: the emulator could not start because the PC had only 1.3 GB of free memory. The tests compile. The previous run (same browser, lock and policy code, before the flavor merge) passed 20/20. Run `./gradlew connectedDebugAndroidTest` on a phone or a PC with more free RAM. |
| Google sign-in end to end, real PayStation payment | NOT RUN: needs your Google OAuth client and a deployed backend (DEPLOYMENT.md) |

The sections below are the detailed record of the earlier two-build version. Its browser, policy, lock and payment tests carry over unchanged.

## 1. Automated tests

All results below are from runs on 2026-10-28 on Windows 11, with the emulator on an Android 14 (API 34) Google APIs x86_64 image.

### JVM unit tests: shared (`app/src/test`, run in both flavors)

Run with `./gradlew testPlayDebugUnitTest testDirectDebugUnitTest`.

| Suite | What it covers | Tests | Result |
|-------|----------------|-------|--------|
| `UrlValidatorTest` | Allowlist, explicit `:443`, HTTP→HTTPS upgrade, look-alike hosts, embedded credentials, backslash tricks, dangerous schemes, `about:blank`, address-bar normalization, log redaction, user host list | 18 | PASS |
| `DownloadPolicyTest` | Trusted-only cookie scope, HTTP upgrade, `blob:`/`data:` refusal, Content-Disposition (quoted, token, RFC 5987 Bengali), URL/MIME fallbacks, `.jsp` fix-up, path-traversal sanitization, executable blocking | 9 | PASS |
| `UserAgentProfileTest` | Desktop UA derived from WebView's Chrome version, no mobile/WebView markers, fallback ≥ Chrome 109 | 4 | PASS |
| `PageErrorTest` | WebView error mapping, auto-retry eligibility | 2 | PASS |
| `LoggingPolicyTest` | Static check: only `SafeLog` uses `android.util.Log`, no `print*`, no URL/cookie/header/password/token values interpolated into log calls | 4 | PASS |
| `TouchHoverScriptTest` | Menu script injected only into the official origins; uses no cookie, storage, form-value or network APIs; guards against double install | 3 | PASS |
| **Total (play)** | | **40** | **40 PASS, 0 FAIL** |

### JVM unit tests: direct build only (`app/src/testDirect`)

| Suite | What it covers | Tests | Result |
|-------|----------------|-------|--------|
| `EntitlementVerifierTest` | Accepts tokens signed by the backend's real signer (interop fixture); rejects tampered payloads, other keys, `alg:none` and garbage | 6 | PASS |
| `AccessPolicyTest` | Signed-out, foreign or missing entitlement, 30-day offline lifetime, trial expiry at the server-recorded time, locked states, days left | 6 | PASS |
| `TrustedClockTest` | Server-anchored time; winding the clock back after a reboot gains nothing; long uptime after a reboot is detected with the boot count | 4 | PASS |
| **Total (direct)** | shared 40 + direct 16 | **56** | **56 PASS, 0 FAIL** |

### Backend (`backend/tests`, `npm test`, embedded PostgreSQL 18)

| Suite | Tests | Result |
|-------|-------|--------|
| Accounts: Google sign-in, invalid token, auth required, refresh rotation with reuse detection, per-IP rate limit, logout | 6 | PASS |
| 7-day trial: starts once on the server clock (exactly 7 days), client times rejected, expiry, reinstall or new phone keeps the trial, delete-and-recreate gets no new trial, one trial per device, device IDs hashed | 7 | PASS |
| Checkout: ৳250 server invoice and amount, client price rejected, phone validation, provider failure or outage or untrusted URL fails safely, open checkout reused | 5 | PASS |
| Verification: pending and cancelled don't unlock; verified payment unlocks with a signed entitlement; forged IPN, unknown invoice, success redirect, wrong amount and wrong invoice don't unlock; duplicate IPNs grant once; provider downtime never marks paid; other users' invoices hidden; no double charge; survives a new phone; overrides an expired trial; refund revokes; second payment flagged for refund | 15 | PASS |
| PayStation client: documented form fields and headers, failure codes, status normalisation, not-found vs outage; exact-amount parser | 5 | PASS |
| **Total** | **38** | **38 PASS, 0 FAIL** |

Live PayStation **sandbox** (`scripts/sandbox-smoke.ts`): PASS (initiate returned a sandbox checkout URL; status returned `processing` and `250.00`).

### Instrumented tests

Run with `./gradlew connectedPlayDebugAndroidTest connectedDirectDebugAndroidTest`.

| Suite | What it covers | Result |
|-------|----------------|--------|
| `WebViewConfigurationTest` | Least-privilege WebView settings; desktop UA and wide viewport; mobile mode restores defaults; controller refuses untrusted, `javascript:` and `file:` URLs | PASS (both flavors, 4/4) |
| `MainActivityTest` | Controls visible and labelled; untrusted address not loaded in-app; rotation keeps the same WebView; recreation and Back don't crash | PASS (both flavors, 4/4) |
| `PortalSmokeTest` (play, live network) | Homepage over HTTPS; `http://` tender-list link upgraded and loaded; Back history | PASS (3/3) |
| `AccessLockTest` (direct) | While locked, Home, Reload and the address bar load nothing | PASS (1/1) |
| **Total** | play 11, direct 9 | **20 PASS, 0 FAIL** |

### Touch-hover behaviour (`tools/touch-hover-harness.html`)

Runs the portal's real `ddlevelsmenu.js` and `theme_1.css` in Chrome and replays the event sequence Android produces for a tap. Serve the project root over HTTP and open the page; add `?script=0` for the control run.

| Run | Result |
|-----|--------|
| Without `touch_hover.js` (control) | 8/14. Reproduces the bug: a tap on "Tender" navigates and the menu closes |
| With `touch_hover.js` | **14/14 PASS**: first tap opens, second tap follows the link, nested menus, switching menus, tap outside closes, CSS dropdowns, plain links unaffected |

### Build checks

| Check | Result |
|-------|--------|
| `assemblePlayDebug`, `assembleDirectDebug` | PASS |
| `assemblePlayRelease`, `assembleDirectRelease` (R8, unsigned) | PASS |
| `bundlePlayRelease` (.aab) | PASS |
| `lintPlayDebug`, `lintDirectDebug` | PASS: 0 errors (minor layout and KTX-style warnings only) |
| Backend `tsc` build, `npm audit` | PASS: 0 vulnerabilities |

## 2. Requirement test matrix

| # | Scenario | How tested | Result |
|---|----------|------------|--------|
| 1 | Homepage over HTTPS | PortalSmokeTest; emulator screenshots | PASS |
| 2 | Public tender pages | PortalSmokeTest (AllTenders.jsp) | PASS |
| 3 | Links and history | PortalSmokeTest (Back) | PASS |
| 4 | Desktop and mobile switching | WebViewConfigurationTest; menu sheet on emulator | PASS (settings level); visual comparison per page is manual |
| 5 | Login and logout | Login works on the user's own phone (reported); app sign-out clears the session (code) | PARTIAL: needs a training account for a full run |
| 6 | Form submission (test env) | – | NOT RUN: no training-environment access |
| 7 | Single and multiple uploads | – | NOT RUN: needs a signed-in account |
| 8 | Public PDF downloads | – | NOT RUN on device; policy covered by unit tests |
| 9 | Authenticated downloads | – | NOT RUN: needs an account |
| 10 | Redirects and external auth | UrlValidatorTest; PortalSmokeTest HTTP upgrade | PASS (policy); no external auth providers verified |
| 11 | Network loss and recovery | – | NOT RUN on device (logic implemented) |
| 12 | Server errors and session expiry | – | NOT RUN (needs portal errors or an account) |
| 13 | Rotation and recreation | MainActivityTest | PASS |
| 14 | Untrusted URL rejection | UrlValidatorTest; MainActivityTest; WebViewConfigurationTest | PASS |
| 15 | SSL error handling | Code review (always `cancel()`) | NOT RUN with a real bad-certificate proxy |
| 16 | File-picker cancellation | – | NOT RUN on device (lifecycle-safe code) |
| 17 | Clearing data and sessions | – | NOT RUN on device |
| 18 | No sensitive data in logs | LoggingPolicyTest (static); R8 strips logs | PASS (static) |
| 19 | Tap-to-hover menus | Harness against the real portal script, 14/14 | PASS |
| 20 | Trial, payment and entitlement security | Backend 38 tests; direct unit tests 16; AccessLockTest | PASS |
| 21 | Google sign-in end to end | – | NOT RUN: needs your OAuth client |
| 22 | Real PayStation payment | Sandbox smoke only | NOT RUN in production (do the go-live test) |

## 3. Manual device checklist

Run on at least one small phone (≤ 5.5"), one large phone (≥ 6.5") and one Android 8 or 9 device, in both light and dark theme. Use the **BPPA training environment** for every step that signs in or submits. Never submit real tenders, payments or documents on production for testing.

**Install and launch**
- [ ] Installs and launches; homepage loads with the lock icon and the *Desktop* badge
- [ ] Bengali text on the homepage renders correctly (not boxes)

**Navigation**
- [ ] Address bar: `eprocure.gov.bd`, `/FAQ.jsp` and full URLs load; `example.com` asks before opening externally; `two words` shows an error
- [ ] Back, Forward, Home, Reload and Stop work; the system Back button walks history and then exits
- [ ] A `target=_blank` link opens the pop-up overlay; its × and Back close it
- [ ] Links to BPPA, LGED and other external sites open the confirmation dialog

**Rendering**
- [ ] Desktop ↔ Mobile switch shows the warning, reloads and keeps you signed in
- [ ] Wide tender tables are usable in landscape with pinch zoom
- [ ] Zoom in/out menu items, text-size setting and full screen (enter and exit with Back) work
- [ ] Rotation during form entry keeps the typed values

**Session (training account)**
- [ ] Login works, including CAPTCHA or OTP if shown (entered by the tester)
- [ ] Session survives navigation, rotation and app switching
- [ ] Session-expiry behaviour: the portal's own expiry page or redirect is shown
- [ ] Portal Logout, then *Sign out of this device*, returns to the homepage signed out

**Uploads (training account)**
- [ ] Single-file upload of PDF, DOC, DOCX, XLS, XLSX, ZIP and JPG/PNG
- [ ] Multiple-file upload where the portal allows it
- [ ] Cancel the picker, then upload again: still works
- [ ] Oversized or wrong-type file: the portal's own validation message is shown
- [ ] Rotate while the picker is open, then pick a file

**Downloads**
- [ ] Public PDF (e.g. *e-GP Guidelines*) downloads, shows a notification and opens from *Downloads*
- [ ] Authenticated tender document downloads while signed in (training account)
- [ ] Failed download shows a reason; Retry works or explains that the link expired
- [ ] Android 8/9: storage permission prompt appears on the first download

**Errors and security**
- [ ] Airplane mode shows the offline screen, and the page reloads automatically when you go back online
- [ ] Slow network (throttled): progress bar shows, Stop works
- [ ] Clear browsing data: each option is confirmed; cookies or site data show the sign-out warning
- [ ] `adb logcat` during login, upload and download (release build) shows no URLs with query strings, cookies or form values
- [ ] A proxy with a self-signed certificate shows "Connection is not secure" with no option to proceed
