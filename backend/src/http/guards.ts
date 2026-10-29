import { timingSafeEqual } from 'node:crypto';
import { Inject, Injectable, type CanActivate, type ExecutionContext } from '@nestjs/common';
import { ThrottlerGuard } from '@nestjs/throttler';
import type { FastifyRequest } from 'fastify';
import type { Config } from '../config.js';
import type { Pool } from '../db.js';
import { TokenService } from '../auth/tokens.js';
import { ApiError, unauthorized } from '../errors.js';
import { CONFIG, POOL } from './tokens.js';

export type AuthedRequest = FastifyRequest & { userId: string };

/** Requires a valid access token for an active account; sets request.userId. */
@Injectable()
export class AuthGuard implements CanActivate {
  constructor(
    @Inject(TokenService) private readonly tokens: TokenService,
    @Inject(POOL) private readonly pool: Pool,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<AuthedRequest>();
    const header = request.headers.authorization ?? '';
    if (!header.startsWith('Bearer ')) throw unauthorized();
    const userId = await this.tokens.verifyAccessToken(header.slice(7));
    const { rowCount } = await this.pool.query(`SELECT 1 FROM users WHERE id = $1 AND status = 'active'`, [userId]);
    if (!rowCount) throw unauthorized();
    request.userId = userId;
    return true;
  }
}

/** Protects the scheduler endpoint (cron-job.org) with a shared secret sent as a Bearer token. */
@Injectable()
export class CronGuard implements CanActivate {
  constructor(@Inject(CONFIG) private readonly config: Config) {}

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<FastifyRequest>();
    const header = request.headers.authorization ?? '';
    const given = Buffer.from(header.startsWith('Bearer ') ? header.slice(7) : '');
    const expected = Buffer.from(this.config.CRON_SECRET);
    if (given.length !== expected.length || !timingSafeEqual(given, expected)) {
      throw new ApiError(401, 'UNAUTHORIZED', 'Invalid scheduler credentials.');
    }
    return true;
  }
}

/** Rate limits per signed-in user where known (runs after AuthGuard), otherwise per client IP. */
@Injectable()
export class UserThrottlerGuard extends ThrottlerGuard {
  protected override async getTracker(request: Record<string, unknown>): Promise<string> {
    const userId = request.userId;
    return typeof userId === 'string' && userId ? `user:${userId}` : `ip:${String(request.ip)}`;
  }
}
