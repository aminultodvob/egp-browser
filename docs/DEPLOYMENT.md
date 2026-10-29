# Deployment (free stack: Neon + Render + cron-job.org)

| Piece | Service | Plan | Region |
|---|---|---|---|
| PostgreSQL | [Neon](https://neon.tech) | Free | AWS Singapore (ap-southeast-1) |
| NestJS API (`backend/`, Docker) | [Render](https://render.com) web service | Free | Singapore (same region as the database) |
| Public website (`site/`: policies, account deletion) | Render static site | Free | global CDN |
| Scheduler | [cron-job.org](https://cron-job.org) | Free | – |

`render.yaml` (a Render Blueprint) defines both Render services.

### What "free" means here (read before launch)

* **Render free web services sleep after 15 minutes without traffic**, and the first request then takes about a minute. The cron-job.org job below calls the API **every 10 minutes**, which keeps it awake and also runs payment reconciliation. One always-on service uses about 744 of the 750 free instance hours per month, so run only **one** free web service in the workspace.
* Render says free instances are not intended for production. When the app earns money, move the API to Render's smallest paid plan (same Blueprint; change `plan: free`). No code changes are needed.
* **Neon Free** pauses compute when idle and wakes on the next query (well under a second), with monthly compute and storage limits. This app's database is tiny. Watch usage in the Neon console.
* cron-job.org free jobs time out after about 30 seconds. The reconcile endpoint stops after 20 seconds and 25 orders, and the next run continues from there.

---

## 1. Neon (database), already created

The tables were created on your Neon database (migrations `001_init.sql` and `002_complimentary_access.sql`).

1. **Reset the database password now.** It was shared in a chat message. In the Neon console: *Roles → neondb_owner → Reset password*.
2. From *Connect*, copy two connection strings:
   * **Pooled** (host contains `-pooler`): becomes `DATABASE_URL`.
   * **Direct** (same, without `-pooler`): becomes `DATABASE_DIRECT_URL`, used only for migrations.
3. In **both**, change `sslmode=require` to **`sslmode=verify-full`**. This makes the driver always verify Neon's certificate (otherwise a future driver version would stop verifying it).

## 2. Secrets (on your PC)

```bash
cd backend
npm ci
npm run keygen
```

This prints `JWT_SECRET`, `TRIAL_HASH_SECRET`, `CRON_SECRET`, `ENTITLEMENT_PRIVATE_KEY` and `EGP_ENTITLEMENT_PUBLIC_KEY`. Keep them in a password manager. The first four go into Render (step 5); the public key goes into the app build (step 8).

## 3. Google sign-in (Google Cloud Console)

1. Create a project, for example "e-GP Browser".
2. **Google Auth Platform → Branding:** app name, support email, logo, home page and privacy policy URL. Use the URLs of your Render static site from step 6, e.g. `https://egp-browser-site.onrender.com/legal/privacy.html`.
3. **Audience:** External, then **Publish app**. In *Testing* mode only listed test users can sign in.
4. **Clients → Create → Web application:** copy the client ID. It becomes `GOOGLE_CLIENT_IDS` (Render) and `EGP_GOOGLE_WEB_CLIENT_ID` (app build).
5. **Clients → Create → Android:** package `bd.egpmobile.browser`, plus SHA-1 fingerprints for:
   * your **upload key** (for local release testing): `keytool -list -v -keystore egp-upload.jks -alias egp-upload`;
   * the **Play App Signing key**: Play Console → *Test and release → App integrity → App signing key certificate → SHA-1*. Apps installed from Play are signed with this key, so sign-in fails for Play users without it;
   * optionally the debug key (`~/.android/debug.keystore`, alias `androiddebugkey`, password `android`).

## 4. PayStation

Use your production merchant ID and password (they go only into Render). Email PayStation support with:

* your IPN URL: `https://<api-host>/api/payment/ipn`;
* your callback domain `https://<api-host>/payment/return/...` (ask whether it must be whitelisted);
* a request to confirm the enabled methods (bKash, Nagad, Rocket, cards).

## 5. Render: API and website

1. Push this repository to GitHub (it is already there).
2. Render → **New → Blueprint** → select the repository. Render reads `render.yaml` and creates `egp-browser-api` and `egp-browser-site`.
3. Enter the values Render asks for (they are never stored in git):

| Variable | Value |
|---|---|
| `PUBLIC_BASE_URL` | the API's Render URL, e.g. `https://egp-browser-api.onrender.com` (or your custom domain) |
| `DATABASE_URL` / `DATABASE_DIRECT_URL` | from step 1 (with `sslmode=verify-full`) |
| `JWT_SECRET`, `TRIAL_HASH_SECRET`, `CRON_SECRET`, `ENTITLEMENT_PRIVATE_KEY` | from step 2 |
| `GOOGLE_CLIENT_IDS` | web client ID from step 3 |
| `PAYSTATION_MERCHANT_ID`, `PAYSTATION_PASSWORD` | from PayStation |

4. Deploy. Each start runs migrations (already-applied ones are skipped), then starts NestJS. Check that `https://<api-host>/health` returns `{"ok":true}`.

## 6. Public website (policies and account deletion)

Generate the pages with your real details, commit them, and Render publishes them:

```bash
cd backend
EGP_DEVELOPER_NAME="Your name or company" EGP_SUPPORT_EMAIL=support@your-domain \
EGP_PUBLISHER_WEBSITE=https://egp-browser-site.onrender.com EGP_POLICY_EFFECTIVE_DATE=2026-11-01 \
npx tsx scripts/render-site.ts
cd .. && git add site && git commit -m "Publish policy pages" && git push
```

URLs for Play Console and Google: `/legal/privacy.html`, `/legal/terms.html`, `/legal/refund.html` and **`/legal/delete-account.html`** (Play requires an account-deletion web page).

## 7. cron-job.org

Create one job:

| Field | Value |
|---|---|
| URL | `https://<api-host>/api/cron/reconcile` |
| Schedule | every **10 minutes** |
| Request method | **POST** |
| Headers | `Authorization: Bearer <CRON_SECRET>` |
| Notifications | on failure (e-mail) |

It keeps the free API awake, catches missed PayStation notifications, expires abandoned checkouts and revokes refunded purchases. The response lists `needsReview` invoices, such as duplicate payments to refund. Check it from cron-job.org's history.

## 8. Build the app for Play

```bash
./gradlew bundleRelease -PEGP_API_BASE_URL=https://<api-host>/ \
  -PEGP_GOOGLE_WEB_CLIENT_ID=XXXX.apps.googleusercontent.com \
  -PEGP_ENTITLEMENT_PUBLIC_KEY=MFkw... \
  -PEGP_SUPPORT_EMAIL=support@your-domain -PEGP_DEVELOPER_NAME="..." \
  -PEGP_PUBLISHER_WEBSITE=https://egp-browser-site.onrender.com -PEGP_POLICY_EFFECTIVE_DATE=2026-11-01
```

The bundle must be signed with your upload key (see PLAY_STORE.md). Then do the go-live test: sign in on a real phone, check the 7-day trial, make one real ৳250 payment, confirm lifetime access, refund it in PayStation, and confirm that the next cron run revokes it.

## Admin: complimentary access (Play review, support)

```bash
cd backend && npm run build
node --env-file=.env dist/grant.js grant reviewer@example.com "Google Play review"
node --env-file=.env dist/grant.js revoke reviewer@example.com
```

The account must have signed in once. No payment record is created. Paid access can only end through a PayStation refund.

## Paid alternative

`deploy/` contains a self-hosted Docker Compose stack (Caddy HTTPS, API, PostgreSQL, backups) for one small VPS, for example a DigitalOcean droplet in Bangalore (about $7–8/month). It uses its own PostgreSQL instead of Neon. Put `CRON_SECRET` and the other secrets in `deploy/backend.env`.
