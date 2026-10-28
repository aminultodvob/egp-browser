# Deployment plan (direct build backend)

Goal: run the backend and public website securely at the lowest sensible cost, close to users in Bangladesh, then connect Google sign-in and PayStation production.

## Recommended setup

**One DigitalOcean droplet in the Bangalore (BLR1) region**, running everything in Docker (`deploy/docker-compose.yml`):

| Container | Role |
|---|---|
| `caddy` | HTTPS with free automatic Let's Encrypt certificates; serves `api.<domain>` and the website `<domain>` |
| `api` | Backend; applies database migrations on start |
| `db` | PostgreSQL 17; never exposed to the internet |
| `reconcile` | Re-checks payments with PayStation every 15 minutes |
| `backup` | Nightly `pg_dump`, keeps 14 days on the server |

Why: Bangalore is DigitalOcean's closest datacenter to Bangladesh, and one small server is enough for launch traffic.

### Cost (checked October 2026; confirm on the provider's pricing page)

| Item | Approx. monthly |
|---|---|
| DigitalOcean Basic droplet, 1 GB RAM / 1 vCPU / 25 GB SSD / 1,000 GB transfer | ~$6 |
| Droplet weekly backups (+20%) | ~$1.20 |
| Domain name | varies by registrar (yearly) |
| TLS certificates (Let's Encrypt via Caddy) | free |
| **Total** | **≈ $7–8 / month** plus the domain |

PayStation transaction fees are separate and set by your merchant agreement. If traffic grows, resize the droplet in place (to 2 GB) or move PostgreSQL to a managed database.

Alternatives considered: Hetzner Singapore (its small plans are no longer sold there and the remaining ones cost more, with tighter traffic limits) and Neon managed PostgreSQL (free tier available; region close to Bangladesh not confirmed). The Compose stack runs on any Ubuntu server, so you can switch later.

Sources: [DigitalOcean droplet pricing](https://www.digitalocean.com/pricing/droplets), [DigitalOcean pricing explained](https://www.fewservers.com/posts/digitalocean-droplet-pricing-explained/), [Hetzner price adjustment 2026](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/), [Hetzner cloud prices](https://cloudtally.eu/hetzner-cloud-prices), [Neon pricing overview](https://agentdeals.dev/vendor/neon).

---

## Step 1: Domain and server (about 30 minutes)

1. **Domain.** Register a domain you control. Avoid names that suggest an official government site (for example anything ending in `.gov.bd`, or "eprocure"). You will use two hostnames: `example.com` (website and policies) and `api.example.com` (backend).
2. **SSH key.** On your PC (Git Bash):
   ```bash
   ssh-keygen -t ed25519 -C "egp-server"
   ```
3. **Droplet.** In DigitalOcean, create a droplet: Ubuntu 24.04 LTS, Basic, 1 GB, region **Bangalore**, authentication **SSH key** (paste `~/.ssh/id_ed25519.pub`), and enable **weekly backups**. Note its IP address.
4. **DNS.** At your registrar, create `A` records for `example.com` and `api.example.com` pointing to the droplet IP. Wait until `nslookup api.example.com` returns that IP.
5. **Harden the server** (firewall, automatic updates, Docker, swap, non-root user, key-only SSH):
   ```bash
   scp deploy/setup-server.sh root@SERVER_IP:/root/
   ssh root@SERVER_IP "bash /root/setup-server.sh"
   ```
   From now on log in as `ssh egp@SERVER_IP`.

## Step 2: Google sign-in (about 20 minutes)

In [Google Cloud Console](https://console.cloud.google.com/):

1. **Create a project**, for example "e-GP Browser".
2. **Google Auth Platform → Branding:** app name `e-GP Browser`, your support email, logo (`store/play-icon-512.png`), home page `https://example.com`, privacy policy `https://example.com/legal/privacy.html`, terms `https://example.com/legal/terms.html`, and authorized domain `example.com`.
3. **Audience:** user type **External**. Then **publish the app** (move it from *Testing* to *In production*). In Testing mode only listed test users can sign in. The app only asks for basic sign-in information (name, email, profile), which normally does not need Google's extra verification. Follow any prompts the console shows.
4. **Clients → Create client → Web application**, named "e-GP Browser backend", with no redirect URIs. Copy its **Client ID** (`…apps.googleusercontent.com`). It goes into:
   * the backend as `GOOGLE_CLIENT_IDS`;
   * the app build as `EGP_GOOGLE_WEB_CLIENT_ID`.
5. **Clients → Create client → Android**, package `bd.egpmobile.browser`, with the **SHA-1 of the key that signs your website APK**:
   ```bash
   keytool -genkeypair -v -keystore egp-direct.jks -alias egp-direct -keyalg RSA -keysize 4096 -validity 10000   # once; back it up
   keytool -list -v -keystore egp-direct.jks -alias egp-direct | grep SHA1
   ```
   To test debug builds too, add a second Android client with the SHA-1 of `~/.android/debug.keystore` (alias `androiddebugkey`, password `android`). Without a matching Android client, sign-in fails with a developer error.

## Step 3: PayStation production (needs PayStation's support team)

1. In the PayStation merchant panel, get your **production Merchant ID and password**. They go only into `deploy/backend.env` on the server, never into the app, git or chat.
2. **Email PayStation support** with:
   * your IPN URL: `https://api.example.com/api/payment/ipn`;
   * your callback domain: `https://api.example.com/payment/return/…` (ask whether the domain must be whitelisted);
   * a request to confirm which methods are enabled (bKash, Nagad, Rocket, cards) and whether the IPN is active on your account.
3. Keep `PAYSTATION_PAY_WITH_CHARGE=0`, so the merchant pays the gateway charge and the verified amount is exactly ৳250.

## Step 4: Secrets and configuration (on your PC)

```bash
cd backend
npm ci
npm run keygen     # prints JWT_SECRET, TRIAL_HASH_SECRET, ENTITLEMENT_PRIVATE_KEY and EGP_ENTITLEMENT_PUBLIC_KEY
```

Keep the output in a password manager. The private values go to the server; the **public** key goes into the app build.

Create the two server files from the examples (do not commit them):

* `deploy/.env`: `API_DOMAIN`, `SITE_DOMAIN`, `ACME_EMAIL`, and `POSTGRES_PASSWORD` (`openssl rand -base64 32`)
* `deploy/backend.env`: secrets from keygen, `GOOGLE_CLIENT_IDS`, and the `PAYSTATION_*` values

Generate the public website with your real publisher details:

```bash
cd backend
EGP_DEVELOPER_NAME="Your Name or Company" EGP_SUPPORT_EMAIL=support@example.com \
EGP_PUBLISHER_WEBSITE=https://example.com EGP_POLICY_EFFECTIVE_DATE=2026-11-01 \
npx tsx scripts/render-site.ts
```

## Step 5: First deployment

From the project folder on your PC (Git Bash):

```bash
tar --exclude=node_modules --exclude=dist -czf - backend deploy | ssh egp@SERVER_IP "mkdir -p egp-browser && tar -xzf - -C egp-browser"
ssh egp@SERVER_IP "cd egp-browser/deploy && chmod 600 .env backend.env && docker compose up -d --build"
```

Check it:

```bash
ssh egp@SERVER_IP "cd egp-browser/deploy && docker compose ps && docker compose logs --tail=50 caddy api"
curl https://api.example.com/health        # {"ok":true}
```

Open `https://example.com/legal/privacy.html` in a browser. The certificate should be valid.

## Step 6: Staging test with the sandbox, then switch to production

1. **Sandbox first.** Set `PAYSTATION_BASE_URL=https://sandbox.paystation.com.bd` and the sandbox credentials in `backend.env`, then `docker compose up -d`.
2. Build the website APK pointed at your server:
   ```bash
   ./gradlew assembleDirectRelease -PEGP_API_BASE_URL=https://api.example.com/ \
     -PEGP_GOOGLE_WEB_CLIENT_ID=XXXX.apps.googleusercontent.com -PEGP_ENTITLEMENT_PUBLIC_KEY=MFkw... \
     -PEGP_SUPPORT_EMAIL=support@example.com -PEGP_DEVELOPER_NAME="..." \
     -PEGP_PUBLISHER_WEBSITE=https://example.com -PEGP_POLICY_EFFECTIVE_DATE=2026-11-01
   ```
   Sign it with `egp-direct.jks` (via `keystore.properties`).
3. On a phone, check that: Google sign-in works; the trial starts with 7 days; *Unlock* opens PayStation's sandbox page; a sandbox payment leads to **Lifetime access**; and the server logs show `ENTITLEMENT_GRANTED`.
4. **Production.** Switch `backend.env` to `https://api.paystation.com.bd` with your production credentials, then `docker compose up -d`. Make **one real ৳250 payment** with your own account and confirm lifetime access. Refund it in the PayStation panel and confirm that within 15 minutes the reconcile job marks it `REFUNDED` and access is removed:
   ```bash
   docker compose logs reconcile --tail=30
   ```
5. **Publish the APK.** Copy the signed APK to `deploy/site/download/e-GP-Browser.apk`, rerun `render-site.ts` (the download link appears), and upload `deploy/site` again.

## Operations

| Task | How |
|---|---|
| Update the backend | Re-run the two Step 5 commands (`up -d --build`); migrations run automatically |
| Backups | Nightly dumps in `deploy/backups/` plus droplet backups. Copy dumps off the server regularly: `scp egp@SERVER_IP:egp-browser/deploy/backups/*.dump ./backups/` |
| Restore test (do it once) | `docker compose exec -T db pg_restore -U egp -d egp_browser --clean < backup.dump` on a test server |
| Monitoring | A free uptime checker on `https://api.example.com/health`, and DigitalOcean's CPU and disk alerts |
| Payments needing attention | `docker compose logs reconcile`; `needsReview` lists `VERIFICATION_REQUIRED` invoices (e.g. a duplicate payment to refund) |
| Logs | `docker compose logs api`. Docker's default log rotation is unbounded, so set `"log-opts": {"max-size": "10m", "max-file": "9"}` in `/etc/docker/daemon.json` to match the 90-day policy roughly |
| Rotate the entitlement key | Only with an app update that contains the new public key |

## Security checklist before launch

- [ ] `.env` and `backend.env` are `chmod 600`, are not in git, and are backed up in a password manager
- [ ] `curl -I http://api.example.com` redirects to HTTPS; the certificate is valid
- [ ] The PostgreSQL port is not reachable from the internet (`nc -zv SERVER_IP 5432` fails)
- [ ] SSH password login is disabled and root login is off (done by `setup-server.sh`)
- [ ] Google OAuth app is published (not in Testing)
- [ ] PayStation has confirmed the IPN URL
- [ ] A restore from backup has been tested
- [ ] The real ৳250 purchase and refund test has passed
