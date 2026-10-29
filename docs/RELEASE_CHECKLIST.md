# Release checklist

## Done and verified (2026-10-29)

| Area | Evidence |
|---|---|
| Browser: secure WebView, desktop mode, tap-to-hover menus, uploads, downloads, pop-ups | Unit tests, earlier instrumented and emulator runs, harness 14/14 |
| Sign-in required before browsing; lock cannot be bypassed | `AccessLockTest`, `BrowserController.navigationAllowed`, emulator screenshots |
| 7-day trial, ৳250 lifetime via PayStation, refunds, reconciliation | Backend 46/46; PayStation sandbox smoke test |
| NestJS backend on Neon | Migrations applied; live server smoke test on Neon (TESTING.md) |
| cron-job.org endpoint | Tests: secret required; a missed IPN is caught |
| Play readiness: target SDK 36, AAB, icons, account-deletion web page, Data safety answers | `bundleRelease`; PLAY_STORE.md |

## You must do

1. **Reset the Neon database password** (it was shared in chat), then use the new connection strings with `sslmode=verify-full`.
2. **Google Cloud:** consent screen (published), Web client ID, and an Android client with the **Play App Signing SHA-1** and your upload-key SHA-1.
3. **Secrets:** `npm run keygen`. Put them in Render, never in git.
4. **Render:** deploy the Blueprint (`render.yaml`) and enter the environment values.
5. **PayStation:** production credentials in Render; send them the IPN URL `https://<api-host>/api/payment/ipn`.
6. **cron-job.org:** POST `/api/cron/reconcile` every 10 minutes with `Authorization: Bearer <CRON_SECRET>`.
7. **Website:** run `render-site.ts` with your real publisher details and commit `site/`.
8. **Build** `bundleRelease` with the public settings, then sign with your upload key.
9. **Go-live test on a real phone:** sign-in, trial, a real ৳250 payment, a refund, and confirm access is revoked.
10. **Play review account:** a Google account granted complimentary access (`dist/grant.js`), entered in *App access*.
11. **Run the instrumented tests** on a phone (`./gradlew connectedDebugAndroidTest`). They were not run after the flavor merge because the PC lacked memory for the emulator.

## Decisions on record

* **PayStation inside the Play app:** conflicts with Google Play's Payments policy for Bangladesh; risk accepted by the publisher (PLAY_STORE.md).
* Refund window of 7 days; log retention of 90 days; trial-hash retention of 24 months; 30-day offline grace for lifetime access; one trial per device; account deletion removes lifetime access. These are defaults; confirm them.
* Use of the "e-GP" name: an impersonation-policy risk; consider permission from BPPA/CPTU.
* Bangladesh tax and consumer rules for selling access: not assessed. Get local advice.

## Known limitations

* Free Render instances sleep when idle. The 10-minute cron job keeps the API awake, but a missed run means the next request takes about a minute.
* An expired trial locks the browser the next time the app returns to the foreground.
* A rooted device can modify any app. The server stays authoritative for purchases.
