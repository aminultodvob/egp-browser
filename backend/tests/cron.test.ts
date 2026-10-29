import assert from 'node:assert/strict';
import { after, before, describe, test } from 'node:test';
import { startHarness, type Harness } from './harness.js';

let h: Harness;
before(async () => {
  h = await startHarness();
});
after(async () => {
  await h?.close();
});

const cron = (secret?: string) =>
  h.app.inject({
    method: 'POST',
    url: '/api/cron/reconcile',
    headers: secret === undefined ? {} : { authorization: `Bearer ${secret}` },
  });

describe('cron-job.org reconciliation endpoint', () => {
  test('rejects calls without the shared secret', async () => {
    assert.equal((await cron()).statusCode, 401);
    assert.equal((await cron('wrong-secret')).statusCode, 401);
    assert.equal((await cron(h.cronSecret.slice(0, -1) + 'X')).statusCode, 401);
  });

  test('a missed IPN is caught: the open order is verified and access granted', async () => {
    const s = await h.signIn('cron-buyer');
    const created = await h.request('POST', '/api/payment/create', s.accessToken, { customerName: 'Karim', customerPhone: '01812345678' });
    const invoice = created.json.invoice as string;
    // The payment succeeded at PayStation but the IPN never arrived; the order is older than 2 minutes.
    h.paystation.setStatus(invoice, 'success');
    await h.pool.query(`UPDATE payment_orders SET created_at = now() - interval '5 minutes' WHERE invoice_number = $1`, [invoice]);

    const res = await cron(h.cronSecret);
    assert.equal(res.statusCode, 200);
    const body = res.json();
    assert.ok(body.changes.includes(`${invoice}: PENDING -> PAID`), JSON.stringify(body));
    assert.equal((await h.request('GET', '/api/entitlement', s.accessToken)).json.state, 'LIFETIME');
  });

  test('reports orders that need a person', async () => {
    const s = await h.signIn('cron-review');
    const created = await h.request('POST', '/api/payment/create', s.accessToken, { customerName: 'Rina', customerPhone: '01912345678' });
    h.paystation.setStatus(created.json.invoice, 'success', '1.00');
    await h.pool.query(`UPDATE payment_orders SET created_at = now() - interval '5 minutes' WHERE invoice_number = $1`, [created.json.invoice]);
    const body = (await cron(h.cronSecret)).json();
    assert.ok(body.needsReview.some((r: { invoice: string; reason: string }) => r.invoice === created.json.invoice && r.reason === 'AMOUNT_MISMATCH'));
  });

  test('health check works without authentication', async () => {
    const res = await h.app.inject({ method: 'GET', url: '/health' });
    assert.equal(res.statusCode, 200);
    assert.deepEqual(res.json(), { ok: true });
  });
});
