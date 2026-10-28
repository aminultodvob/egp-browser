/**
 * Live check against PayStation's SANDBOX using the real client:
 * creates a checkout link, then queries its status. Moves no money.
 *
 *   PAYSTATION_MERCHANT_ID=... PAYSTATION_PASSWORD=... npx tsx scripts/sandbox-smoke.ts
 *
 * Use the sandbox credentials published in PayStation's documentation. Never production credentials.
 */
import { PayStationClient } from '../src/payments/paystation.js';
import { newInvoiceNumber } from '../src/payments/service.js';

const merchantId = process.env.PAYSTATION_MERCHANT_ID;
const password = process.env.PAYSTATION_PASSWORD;
if (!merchantId || !password) throw new Error('Set PAYSTATION_MERCHANT_ID and PAYSTATION_PASSWORD (sandbox)');

const client = new PayStationClient({ baseUrl: 'https://sandbox.paystation.com.bd', merchantId, password, payWithCharge: '0' });
const invoice = newInvoiceNumber();
const created = await client.initiate({
  invoiceNumber: invoice,
  amount: 250,
  customerName: 'Sandbox Tester',
  customerPhone: '01700000000',
  customerEmail: 'sandbox@example.com',
  callbackUrl: `https://example.com/payment/return/${invoice}`,
  reference: 'e-GP Browser sandbox smoke test',
  checkoutItems: 'e-GP Browser lifetime access (sandbox)',
});
console.log('initiate:', created.ok ? { ok: true, checkoutHost: new URL(created.paymentUrl).hostname } : created);
console.log('status:', await client.transactionStatus(invoice));
