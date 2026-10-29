import { Body, Controller, Get, HttpCode, Inject, Post, Req, UseGuards } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { TRIAL_DAYS, type Config } from '../config.js';
import { transaction, type Pool } from '../db.js';
import { ApiError } from '../errors.js';
import { hmac } from '../auth/tokens.js';
import { AccessService } from './access.service.js';
import { AuthGuard, UserThrottlerGuard, type AuthedRequest } from './guards.js';
import { trialBody } from './schemas.js';
import { CONFIG, POOL } from './tokens.js';

const MINUTE = 60_000;

@Controller('api')
@UseGuards(AuthGuard, UserThrottlerGuard)
export class AccessController {
  constructor(
    @Inject(POOL) private readonly pool: Pool,
    @Inject(CONFIG) private readonly config: Config,
    @Inject(AccessService) private readonly access: AccessService,
  ) {}

  @Get('entitlement')
  @Throttle({ default: { limit: 60, ttl: MINUTE } })
  entitlement(@Req() request: AuthedRequest) {
    return this.access.entitlementFor(request.userId);
  }

  /** Starts the 7-day trial once per account and device, timed by the database clock. Idempotent. */
  @Post('trial/activate')
  @HttpCode(200)
  @Throttle({ default: { limit: 10, ttl: MINUTE } })
  async activateTrial(@Req() request: AuthedRequest, @Body() body: unknown) {
    const { deviceId } = trialBody.parse(body ?? {});
    const subjectHash = await this.access.subjectHash(request.userId);
    const deviceHash = deviceId ? hmac(this.config.TRIAL_HASH_SECRET, `device:${deviceId}`) : null;
    await transaction(this.pool, async (client) => {
      // Serialise activations per subject so a double tap cannot create two claims.
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [subjectHash!.toString('hex')]);
      const existing = await client.query('SELECT 1 FROM trials WHERE user_id = $1', [request.userId]);
      if (existing.rowCount) return; // Never restarts.
      const lifetime = await client.query(
        `SELECT 1 FROM entitlements WHERE user_id = $1 AND kind = 'LIFETIME' AND status = 'ACTIVE'`,
        [request.userId],
      );
      if (lifetime.rowCount) return;
      const claimed = await client.query('SELECT 1 FROM trial_claims WHERE subject_hash = $1', [subjectHash]);
      if (claimed.rowCount) throw new ApiError(409, 'TRIAL_ALREADY_USED', 'The free trial was already used with this Google account.');
      if (deviceHash) {
        const { rows } = await client.query<{ n: string }>('SELECT count(*) AS n FROM trial_claims WHERE device_hash = $1', [deviceHash]);
        if (Number(rows[0]!.n) >= this.config.MAX_TRIALS_PER_DEVICE) {
          throw new ApiError(409, 'TRIAL_DEVICE_LIMIT', 'The free trial was already used on this device.');
        }
      }
      await client.query(
        `INSERT INTO trials (user_id, started_at, expires_at) VALUES ($1, now(), now() + make_interval(days => $2))`,
        [request.userId, TRIAL_DAYS],
      );
      await client.query('INSERT INTO trial_claims (subject_hash, device_hash) VALUES ($1, $2)', [subjectHash, deviceHash]);
    });
    return this.access.entitlementFor(request.userId);
  }
}
