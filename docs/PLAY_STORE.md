# Google Play release (play build)

The **play** build is completely free. It contains no account, trial, payment or PayStation code: those classes exist only in `src/direct`. That keeps it outside Google Play's billing requirements. **Do not add the paywall to the Play build.** If you ever want to charge on Play, it must use Google Play Billing (see "Two builds" below).

## 1. Build the upload bundle

```bash
# one-time: create an UPLOAD key (keep it and its passwords safe; back it up)
keytool -genkeypair -v -keystore egp-upload.jks -alias egp-upload -keyalg RSA -keysize 4096 -validity 10000

# keystore.properties (git-ignored) or EGP_KEYSTORE_FILE / EGP_KEYSTORE_PASSWORD / EGP_KEY_ALIAS / EGP_KEY_PASSWORD
./gradlew bundlePlayRelease
# -> app/build/outputs/bundle/playRelease/app-play-release.aab  (upload this)
```

Enroll in **Play App Signing** when you create the app. Google keeps the app signing key and you keep only the upload key.

Technical requirements already met: target SDK **36 (Android 16)**, min SDK 26, 64-bit (pure Kotlin/Java, no native code), edge-to-edge, predictive back, adaptive and themed (monochrome) launcher icon, R8 minification, no cleartext traffic, `allowBackup=false`.

## 2. Store listing

Assets in `store/`:

| Asset | File |
|---|---|
| App icon 512×512 | `store/play-icon-512.png` |
| Feature graphic 1024×500 | `store/feature-graphic-1024x500.png` (cropped from your promo banner) |
| Phone screenshots | `screenshots/10-play-browser.png`, `11-play-menu.png`, `12-play-dark.png` (1080×2400) |

**App name:** e-GP Browser

**Short description (80 chars max):**
> Independent mobile browser for Bangladesh's public e-GP procurement portal.

**Full description:**
> e-GP Browser makes the public e-GP portal (www.eprocure.gov.bd) easier to use on Android phones.
>
> • Desktop-site mode for tender forms and wide tables
> • Tap-friendly menus (the portal's hover menus open with a tap)
> • File uploads and downloads (PDF, Word, Excel, ZIP) with a downloads manager
> • Pop-up windows, zoom, full screen, offline recovery and dark mode
> • Security first: locked to the official portal over HTTPS, certificate errors are never bypassed, no passwords stored, no ads, no tracking
>
> IMPORTANT: e-GP Browser is an independent app. It is not made by, affiliated with or endorsed by the Government of Bangladesh, BPPA, CPTU or the e-GP portal. All procurement information comes from https://www.eprocure.gov.bd and is shown unchanged.

**Category:** Business (or Productivity). **Contact email and privacy policy URL:** required by Play (see section 4).

## 3. App content declarations (play build)

| Play Console section | Answer for this build |
|---|---|
| Privacy policy | URL of your hosted copy of `app/src/main/assets/legal/privacy.html` (fill in the placeholders first) |
| Ads | No ads |
| App access | All functionality is available without special access. The portal's own login is optional and belongs to the portal. |
| Data safety: data collected | **No data collected** by the developer. Everything the user types goes directly to the e-GP portal; the app has no server. |
| Data safety: data shared | None |
| Data safety: security practices | Data is encrypted in transit (HTTPS only). No account, so deletion does not apply. |
| Content rating | Complete the IARC questionnaire (no objectionable content; the browser is restricted to one site) |
| Target audience | 18+ (professional procurement use) |
| Government apps declaration | Answer that the app is **not** from or on behalf of a government entity. Provide the disclaimer and the source URL www.eprocure.gov.bd. |
| Financial features | None |

## 4. Policy risks to resolve before submitting

* **Impersonation / misleading claims.** The name "e-GP" and the globe logo refer to a government service. Google requires apps that are not official to say so clearly, which the app does in the first-launch dialog, Settings and the description above. Google may still reject a non-government app whose name or icon could be read as official. Lower the risk by:
  * keeping the "independent app" disclaimer at the top of the description;
  * not using the Bangladesh flag, government seal or BPPA logo in the icon or screenshots (the screenshots show the portal itself, which is accurate);
  * being ready to explain the app's independence in an appeal.

  Obtaining written permission from BPPA/CPTU would remove most of this risk. This is a judgement call to make with legal advice.
* **Promotional banner.** The supplied promo image shows a mock-up of a stylised portal screen. Use it only as the feature graphic. For screenshots, Play requires real app screenshots, which are provided.
* **Web-view apps.** Play rejects apps that are just a web page without added value. This app adds desktop mode, tap-to-hover menus, a download manager, uploads, a security policy, and offline and error handling. Mention these in the description, as above.

## 5. Two builds, two channels

| | play | direct |
|---|---|---|
| Distribution | Google Play | Your website (APK) |
| Price | Free | 7-day trial, then ৳250 lifetime (PayStation) |
| Account | None | Google sign-in |
| Backend | None | `backend/` |
| Gradle tasks | `bundlePlayRelease` | `assembleDirectRelease` |

Both use package `bd.egpmobile.browser`, so a phone can have only one of them installed. Because they are signed with different keys (the Play app signing key vs your own key), one cannot update the other. Tell website users who switch to uninstall first.
