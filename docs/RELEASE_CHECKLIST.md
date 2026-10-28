# Production-readiness report and checklist

## Completed and verified (October 2026)

| Area | Status | Evidence |
|---|---|---|
| Browser (both builds) | Done | 20 instrumented tests pass on an Android 14 emulator, including live portal load over HTTPS, HTTP→HTTPS upgrade and Back history |
| Premium UI and brand | Done | Logo-derived palette, splash, adaptive and themed icon, floating bottom bar, bottom-sheet menu, light and dark themes (screenshots in `screenshots/`) |
| Tap-to-hover portal menus | Done | 14/14 behavioural checks against the portal's real `ddlevelsmenu.js` (`tools/touch-hover-harness.html`) |
| Play build | Done | `bundlePlayRelease` builds (target SDK 36); free, with no payment or account code |
| Direct build: account, trial, purchase UI | Done | Builds; lock screen verified on emulator; Home, Reload and address bar cannot bypass the lock (`AccessLockTest`) |
| Backend | Done | 38 tests on real PostgreSQL covering every payment, trial and auth scenario in the brief; 0 npm vulnerabilities |
| PayStation request format | Verified on sandbox | `scripts/sandbox-smoke.ts`: initiate succeeded, status returned `processing / 250.00` |
| Signed entitlement interop | Done | Android verifier accepts backend-signed tokens and rejects tampered or foreign ones (unit tests) |
| Android unit tests | Done | play 40, direct 56; lint: 0 errors |

## Not verified (needs your accounts or devices)

* **Google sign-in end to end.** It needs your Google Cloud OAuth client (below). The code path builds and handles cancel, no-account and failure cases, but no real sign-in has been performed.
* **A real PayStation production payment.** Do this once, as described in BACKEND.md → Go-live test.
* **Authenticated portal workflows** (login, uploads, authenticated downloads) need a training or real e-GP account on a real phone.
* **Backend deployed on HTTPS.** Not deployed; no hosting account is available here.

## Credentials and configuration you must supply

| Item | Where | Notes |
|---|---|---|
| Google Cloud project with an **OAuth client of type "Web application"** | `EGP_GOOGLE_WEB_CLIENT_ID` (app build) and `GOOGLE_CLIENT_IDS` (backend) | Also create an **Android** OAuth client with package `bd.egpmobile.browser` and the SHA-1 of your direct-build signing key, or Credential Manager will refuse |
| Entitlement key pair | `npm run keygen`: private key → backend secret; `EGP_ENTITLEMENT_PUBLIC_KEY` → app build | |
| `JWT_SECRET`, `TRIAL_HASH_SECRET` | backend secrets | from `npm run keygen` |
| Backend HTTPS URL | `EGP_API_BASE_URL` (direct release build), `PUBLIC_BASE_URL` (backend) | must be `https://` |
| PayStation merchant ID and password (production) | backend secrets only | never in the app or git |
| IPN URL registered with PayStation | email PayStation | `https://<api>/api/payment/ipn` |
| PostgreSQL 13+ with backups | `DATABASE_URL` | |
| Publisher name, support email, website, policy date | `EGP_DEVELOPER_NAME`, `EGP_SUPPORT_EMAIL`, `EGP_PUBLISHER_WEBSITE`, `EGP_POLICY_EFFECTIVE_DATE` | shown in in-app policies; placeholders appear until set |
| Release keystores | `keystore.properties` or `EGP_KEYSTORE_*` | separate upload key for Play; your own key for the direct APK |
| Hosted privacy policy URL | your website | required by Play and good practice for direct users |

Build the configured direct release:

```bash
./gradlew assembleDirectRelease -PEGP_API_BASE_URL=https://api.example.com/ -PEGP_GOOGLE_WEB_CLIENT_ID=...apps.googleusercontent.com -PEGP_ENTITLEMENT_PUBLIC_KEY=MFkw... -PEGP_SUPPORT_EMAIL=support@example.com -PEGP_DEVELOPER_NAME="..." -PEGP_PUBLISHER_WEBSITE=https://example.com -PEGP_POLICY_EFFECTIVE_DATE=2026-11-01
```

(These values are public. They can also go in `~/.gradle/gradle.properties`. Never put the PayStation password or private keys in the app build.)

## Business and legal decisions to confirm

These were written as sensible defaults. Confirm or change them, ideally with professional advice:

1. **Refund window: 7 days, full refund** (`assets/legal/refund.html`).
2. **Retention:** trial hashes 24 months, server logs 90 days, payment records "as required for accounting". Configure log retention at your host to match.
3. **Offline grace:** lifetime access works offline for 30 days after the last check (`OFFLINE_LIFETIME_DAYS`).
4. **One trial per device** (`MAX_TRIALS_PER_DEVICE=1`). Shared office phones only get one trial.
5. **Account deletion removes lifetime access** (there is no transfer to another account). This is stated in the app and the privacy policy.
6. **Use of the "e-GP" name and logo**, and whether to ask BPPA/CPTU for permission (see PLAY_STORE.md §4).
7. Bangladesh-specific obligations (for example consumer protection, data protection rules and tax on digital sales) have **not** been assessed. Get local legal and tax advice before charging.

## Known limitations

* An expired trial locks the browser the next time the app returns to the foreground, not mid-page.
* Offline time checks after a reboot use the device clock, but never earlier than the last trusted time, so winding the clock back gains nothing. Moving the clock forward only shortens access.
* A rooted device can modify any app. The signed entitlement prevents forging access on an unmodified app, and the server stays authoritative for purchases.
* See LIMITATIONS.md for portal compatibility limits.
