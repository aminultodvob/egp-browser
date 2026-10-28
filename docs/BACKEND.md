# Backend: accounts, trial, PayStation payments

Used **only by the direct (website) build**. The Google Play build is free and never contacts this server.

Stack: Node.js 20+ / TypeScript, Fastify 5, PostgreSQL 13+, `jose` (JWT/JWS), `zod` (validation). Production dependencies: `npm audit` reports 0 vulnerabilities (October 2026).

## How it fits together

```
Android app ── Google sign-in (Credential Manager) ──► Google ID token
     │
     ├─ POST /api/auth/google ─────────────► backend verifies the token with Google's keys
     ├─ POST /api/trial/activate ───────────► trial starts on the DATABASE clock (7 days)
     ├─ POST /api/payment/create ───────────► backend → PayStation /initiate-payment (server-side credentials)
     │      ◄─ paymentUrl (PayStation hosted checkout, opened in a Custom Tab)
     │                       PayStation ── IPN ──► POST /api/payment/ipn ─┐
     │                       payer browser ──► GET /payment/return/{inv} ─┤ both only TRIGGER:
     ├─ GET /api/payment/status/{inv} ──────────────────────────────────── ┤ backend → PayStation /transaction-status
     │                                                                     ┘ → PAID only if success + exact ৳250 + matching invoice
     └─ GET /api/entitlement ─────────────► state + ES256-signed token (verified in the app)
```

**Proof of payment is only ever PayStation's server-to-server `transaction-status` answer.** The IPN is unsigned (per PayStation's docs), so it is logged and used to trigger a status check. The return page and the app's own report work the same way.

## PayStation integration (from https://paystation.com.bd/documentation)

| Call | Implementation |
|------|----------------|
| `POST {base}/initiate-payment`, form fields `merchantId, password, invoice_number, currency=BDT, payment_amount=250, pay_with_charge=0, reference, cust_name, cust_phone, cust_email, callback_url, checkout_items` | `PayStationClient.initiate`. Success requires `status_code == "200"`, `status == "success"` and a `payment_url`. |
| `POST {base}/transaction-status`, header `merchantId`, form `invoice_number` | `PayStationClient.transactionStatus`. `data.trx_status` is normalised to lower case. |
| IPN: JSON POST to our URL, sent for successful transactions only, retried on non-2xx | `POST /api/payment/ipn`. Returns 200 when handled, 503 when PayStation's status API is unreachable (so PayStation retries), 404 for unknown invoices. |

Verified live against the PayStation **sandbox** on 2026-10-28. `initiate-payment` returned a checkout on `sandbox.paystation.com.bd`, and `transaction-status` for that unpaid invoice returned `processing` with `payment_amount "250.00"` (`npx tsx scripts/sandbox-smoke.ts`). No production call has been made.

### Status mapping

| PayStation `trx_status` | Our order status | Entitlement |
|---|---|---|
| `success`, invoice matches, amount exactly 250, `trx_id` present, not a duplicate | `PAID` | lifetime granted (once) |
| `success` but amount/invoice/trx_id wrong, or a second paid order for the same account | `VERIFICATION_REQUIRED` | none (manual review / refund) |
| `processing` | `PROCESSING` | none |
| `failed` | `FAILED` (a `PAID` order is never downgraded; an anomaly is logged instead) | none |
| `refund` | `REFUNDED` | lifetime revoked |
| not found (`2001`) for 24 h | `EXPIRED` | none |
| network error / 5xx / non-JSON | unchanged | unchanged |

Other states: `PENDING` (created), `CANCELLED` (reserved for manual use).

## API reference

All JSON. Errors are `{"error": {"code", "message"}}`, and messages are safe to show to users. Authenticated endpoints need `Authorization: Bearer <accessToken>`.

