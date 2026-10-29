/**
 * Command-line reconciliation (same logic as POST /api/cron/reconcile, without the time budget).
 * Usually triggered by cron-job.org through the API; this script is for manual runs.
 */
import { loadConfig } from './config.js';
import { createPool } from './db.js';
import { PayStationClient } from './payments/paystation.js';
import { PaymentService } from './payments/service.js';
import { reconcile } from './payments/reconcile.service.js';

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
  console.log(JSON.stringify(await reconcile(pool, service, { maxOrders: Number.MAX_SAFE_INTEGER, budgetMs: Number.MAX_SAFE_INTEGER }), null, 2));
} finally {
  await pool.end();
}
