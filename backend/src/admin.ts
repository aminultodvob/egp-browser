import type { Pool } from './db.js';

export type AdminResult =
  | { ok: true; userId: string; email: string }
  | { ok: false; reason: 'USER_NOT_FOUND' | 'NO_COMPLIMENTARY_ACCESS' };

/**
 * Grants complimentary lifetime access to an existing account (the user must have signed in once).
 * Never touches payment records. A paid entitlement is left as it is.
 */
export async function grantComplimentary(pool: Pool, email: string, reason: string): Promise<AdminResult> {
  const user = await pool.query<{ id: string; email: string }>('SELECT id, email FROM users WHERE lower(email) = lower($1)', [email]);
  const row = user.rows[0];
  if (!row) return { ok: false, reason: 'USER_NOT_FOUND' };
  await pool.query(
    `INSERT INTO entitlements (user_id, kind, status, source_order_id, grant_reason)
     VALUES ($1, 'LIFETIME', 'ACTIVE', NULL, $2)
     ON CONFLICT (user_id, kind) DO UPDATE
       SET status = 'ACTIVE', granted_at = now(), revoked_at = NULL, revoke_reason = NULL,
           grant_reason = COALESCE(entitlements.grant_reason, EXCLUDED.grant_reason)
       WHERE entitlements.status <> 'ACTIVE'`,
    [row.id, `COMPLIMENTARY: ${reason}`],
  );
  return { ok: true, userId: row.id, email: row.email };
}

/** Revokes complimentary access only; paid access can only end through a PayStation refund. */
export async function revokeComplimentary(pool: Pool, email: string): Promise<AdminResult> {
  const { rows } = await pool.query<{ user_id: string; email: string }>(
    `UPDATE entitlements e SET status = 'REVOKED', revoked_at = now(), revoke_reason = 'COMPLIMENTARY_ENDED'
       FROM users u
      WHERE u.id = e.user_id AND lower(u.email) = lower($1)
        AND e.source_order_id IS NULL AND e.status = 'ACTIVE'
      RETURNING e.user_id, u.email`,
    [email],
  );
  const row = rows[0];
  return row ? { ok: true, userId: row.user_id, email: row.email } : { ok: false, reason: 'NO_COMPLIMENTARY_ACCESS' };
}
