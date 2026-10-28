import { SignJWT, importPKCS8 } from 'jose';
import { CURRENCY, PRICE_BDT } from './config.js';
import type { Queryable } from './db.js';

export type AccessState = 'TRIAL_NOT_STARTED' | 'TRIAL_ACTIVE' | 'TRIAL_EXPIRED' | 'LIFETIME';

export interface Entitlement {
  userId: string;
  state: AccessState;
  serverTime: Date;
  trial: { startedAt: Date; expiresAt: Date } | null;
  /** The account (or a deleted account with the same Google identity) already used its trial. */
  trialAlreadyUsed: boolean;
  lifetime: { grantedAt: Date } | null;
  pendingPayment: { invoice: string; status: string } | null;
}

/** How long a signed entitlement may be trusted offline by the app. */
export const OFFLINE_LIFETIME_DAYS = 30;
const OFFLINE_LOCKED_DAYS = 1;
const DAY_MS = 86_400_000;

/** Computes the authoritative access state using the database clock. */
export async function loadEntitlement(db: Queryable, userId: string, subjectHash: Buffer | null): Promise<Entitlement> {
  const { rows } = await db.query<{
    now: Date;
    lifetime_granted_at: Date | null;
    trial_started_at: Date | null;
    trial_expires_at: Date | null;
    claimed: boolean;
    pending_invoice: string | null;
    pending_status: string | null;
  }>(
    `SELECT now() AS now,
            (SELECT granted_at FROM entitlements
              WHERE user_id = $1 AND kind = 'LIFETIME' AND status = 'ACTIVE') AS lifetime_granted_at,
            t.started_at AS trial_started_at, t.expires_at AS trial_expires_at,
            EXISTS (SELECT 1 FROM trial_claims WHERE subject_hash = $2) AS claimed,
            p.invoice_number AS pending_invoice, p.status AS pending_status
       FROM (SELECT 1) AS one
       LEFT JOIN trials t ON t.user_id = $1
       LEFT JOIN LATERAL (
            SELECT invoice_number, status FROM payment_orders
             WHERE user_id = $1 AND status IN ('PENDING', 'PROCESSING', 'VERIFICATION_REQUIRED')
               AND created_at > now() - interval '24 hours'
             ORDER BY created_at DESC LIMIT 1) p ON true`,
    [userId, subjectHash],
  );
  const r = rows[0]!;
  const trial = r.trial_started_at && r.trial_expires_at ? { startedAt: r.trial_started_at, expiresAt: r.trial_expires_at } : null;
  let state: AccessState;
  if (r.lifetime_granted_at) state = 'LIFETIME';
  else if (trial) state = trial.expiresAt > r.now ? 'TRIAL_ACTIVE' : 'TRIAL_EXPIRED';
  else state = r.claimed ? 'TRIAL_EXPIRED' : 'TRIAL_NOT_STARTED';
  return {
    userId,
    state,
    serverTime: r.now,
    trial,
    trialAlreadyUsed: trial !== null || r.claimed,
    lifetime: r.lifetime_granted_at ? { grantedAt: r.lifetime_granted_at } : null,
    pendingPayment: r.pending_invoice ? { invoice: r.pending_invoice, status: r.pending_status! } : null,
  };
}

/**
 * Signs a compact ES256 JWS the app verifies with the embedded public key, so a cached
 * entitlement cannot be forged or edited on the device. Claims: sub, st (state), texp (trial expiry).
 */
export class EntitlementSigner {
  private constructor(private readonly key: CryptoKey) {}

  static async create(privateKeyPem: string): Promise<EntitlementSigner> {
    return new EntitlementSigner(await importPKCS8(privateKeyPem, 'ES256'));
  }

  async sign(e: Entitlement): Promise<string> {
    const issuedAt = Math.floor(e.serverTime.getTime() / 1000);
    const validUntilMs =
      e.state === 'LIFETIME' ? e.serverTime.getTime() + OFFLINE_LIFETIME_DAYS * DAY_MS
      : e.state === 'TRIAL_ACTIVE' ? e.trial!.expiresAt.getTime()
      : e.serverTime.getTime() + OFFLINE_LOCKED_DAYS * DAY_MS;
    return new SignJWT({ st: e.state, texp: e.trial ? Math.floor(e.trial.expiresAt.getTime() / 1000) : null })
      .setProtectedHeader({ alg: 'ES256', typ: 'JWT' })
      .setSubject(e.userId)
      .setIssuer('egp-browser-backend')
      .setAudience('egp-browser-app')
      .setIssuedAt(issuedAt)
      .setExpirationTime(Math.floor(validUntilMs / 1000))
      .sign(this.key);
  }
}

export async function entitlementResponse(e: Entitlement, signer: EntitlementSigner) {
  return {
    state: e.state,
    serverTime: e.serverTime.toISOString(),
    trial: e.trial ? { startedAt: e.trial.startedAt.toISOString(), expiresAt: e.trial.expiresAt.toISOString() } : null,
    trialAlreadyUsed: e.trialAlreadyUsed,
    lifetime: e.lifetime ? { grantedAt: e.lifetime.grantedAt.toISOString() } : null,
    pendingPayment: e.pendingPayment,
    price: { amount: PRICE_BDT, currency: CURRENCY },
    token: await signer.sign(e),
  };
}
