# Google Play release

## Flow

Install from Google Play (free listing) → open → **sign in with Google** (required before the browser works) → **7-day free trial** (timed by the server) → **৳250 one-time lifetime access via PayStation**, inside the app.

## ⚠ Payment policy risk (accepted by the publisher, 2026-10-29)

Google Play's Payments policy requires apps distributed on Play to use **Google Play Billing** to sell in-app digital features such as unlocking the app after a trial. Alternative billing is only permitted in specific markets (the EEA, UK, US, India, Indonesia, Japan, South Korea, Australia, Brazil and South Africa). **Bangladesh is not one of them.**

This build charges through PayStation inside the app. Google may therefore **reject the app, remove it, or suspend the developer account**. The publisher was informed and chose this design. Compliant alternatives, if needed later:

* switch the in-app payment to Google Play Billing (a one-time product at ৳250); the backend's entitlement model already supports a second payment source;
* or distribute the PayStation version outside Google Play only (website APK).

Sources: [Google Play Help: alternative billing](https://support.google.com/googleplay/answer/11174377?hl=en), [Play Console: billing choice programme](https://support.google.com/googleplay/android-developer/answer/17161464?hl=en-GB).

## 1. Build and sign the bundle

```bash
keytool -genkeypair -v -keystore egp-upload.jks -alias egp-upload -keyalg RSA -keysize 4096 -validity 10000   # once; back it up
./gradlew bundleRelease -PEGP_API_BASE_URL=https://<api-host>/ -PEGP_GOOGLE_WEB_CLIENT_ID=... -PEGP_ENTITLEMENT_PUBLIC_KEY=... \
  -PEGP_SUPPORT_EMAIL=... -PEGP_DEVELOPER_NAME="..." -PEGP_PUBLISHER_WEBSITE=... -PEGP_POLICY_EFFECTIVE_DATE=...
# signing: keystore.properties (git-ignored) or EGP_KEYSTORE_FILE / EGP_KEYSTORE_PASSWORD / EGP_KEY_ALIAS / EGP_KEY_PASSWORD
# output: app/build/outputs/bundle/release/app-release.aab
```

Enroll in **Play App Signing**, then add the **app signing key's SHA-1** to the Android OAuth client in Google Cloud. Without it, Google sign-in fails for everyone who installs from Play (DEPLOYMENT.md, step 3).

Technical requirements already met: target SDK 36, min SDK 26, 64-bit (no native code), edge-to-edge, predictive back, adaptive and themed icon, R8, no cleartext traffic, backups disabled.

## 2. Store listing

| Asset | File |
|---|---|
| Icon 512×512 | `store/play-icon-512.png` |
| Feature graphic 1024×500 | `store/feature-graphic-1024x500.png` |
| Phone screenshots | take them from a signed-in build: the browser, the menu and Account & access (`screenshots/` has emulator examples) |

**Short description:**
> Independent mobile browser for Bangladesh's public e-GP procurement portal.

**Full description:**
> e-GP Browser makes the public e-GP portal (www.eprocure.gov.bd) easier to use on Android phones.
>
> • Desktop-site mode for tender forms and wide tables
> • Tap-friendly menus, uploads and downloads with a downloads manager
> • Pop-up windows, zoom, full screen, offline recovery and dark mode
> • Secure: locked to the official portal over HTTPS, certificate errors are never bypassed, your e-GP password is never stored
>
> Sign in with Google to start a 7-day free trial. After the trial, unlock lifetime access with a one-time payment of ৳250. No subscription. The ৳250 is for this app, not a government fee.
>
> IMPORTANT: e-GP Browser is an independent app. It is not made by, affiliated with or endorsed by the Government of Bangladesh, BPPA, CPTU or the e-GP portal. All procurement information comes from https://www.eprocure.gov.bd and is shown unchanged.

## 3. App content (Play Console)

| Section | Answer |
|---|---|
| Privacy policy | `https://<site-host>/legal/privacy.html` |
| **App access** | "All functionality requires sign-in with Google. Reviewers: sign in with the review account below, which has lifetime access." Create a Google account for review, sign in once in the app, then run `node dist/grant.js grant <email> "Google Play review"` (DEPLOYMENT.md, Admin). Without this, reviewers on a shared test device can be blocked by the one-trial-per-device rule. |
| Ads | No ads |
| **Account deletion** | In the app: *Account & access → Delete account*. Web: `https://<site-host>/legal/delete-account.html` |
| Content rating | IARC questionnaire (no objectionable content) |
| Target audience | 18+ |
| Government apps | Not a government app; give the disclaimer and the source URL www.eprocure.gov.bd |
| Financial features | None (the app sells its own access; it offers no financial services) |

### Data safety

| Data type | Collected | Shared | Purpose | Notes |
|---|---|---|---|---|
| Email address, name | Yes | No | Account management | From Google sign-in |
| User IDs | Yes | No | Account management, fraud prevention | Google account ID; pseudonymous keyed hash kept for trial abuse prevention |
| Device or other IDs | Yes | No | Fraud prevention | App-scoped Android ID, sent once at trial start, stored only as a keyed hash |
| Purchase history | Yes | No | App functionality, account management | Invoice, amount, status |
| Phone number | Yes | **Yes** (PayStation) | Payments | Sent to PayStation to create the checkout; not stored by us |
| Financial info (card, wallet) | No | – | – | Entered only on PayStation's page; we never receive it |
| Web browsing (portal pages) | No | – | – | Stays on the device |

Security practices: **encrypted in transit: yes**; **users can request deletion: yes** (in app and on the web). Data is required (the app needs an account).

## 4. Other policy points

* **Impersonation.** The name "e-GP" refers to a government service. The app states it is independent (first-launch dialog, settings, store description). Avoid government seals or flags in the icon and graphics. Written permission from BPPA/CPTU would reduce this risk.
* **Web-view apps.** List the added value (desktop mode, tap menus, downloads manager, security) in the description, as above.