| Method & path | Auth | Body | Success | Errors |
|---|---|---|---|---|
| `POST /api/auth/google` | – | `{idToken}` | `{accessToken, accessTokenExpiresIn: 900, refreshToken, user:{id,email,name}, entitlement}` | 401 `INVALID_GOOGLE_TOKEN`, `GOOGLE_EMAIL_UNVERIFIED`; 403 `ACCOUNT_SUSPENDED`; 429 |
| `POST /api/auth/refresh` | – | `{refreshToken}` | `{accessToken, accessTokenExpiresIn, refreshToken}` (rotated) | 401 `INVALID_REFRESH_TOKEN` (also on reuse, which revokes the family) |
| `POST /api/auth/logout` | – | `{refreshToken}` | 204 | – |
| `GET /api/me` | yes | – | `{id, email, name, createdAt}` | 401 |
| `DELETE /api/account` | yes | – | 204 | 401 |
| `GET /api/entitlement` | yes | – | Entitlement (below) | 401 |
| `POST /api/trial/activate` | yes | `{deviceId?}` | Entitlement | 409 `TRIAL_ALREADY_USED`, `TRIAL_DEVICE_LIMIT`; 400 for any other field |
| `POST /api/payment/create` | yes | `{customerName, customerPhone}` (`01[3-9]XXXXXXXX`) | `{invoice, status, amount: 250, currency: "BDT", paidAt, paymentUrl}` | 409 `ALREADY_OWNED`; 502 `PAYMENT_PROVIDER_ERROR` / `PAYMENT_PROVIDER_UNAVAILABLE`; 400 (including any client-sent amount) |
| `GET /api/payment/status/{invoice}` | yes, owner only | – | `{invoice, status, amount, currency, paidAt, providerReachable, entitlement}` | 404 (also for other users' invoices) |
| `POST /api/payment/ipn` | – (PayStation) | PayStation IPN JSON | `{status:"success"}` | 404 unknown invoice, 503 retry |
| `GET /payment/return/{invoice}` | – | – | HTML result page (no scripts, strict CSP) | – |
| `GET /health` | – | – | `{ok:true}` | 500 if the DB is down |

**Entitlement:**

```json
{
  "state": "TRIAL_NOT_STARTED | TRIAL_ACTIVE | TRIAL_EXPIRED | LIFETIME",
  "serverTime": "2026-10-28T10:00:00.000Z",
  "trial": {"startedAt": "...", "expiresAt": "..."} | null,
  "trialAlreadyUsed": true,
  "lifetime": {"grantedAt": "..."} | null,
  "pendingPayment": {"invoice": "EGP...", "status": "PROCESSING"} | null,
  "price": {"amount": 250, "currency": "BDT"},
  "token": "<ES256 JWS: sub, st, texp, iat, exp>"
}
```

The app verifies `token` with the public key built into it. Offline it then honours lifetime access for **30 days** after the last check, and a trial until its server-recorded expiry, using time anchored to the server clock. "Payment pending" and "service unavailable" are reported through `pendingPayment` and `providerReachable`.

Rate limits per minute: sign-in 20/IP, refresh 60/IP, trial activation 10/user, payment creation 5/user, status 60/user, IPN 120/IP, return page 30/IP.

## Data model

`migrations/001_init.sql`: `users`, `refresh_tokens` (SHA-256 hashes only), `trials`, `trial_claims` (HMAC of Google subject and device ID, which survives account deletion), `payment_orders` (`invoice_number` UNIQUE, `provider_trx_id` UNIQUE, **one PAID order per user** partial unique index), `entitlements` (UNIQUE user+kind), and `payment_events` (append-only audit log, including raw IPN bodies). No passwords, PINs, OTPs or card data are ever stored, and the payer's phone number is not stored.

## Local development

```bash
cd backend
npm ci
npm run keygen            # prints JWT_SECRET, TRIAL_HASH_SECRET, ENTITLEMENT_PRIVATE_KEY and the app's public key
cp .env.example .env      # fill in the values; PayStation SANDBOX credentials
docker run -d --name egp-pg -e POSTGRES_USER=egp -e POSTGRES_PASSWORD=egp -e POSTGRES_DB=egp_browser -p 5432:5432 postgres:17
npm run migrate:dev
npm run dev               # http://localhost:8080 (the debug app reaches it at http://10.0.2.2:8080 from the emulator)
npm test                  # 38 tests; starts its own embedded PostgreSQL, no Docker needed
```

`npm run dev` and `migrate:dev` read the environment, so load `.env` with your shell or a tool such as `dotenv-cli`. Do not commit `.env`.

## Staging and production

1. **Host.** Any container host runs the `Dockerfile` (it applies migrations, then starts). Use a managed PostgreSQL 13+ with automatic daily backups. Put the API behind HTTPS (the platform's TLS or a reverse proxy) and set `TRUST_PROXY=true` only behind that proxy. A single small instance is enough at launch. Compare current hosting prices yourself; they change often, and PayStation's transaction fees are separate (see your merchant agreement).
2. **Secrets.** Store `JWT_SECRET`, `TRIAL_HASH_SECRET`, `ENTITLEMENT_PRIVATE_KEY` and `PAYSTATION_*` in the host's secret manager. Use different values for staging and production. Rotating `ENTITLEMENT_PRIVATE_KEY` requires an app update with the new public key.
3. **PayStation.** Staging: `PAYSTATION_BASE_URL=https://sandbox.paystation.com.bd` with sandbox credentials. Production: `https://api.paystation.com.bd` with your approved merchant ID and password. **Send PayStation your IPN URL** `https://<your-api>/api/payment/ipn`. The `callback_url` (`https://<your-api>/payment/return/<invoice>`) is sent with each payment.
4. **Reconciliation.** Run `npm run reconcile` (`node dist/reconcile.js`) every 15 minutes, using the host's cron or scheduled job. It catches missed IPNs, expires abandoned orders, detects refunds and lists orders that need review.
5. **Monitoring.** Alert on `/health` failures, HTTP 5xx rates, and any `VERIFICATION_REQUIRED` orders from the reconcile output.
6. **Go-live test.** Make one real ৳250 payment with your own account, following PayStation's go-live procedure. Confirm `PAID` and `LIFETIME`, then refund it through PayStation and confirm reconciliation marks it `REFUNDED` and revokes access.

## Refunds and disputes (operations)

Refunds are made in the PayStation merchant panel. The reconcile job sees `trx_status = refund` and revokes the matching entitlement automatically. Orders flagged `DUPLICATE_PAYMENT_REFUND_DUE` must be refunded by you. All changes are recorded in `payment_events`.
