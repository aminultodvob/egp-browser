import { createHash, createHmac, randomBytes } from 'node:crypto';
import { SignJWT, jwtVerify } from 'jose';
import { ApiError, unauthorized } from '../errors.js';
import { transaction, type Pool } from '../db.js';

export const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
export const REFRESH_TOKEN_TTL_DAYS = 60;
const ISSUER = 'egp-browser-backend';
const AUDIENCE = 'egp-browser-app';

export const sha256 = (value: string): Buffer => createHash('sha256').update(value).digest();
export const hmac = (secret: string, value: string): Buffer => createHmac('sha256', secret).update(value).digest();

export interface IssuedTokens {
  accessToken: string;
  accessTokenExpiresIn: number;
  refreshToken: string;
}

/** Short-lived HS256 access tokens plus rotating, hashed refresh tokens. */
export class TokenService {
  private readonly key: Uint8Array;

  constructor(
    secret: string,
    private readonly pool: Pool,
  ) {
    this.key = new TextEncoder().encode(secret);
  }

  async verifyAccessToken(token: string): Promise<string> {
    try {
      const { payload } = await jwtVerify(token, this.key, { issuer: ISSUER, audience: AUDIENCE, algorithms: ['HS256'] });
      if (typeof payload.sub !== 'string') throw unauthorized();
      return payload.sub;
    } catch {
      throw unauthorized();
    }
  }

  /** Starts a new refresh-token family (a sign-in on one device). */
  async issue(userId: string): Promise<IssuedTokens> {
    return this.issueInFamily(this.pool, userId, crypto.randomUUID());
  }

  /**
   * Exchanges a refresh token for a new pair. A token can be used once; presenting a spent or
   * revoked token revokes its whole family, so a stolen token stops working for both parties.
   */
  async rotate(refreshToken: string): Promise<IssuedTokens & { userId: string }> {
    const hash = sha256(refreshToken);
    const outcome = await transaction(this.pool, async (client) => {
      const { rows } = await client.query<{
        id: string; user_id: string; family_id: string; used_at: Date | null; revoked_at: Date | null; expired: boolean;
      }>(
        `SELECT id, user_id, family_id, used_at, revoked_at, expires_at <= now() AS expired
           FROM refresh_tokens WHERE token_hash = $1 FOR UPDATE`,
        [hash],
      );
      const row = rows[0];
      if (!row) return { ok: false as const };
      if (row.used_at || row.revoked_at) {
        await client.query('UPDATE refresh_tokens SET revoked_at = now() WHERE family_id = $1 AND revoked_at IS NULL', [row.family_id]);
        return { ok: false as const };
      }
      if (row.expired) return { ok: false as const };
      const active = await client.query(`SELECT 1 FROM users WHERE id = $1 AND status = 'active'`, [row.user_id]);
      if (active.rowCount === 0) return { ok: false as const };
      await client.query('UPDATE refresh_tokens SET used_at = now() WHERE id = $1', [row.id]);
      const tokens = await this.issueInFamily(client, row.user_id, row.family_id);
      return { ok: true as const, tokens, userId: row.user_id };
    });
    if (!outcome.ok) throw new ApiError(401, 'INVALID_REFRESH_TOKEN', 'Sign in again.');
    return { ...outcome.tokens, userId: outcome.userId };
  }

  async revokeFamily(refreshToken: string): Promise<void> {
    await this.pool.query(
      `UPDATE refresh_tokens SET revoked_at = now()
        WHERE family_id = (SELECT family_id FROM refresh_tokens WHERE token_hash = $1) AND revoked_at IS NULL`,
      [sha256(refreshToken)],
    );
  }

  private async issueInFamily(
    db: { query: Pool['query'] },
    userId: string,
    familyId: string,
  ): Promise<IssuedTokens> {
    const refreshToken = randomBytes(32).toString('base64url');
    await db.query(
      `INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at)
       VALUES ($1, $2, $3, now() + make_interval(days => $4))`,
      [userId, familyId, sha256(refreshToken), REFRESH_TOKEN_TTL_DAYS],
    );
    const accessToken = await new SignJWT({})
      .setProtectedHeader({ alg: 'HS256' })
      .setSubject(userId)
      .setIssuer(ISSUER)
      .setAudience(AUDIENCE)
      .setIssuedAt()
      .setExpirationTime(`${ACCESS_TOKEN_TTL_SECONDS}s`)
      .sign(this.key);
    return { accessToken, accessTokenExpiresIn: ACCESS_TOKEN_TTL_SECONDS, refreshToken };
  }
}
