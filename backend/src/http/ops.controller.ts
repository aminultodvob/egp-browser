import { Controller, Get, HttpCode, Inject, Post, UseGuards } from '@nestjs/common';
import { SkipThrottle } from '@nestjs/throttler';
import type { Pool } from '../db.js';
import { PaymentService } from '../payments/service.js';
import { reconcile } from '../payments/reconcile.service.js';
import { CronGuard } from './guards.js';
import { POOL } from './tokens.js';

@Controller()
export class OpsController {
  constructor(
    @Inject(POOL) private readonly pool: Pool,
    @Inject(PaymentService) private readonly payments: PaymentService,
  ) {}

  /** Liveness and database check (also usable as a keep-warm ping). */
  @Get('health')
  @SkipThrottle()
  async health() {
    await this.pool.query('SELECT 1');
    return { ok: true };
  }

  /**
   * Scheduled reconciliation for cron-job.org (every 15 minutes, header
   * "Authorization: Bearer <CRON_SECRET>"). Bounded to finish well inside cron-job.org's request timeout;
   * unfinished orders are handled by the next run.
   */
  @Post('api/cron/reconcile')
  @HttpCode(200)
  @SkipThrottle()
  @UseGuards(CronGuard)
  reconcile() {
    return reconcile(this.pool, this.payments, { maxOrders: 25, budgetMs: 20_000 });
  }
}
