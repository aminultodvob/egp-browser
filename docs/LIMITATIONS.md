# Known limitations and troubleshooting

## Limitations

### Portal compatibility

* **No official certification.** BPPA tests the portal on Microsoft Edge 109+, Mozilla Firefox 113+ and Google Chrome 109+ at 1024×768 or larger, according to `SystemRequirement.jsp`. This app is not certified by BPPA. It uses Android System WebView, which is Chromium-based, with a Chrome desktop user agent, so it behaves close to, but not exactly like, desktop Chrome.
* **The portal has no mobile layout.** Its pages are fixed-width XHTML with no `viewport` meta tag. In *Desktop* mode (the default) pages render at the 980 px desktop width, zoomed out to fit, so you need to pinch-zoom or use landscape. *Mobile* mode reflows pages to the screen width. That is easier to read, but wide tables and multi-column forms may wrap or overlap. Switch modes per task.
* **The desktop user agent alone is not a desktop browser.** Desktop mode changes the user agent and the viewport. It does not add desktop-only plugins, a mouse or a larger screen.
* **Hover menus open on tap.** The top menu (Message Box, APP, Tender, CMS…) is a mouse-hover menu (`ddlevelsmenu.js`). Tap a menu header once to open its drop-down and tap it again to open the header's own page. Tap a drop-down item to go there, or tap anywhere else to close the menu. Menus built some other way are not covered.
* **Hardware tokens and signing software.** If a workflow requires a USB token, a desktop signing utility or a browser extension, it will not work on Android. The app does not emulate or bypass these.
* **Authenticated flows are untested.** Login, tender preparation, document upload, payment and authenticated downloads were not tested because no authorized or training-environment account was available. See `TESTING.md`.
* **Payment providers.** No external payment or authentication host is trusted by default. If a payment redirect opens in another app, which loses the session, add the provider's host in *Settings → Additional trusted sites*, but only after verifying it (see `SECURITY.md`).

### Downloads

Downloads run inside the app with the browser's own e-GP session (not through Android's DownloadManager). Every file is checked by its first bytes before it is saved, and PDFs open in the built-in viewer, so they work on phones without a PDF app.

* **"e-GP sent a web page instead of the document"**: the portal answered with a page (usually because the session ended, or the document must be opened from its page). Nothing broken is saved. Sign in on the portal again and download from the page.
* **Form-submitted downloads (POST).** WebView's download callback only provides the URL, so a document the portal generates only in reply to a form submission may produce the message above.
* **`blob:` and `data:` downloads** (files created by JavaScript inside the page) cannot be downloaded and are refused with a message.
* **Word, Excel and ZIP files** open in an installed app. If none is installed, the app says so; install a viewer from Google Play.
* Files are saved in `Downloads/e-GP`. Android 8–9 ask for storage permission at the first download; Android 13+ asks once for notification permission (progress and "tap to open").

### Session and lifecycle

* Rotation keeps the page and form state, because the activity handles configuration changes itself.
* If Android kills the app in the background, the page history is restored, but unsaved form input may be lost and a pending file-picker request is cancelled. Cookies persist on disk (`CookieManager.flush()` runs on pause), but the server may still have expired the session.
* Theme changes (light or dark) recreate the activity and reload the current page.
* Only one browser window is shown at a time. Pop-ups open over the main page with a close button, up to three nested.

### Pop-ups

* Pop-ups open in an overlay with their own close button and share the e-GP session. Pages that resize or position their pop-up window have no effect on Android.

## Troubleshooting

| Problem | What to do |
|---------|-----------|
| "Connection is not secure" | Check the device date, time and time zone. Avoid public Wi-Fi that shows a sign-in page. The app will not bypass certificate errors. |
| Page is very small | Pinch-zoom, rotate to landscape, use *Zoom in* in the menu, or raise *Settings → Text size*. |
| Table or form overlaps | Make sure *Desktop* is shown in the address bar badge. Mobile mode reflows fixed-width pages. |
| A link opens another app | It points outside `www.eprocure.gov.bd`. That is intentional. Your e-GP session is not shared with it. |
| File upload: file not listed | The picker shows all file types. Use the picker's menu to browse *Files* or *Downloads*. Cloud files (Drive, OneDrive) must be downloadable to the device. The portal itself enforces allowed types and sizes. |
| "This file is generated inside the page…" | The portal created the file with JavaScript. Try the same action on a computer, or use the portal's alternative download link if there is one. |
| Download failed with a server error | Your session may have expired or the link may have been single-use. Sign in again and download from the page. |
| Signed out unexpectedly | The e-GP server expires idle sessions. Clearing cookies or site storage also signs you out, and the app always asks before doing that. |
| Blank page after an update | Update *Android System WebView* and *Chrome* from Google Play, then use *Clear browsing data → Cached pages*. |
