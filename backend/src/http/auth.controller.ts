import { Body, Controller, Delete, Get, HttpCode, Inject, Post, Req, UseGuards } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import type { Pool } from '../db.js';
import { transaction } from '../db.js';
import { ApiError } from '../errors.js';
import type { GoogleVerifier } from '../auth/google.js';
import { TokenService } from '../auth/tokens.js';
import { AccessService } from './access.service.js';
import { AuthGuard, UserThrottlerGuard, type AuthedRequest } from './guards.js';
import { googleSignInBody, refreshBody } from './schemas.js';
import { GOOGLE_VERIFIER, POOL } from './tokens.js';

const MINUTE = 60_000;

@Controller('api')
export class AuthController {
  constructor(
    @Inject(POOL) private readonly pool: Pool,
    @Inject(GOOGLE_VERIFIER) private readonly verifyGoogle: GoogleVerifier,
    @Inject(TokenService) private readonly tokens: TokenService,
    @Inject(AccessService) private readonly access: AccessService,
  ) {}

  /** Exchanges a Google ID token (from Android Credential Manager) for our session tokens. */
  @Post('auth/google')
  @HttpCode(200)
  @UseGuards(UserThrottlerGuard)
  @Throttle({ default: { limit: 20, ttl: MINUTE } })
  async google(@Body() body: unknown) {
    const { idToken } = googleSignInBody.parse(body);
    const identity = await this.verifyGoogle(idToken);
    const { rows } = await this.pool.query<{ id: string; email: string; display_name: string | null; status: string }>(
      `INSERT INTO users (google_sub, email, display_name, last_login_at) VALUES ($1, $2, $3, now())
       ON CONFLICT (google_sub) DO UPDATE
         SET email = EXCLUDED.email, display_name = COALESCE(EXCLUDED.display_name, users.display_name), last_login_at = now()
       RETURNING id, email, display_name, status`,
      [identity.sub, identity.email, identity.name ?? null],
    );
    const user = rows[0]!;
    if (user.status !== 'active') throw new ApiError(403, 'ACCOUNT_SUSPENDED', 'This account is suspended. Contact support.');
    return {
      ...(await this.tokens.issue(user.id)),
      user: { id: user.id, email: user.email, name: user.display_name },
      entitlement: await this.access.entitlementFor(user.id),
    };
  }

  @Post('auth/refresh')
  @HttpCode(200)
  @UseGuards(UserThrottlerGuard)
  @Throttle({ default: { limit: 60, ttl: MINUTE } })
  async refresh(@Body() body: unknown) {
    const { refreshToken } = refreshBody.parse(body);
    const { userId: _userId, ...issued } = await this.tokens.rotate(refreshToken);
    return issued;
  }

  @Post('auth/logout')
  @HttpCode(204)
  @UseGuards(UserThrottlerGuard)
  @Throttle({ default: { limit: 60, ttl: MINUTE } })
  async logout(@Body() body: unknown): Promise<void> {
    const { refreshToken } = refreshBody.parse(body);
    await this.tokens.revokeFamily(refreshToken);
  }

  @Get('me')
  @UseGuards(AuthGuard)
  async me(@Req() request: AuthedRequest) {
    const { rows } = await this.pool.query<{ id: string; email: string; display_name: string | null; created_at: Date }>(
      'SELECT id, email, display_name, created_at FROM users WHERE id = $1',
      [request.userId],
    );
    const user = rows[0]!;
    return { id: user.id, email: user.email, name: user.display_name, createdAt: user.created_at.toISOString() };
  }

  /**
   * Deletes the account and personal data. Payment records are kept without the account link for
   * accounting; a pseudonymous trial claim is kept so the free trial cannot be restarted.
   */
  @Delete('account')
  @HttpCode(204)
  @UseGuards(AuthGuard, UserThrottlerGuard)
  @Throttle({ default: { limit: 5, ttl: MINUTE } })
  async deleteAccount(@Req() request: AuthedRequest): Promise<void> {
    await transaction(this.pool, async (client) => {
      await client.query('UPDATE payment_orders SET user_id = NULL WHERE user_id = $1', [request.userId]);
      await client.query('DELETE FROM entitlements WHERE user_id = $1', [request.userId]);
      await client.query('DELETE FROM users WHERE id = $1', [request.userId]);
    });
  }
}
