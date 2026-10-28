import assert from 'node:assert/strict';
import { after, before, describe, test } from 'node:test';
import { compactVerify } from 'jose';
import { startHarness, type Harness } from './harness.js';

let h: Harness;
before(async () => {
  h = await startHarness();
});
after(async () => {
  await h?.close();
});

const buyer = { customerName: 'Rahim Uddin', customerPhone: '01712345678' };

async function checkout(sub: string) {
  const s = await h.signIn(sub);
  const res = await h.request('POST', '/api/payment/create', s.accessToken, buyer);
  assert.equal(res.status, 200, JSON.stringify(res.json));
  return { s, invoice: res.json.invoice as string, res };
}

const ipn = (invoice: string, amount = 250) =>
  h.request('POST', '/api/payment/ipn', undefined, {
    invoice_number: invoice, trx_status: 'Success', trx_id: 'FORGED', trx_amount: amount,
    order_date_time: '2026-10-28 10:00:00', payment_method: 'bKash',
  });

const entitlement = async (token: string) => (await h.request('GET', '/api/entitlement', token)).json;

describe('checkout', () => {
  test('creates a pending ৳250 order with a server invoice and PayStation checkout URL', async () => {
    const { res, invoice } = await checkout('buyer-create');
    assert.match(invoice, /^EGP[A-Z0-9]{8,}$/);
    assert.equal(res.json.amount, 250);
    assert.equal(res.json.currency, 'BDT');
    assert.equal(res.json.status, 'PENDING');
    assert.match(res.json.paymentUrl, /^https:\/\/sandbox\.paystation\.com\.bd\/checkout\//);
    const call = h.paystation.initiateCalls.at(-1)!;
    assert.equal(call.amount, 250);
    assert.equal(call.invoiceNumber, invoice);
    assert.equal(call.customerPhone, '01712345678');
    assert.equal(call.customerEmail, 'buyer-create@example.com');
    assert.equal(call.callbackUrl, `https://api.test.example/payment/return/${invoice}`);
  });

  test('a client-supplied price or currency is rejected', async () => {
    const s = await h.signIn('buyer-price');
    const res = await h.request('POST', '/api/payment/create', s.accessToken, { ...buyer, amount: 1 });
    assert.equal(res.status, 400);
  });

  test('invalid phone numbers are rejected before contacting PayStation', async () => {
    const s = await h.signIn('buyer-phone');
    const calls = h.paystation.initiateCalls.length;
    const res = await h.request('POST', '/api/payment/create', s.accessToken, { ...buyer, customerPhone: '12345' });
    assert.equal(res.status, 400);
    assert.equal(h.paystation.initiateCalls.length, calls);
  });

  test('checkout initiation failure fails safely', async () => {
    const s = await h.signIn('buyer-provider-error');
    h.paystation.initiateResult = () => ({ ok: false, code: '1008', message: 'Duplicate invoice number.' });
    const res = await h.request('POST', '/api/payment/create', s.accessToken, buyer);
    assert.equal(res.status, 502);
    h.paystation.initiateResult = () => 'unavailable';
    assert.equal((await h.request('POST', '/api/payment/create', s.accessToken, buyer)).status, 502);
    h.paystation.initiateResult = (req) => ({ ok: true, paymentUrl: `https://evil.example/checkout/${req.invoiceNumber}` });
    assert.equal((await h.request('POST', '/api/payment/create', s.accessToken, buyer)).status, 502, 'untrusted checkout host');
    h.paystation.initiateResult = (req) => ({ ok: true, paymentUrl: `https://sandbox.paystation.com.bd/checkout/${req.invoiceNumber}/t` });
    const { rows } = await h.pool.query(`SELECT o.status FROM payment_orders o JOIN users u ON u.id = o.user_id WHERE u.google_sub = 'buyer-provider-error'`);
    assert.ok(rows.every((r) => r.status === 'FAILED'));
    assert.equal((await entitlement(s.accessToken)).state, 'TRIAL_NOT_STARTED');
  });

  test('an open checkout is reused instead of creating duplicate invoices', async () => {
    const { s, invoice } = await checkout('buyer-reuse');
    const again = await h.request('POST', '/api/payment/create', s.accessToken, buyer);
    assert.equal(again.json.invoice, invoice);
  });
});

describe('verification', () => {
  test('pending and cancelled payments do not unlock access', async () => {
    const { s, invoice } = await checkout('buyer-pending');
    h.paystation.setStatus(invoice, 'processing');
    let st = await h.request('GET', `/api/payment/status/${invoice}`, s.accessToken);
    assert.equal(st.json.status, 'PROCESSING');
    assert.equal(st.json.entitlement.state, 'TRIAL_NOT_STARTED');
    await new Promise((r) => setTimeout(r, 3100));
    h.paystation.setStatus(invoice, 'failed');
    st = await h.request('GET', `/api/payment/status/${invoice}`, s.accessToken);
    assert.equal(st.json.status, 'FAILED');
    assert.notEqual(st.json.entitlement.state, 'LIFETIME');
  });

  test('a verified ৳250 payment unlocks lifetime access, with a signed entitlement', async () => {
    const { s, invoice } = await checkout('buyer-paid');
    h.paystation.setStatus(invoice, 'success', '250.00');
    assert.equal((await ipn(invoice)).status, 200);
    const e = await entitlement(s.accessToken);
    assert.equal(e.state, 'LIFETIME');
    const { payload } = await compactVerify(e.token, h.entitlementPublicKey);
    const claims = JSON.parse(new TextDecoder().decode(payload));
    assert.equal(claims.st, 'LIFETIME');
    assert.equal(claims.sub, s.userId);
    const tampered = e.token.replace(/\.([^.]+)\./, (_: string, p: string) => `.${p.slice(0, -2)}AA.`);
    await assert.rejects(compactVerify(tampered, h.entitlementPublicKey));
  });

  test('a forged IPN without a real payment does not unlock access', async () => {
    const { s, invoice } = await checkout('buyer-forged');
    // PayStation knows nothing about a payment for this invoice.
    assert.equal((await ipn(invoice)).status, 200);
    assert.notEqual((await entitlement(s.accessToken)).state, 'LIFETIME');
    const { rows } = await h.pool.query('SELECT status FROM payment_orders WHERE invoice_number = $1', [invoice]);
    assert.equal(rows[0].status, 'PENDING');
  });

  test('IPN for an unknown invoice is rejected', async () => {
    assert.equal((await ipn('EGPNOSUCHINVOICE1')).status, 404);
  });

  test('the success redirect page alone does not unlock access', async () => {
    const { s, invoice } = await checkout('buyer-redirect');
    const page = await h.app.inject({ method: 'GET', url: `/payment/return/${invoice}?status=success&trx_id=FAKE` });
    assert.equal(page.statusCode, 200);
    assert.match(page.body, /Payment being confirmed/);
    assert.match(page.headers['content-security-policy'] as string, /default-src 'none'/);
    assert.notEqual((await entitlement(s.accessToken)).state, 'LIFETIME');
  });

  test('a wrong amount is rejected and flagged for review', async () => {
    const { s, invoice } = await checkout('buyer-amount');
    h.paystation.setStatus(invoice, 'success', '1.00');
    await ipn(invoice);
    assert.notEqual((await entitlement(s.accessToken)).state, 'LIFETIME');
    const { rows } = await h.pool.query('SELECT status, failure_reason FROM payment_orders WHERE invoice_number = $1', [invoice]);
    assert.deepEqual(rows[0], { status: 'VERIFICATION_REQUIRED', failure_reason: 'AMOUNT_MISMATCH' });
  });

  test('a mismatched invoice from the provider is rejected', async () => {
    const { s, invoice } = await checkout('buyer-invoice');
    h.paystation.setStatus(invoice, 'success', '250.00', 'TRXMISMATCH', 'EGPSOMEONEELSE123');
    await ipn(invoice);
    assert.notEqual((await entitlement(s.accessToken)).state, 'LIFETIME');
  });

  test('duplicate notifications grant access exactly once', async () => {
    const { s, invoice } = await checkout('buyer-dup');
    h.paystation.setStatus(invoice, 'success');
    const results = await Promise.all([ipn(invoice), ipn(invoice), ipn(invoice), ipn(invoice)]);
    assert.ok(results.every((r) => r.status === 200));
    assert.equal((await entitlement(s.accessToken)).state, 'LIFETIME');
    const grants = await h.pool.query(
      `SELECT count(*)::int AS n FROM payment_events WHERE invoice_number = $1 AND kind = 'ENTITLEMENT_GRANTED'`, [invoice]);
    assert.equal(grants.rows[0].n, 1);
    const ents = await h.pool.query('SELECT count(*)::int AS n FROM entitlements WHERE user_id = $1', [s.userId]);
    assert.equal(ents.rows[0].n, 1);
  });

  test('provider downtime never marks a payment as paid; PayStation is asked to retry', async () => {
    const { s, invoice } = await checkout('buyer-down');
    h.paystation.statuses.set(invoice, 'unavailable');
    assert.equal((await ipn(invoice)).status, 503);
    const st = await h.request('GET', `/api/payment/status/${invoice}`, s.accessToken);
    assert.equal(st.json.status, 'PENDING');
    assert.equal(st.json.providerReachable, false);
    // Retry after recovery succeeds.
    h.paystation.setStatus(invoice, 'success');
    assert.equal((await ipn(invoice)).status, 200);
    assert.equal((await entitlement(s.accessToken)).state, 'LIFETIME');
  });

  test("a user cannot read another user's invoice", async () => {
    const { invoice } = await checkout('buyer-owner');
    const other = await h.signIn('buyer-intruder');
    const res = await h.request('GET', `/api/payment/status/${invoice}`, other.accessToken);
    assert.equal(res.status, 404);
  });

  test('a user who already owns lifetime access is not charged again', async () => {
    const { s, invoice } = await checkout('buyer-owned');
    h.paystation.setStatus(invoice, 'success');
    await ipn(invoice);
    const calls = h.paystation.initiateCalls.length;
    const res = await h.request('POST', '/api/payment/create', s.accessToken, buyer);
    assert.equal(res.status, 409);
    assert.equal(res.json.error.code, 'ALREADY_OWNED');
    assert.equal(h.paystation.initiateCalls.length, calls);
  });

  test('lifetime access survives reinstall and a new phone (new sign-in)', async () => {
    const { invoice } = await checkout('buyer-newphone');
    h.paystation.setStatus(invoice, 'success');
    await ipn(invoice);
    const newDevice = await h.signIn('buyer-newphone');
    assert.equal((await entitlement(newDevice.accessToken)).state, 'LIFETIME');
  });

  test('lifetime purchase overrides an expired trial', async () => {
    const s = await h.signIn('buyer-expired');
    await h.request('POST', '/api/trial/activate', s.accessToken, {});
    await h.pool.query(`UPDATE trials SET started_at = now() - interval '8 days', expires_at = now() - interval '1 day' WHERE user_id = $1`, [s.userId]);
    assert.equal((await entitlement(s.accessToken)).state, 'TRIAL_EXPIRED');
    const res = await h.request('POST', '/api/payment/create', s.accessToken, buyer);
    h.paystation.setStatus(res.json.invoice, 'success');
    await ipn(res.json.invoice);
    assert.equal((await entitlement(s.accessToken)).state, 'LIFETIME');
  });

  test('a refund reported by PayStation revokes access', async () => {
    const { s, invoice } = await checkout('buyer-refund');
    h.paystation.setStatus(invoice, 'success');
    await ipn(invoice);
    assert.equal((await entitlement(s.accessToken)).state, 'LIFETIME');
    h.paystation.setStatus(invoice, 'refund');
    // Reconciliation re-checks paid orders; simulate one check.
    const { PaymentService } = await import('../src/payments/service.js');
    await new PaymentService(h.pool, h.paystation, { publicBaseUrl: 'https://api.test.example', checkoutHosts: [] }).verify(invoice);
    assert.notEqual((await entitlement(s.accessToken)).state, 'LIFETIME');
    const { rows } = await h.pool.query('SELECT status FROM payment_orders WHERE invoice_number = $1', [invoice]);
    assert.equal(rows[0].status, 'REFUNDED');
  });

  test('a second successful payment for the same account is flagged for refund, not granted twice', async () => {
    const s = await h.signIn('buyer-twice');
    const first = await h.request('POST', '/api/payment/create', s.accessToken, buyer);
    // Expire the reuse window so a second invoice is created.
    await h.pool.query(`UPDATE payment_orders SET created_at = now() - interval '1 hour' WHERE invoice_number = $1`, [first.json.invoice]);
    const second = await h.request('POST', '/api/payment/create', s.accessToken, buyer);
    assert.notEqual(second.json.invoice, first.json.invoice);
    h.paystation.setStatus(first.json.invoice, 'success', '250.00', 'TRXFIRST');
    h.paystation.setStatus(second.json.invoice, 'success', '250.00', 'TRXSECOND');
    await Promise.all([ipn(first.json.invoice), ipn(second.json.invoice)]);
    const { rows } = await h.pool.query(
      `SELECT status, failure_reason FROM payment_orders WHERE invoice_number = ANY($1) ORDER BY status`,
      [[first.json.invoice, second.json.invoice]],
    );
    assert.deepEqual(rows.map((r) => r.status).sort(), ['PAID', 'VERIFICATION_REQUIRED']);
    assert.equal(rows.find((r) => r.status === 'VERIFICATION_REQUIRED')!.failure_reason, 'DUPLICATE_PAYMENT_REFUND_DUE');
  });
});
