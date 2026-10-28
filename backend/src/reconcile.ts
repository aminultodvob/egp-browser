/**
 * Reconciliation job (run from cron, e.g. every 15 minutes):
 *  - re-checks open orders with PayStation (catches missed IPNs and expiries);
 *  - re-checks paid orders from the last 30 days (catches refunds, which revoke access);
 *  - lists orders that need a person (VERIFICATION_REQUIRED), e.g. duplicate payments to refund.
 */
import { loadConfig } from './config.js';
import { createPool } from './db.js';
import { PayStationClient, ProviderUnavailableError } from './payments/paystation.js';
import { PaymentService } from './payments/service.js';

const config = loadConfig();
const pool = createPool(config.DATABASE_URL);
const service = new PaymentService(
  pool,
  new PayStationClient({
    baseUrl: config.PAYSTATION_BASE_URL,
    merchantId: config.PAYSTATION_MERCHANT_ID,
    password: config.PAYSTATION_PASSWORD,
    payWithCharge: config.PAYSTATION_PAY_WITH_CHARGE,
  }),
  { publicBaseUrl: config.PUBLIC_BASE_URL, checkoutHosts: [new URL(config.PAYSTATION_BASE_URL).hostname] },
);

try {
  const { rows } = await pool.query<{ invoice_number: string; status: string }>(
    `SELECT invoice_number, status FROM payment_orders
      WHERE (status IN ('PENDING', 'PROCESSING') AND created_at < now() - interval '2 minutes')
         OR (status = 'PAID' AND paid_at > now() - interval '30 days')
      ORDER BY created_at`,
  );
  const changes: string[] = [];
  let unavailable = 0;
  for (const row of rows) {
    try {
      const after = await service.verify(row.invoice_number);
      if (after.status !== row.status) changes.push(`${row.invoice_number}: ${row.status} -> ${after.status}`);
    } catch (error) {
      if (error instanceof ProviderUnavailableError) unavailable++;
      else throw error;
    }
  }
  const review = await pool.query<{ invoice_number: string; failure_reason: string | null; created_at: Date }>(
    `SELECT invoice_number, failure_reason, created_at FROM payment_orders WHERE status = 'VERIFICATION_REQUIRED' ORDER BY created_at`,
  );
  console.log(JSON.stringify({
    checked: rows.length,
    providerUnavailable: unavailable,
    changes,
    needsReview: review.rows.map((r) => ({ invoice: r.invoice_number, reason: r.failure_reason, createdAt: r.created_at })),
  }, null, 2));
} finally {
  await pool.end();
}
