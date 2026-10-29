import type { Pool } from '../db.js';
import { ProviderUnavailableError } from './paystation.js';
import type { PaymentService } from './service.js';

export interface ReconcileResult {
  checked: number;
  remaining: number;
  providerUnavailable: number;
  changes: string[];
  needsReview: { invoice: string; reason: string | null; createdAt: string }[];
}

/**
 * Re-checks payments with PayStation:
 *  - open orders (catches missed IPNs and expires abandoned checkouts);
 *  - paid orders from the last 30 days (catches refunds, which revoke access);
 * and lists orders that need a person (VERIFICATION_REQUIRED, e.g. a duplicate payment to refund).
 * Bounded by [maxOrders] and [budgetMs] so an HTTP-triggered run finishes inside the caller's timeout;
 * anything left is picked up by the next run (oldest checks first).
 */
export async function reconcile(
  pool: Pool,
  payments: PaymentService,
  options: { maxOrders: number; budgetMs: number },
): Promise<ReconcileResult> {
  const started = Date.now();
  const { rows } = await pool.query<{ invoice_number: string; status: string }>(
    `SELECT invoice_number, status FROM payment_orders
      WHERE (status IN ('PENDING', 'PROCESSING') AND created_at < now() - interval '2 minutes')
         OR (status = 'PAID' AND paid_at > now() - interval '30 days')
      ORDER BY last_checked_at ASC NULLS FIRST, created_at ASC`,
  );
  const changes: string[] = [];
  let checked = 0;
  let unavailable = 0;
  for (const row of rows) {
    if (checked >= options.maxOrders || Date.now() - started > options.budgetMs) break;
    checked++;
    try {
      const after = await payments.verify(row.invoice_number);
      if (after.status !== row.status) changes.push(`${row.invoice_number}: ${row.status} -> ${after.status}`);
    } catch (error) {
      if (error instanceof ProviderUnavailableError) unavailable++;
      else throw error;
    }
  }
  const review = await pool.query<{ invoice_number: string; failure_reason: string | null; created_at: Date }>(
    `SELECT invoice_number, failure_reason, created_at FROM payment_orders
      WHERE status = 'VERIFICATION_REQUIRED' ORDER BY created_at`,
  );
  return {
    checked,
    remaining: rows.length - checked,
    providerUnavailable: unavailable,
    changes,
    needsReview: review.rows.map((r) => ({
      invoice: r.invoice_number,
      reason: r.failure_reason,
      createdAt: r.created_at.toISOString(),
    })),
  };
}
