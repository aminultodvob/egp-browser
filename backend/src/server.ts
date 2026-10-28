import { buildApp } from './app.js';
import { loadConfig } from './config.js';
import { createPool } from './db.js';
import { googleVerifier } from './auth/google.js';
import { PayStationClient } from './payments/paystation.js';

const config = loadConfig();
const pool = createPool(config.DATABASE_URL);
const app = await buildApp({
  config,
  pool,
  verifyGoogle: googleVerifier(config.GOOGLE_CLIENT_IDS),
  paystation: new PayStationClient({
    baseUrl: config.PAYSTATION_BASE_URL,
    merchantId: config.PAYSTATION_MERCHANT_ID,
    password: config.PAYSTATION_PASSWORD,
    payWithCharge: config.PAYSTATION_PAY_WITH_CHARGE,
  }),
});

const shutdown = async () => {
  await app.close();
  await pool.end();
  process.exit(0);
};
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

await app.listen({ host: config.HOST, port: config.PORT });
