import assert from 'node:assert/strict';
import { after, before, describe, test } from 'node:test';
import { grantComplimentary, revokeComplimentary } from '../src/admin.js';
import { startHarness, type Harness } from './harness.js';

let h: Harness;
before(async () => {
  h = await startHarness();
});
after(async () => {
  await h?.close();
});

const state = async (token: string) => (await h.request('GET', '/api/entitlement', token)).json.state;

describe('complimentary access (admin)', () => {
  test('grants lifetime access without creating a payment record, and can be revoked', async () => {
    const s = await h.signIn('reviewer', 'Reviewer@Example.com');
    assert.equal(await state(s.accessToken), 'TRIAL_NOT_STARTED');
    const granted = await grantComplimentary(h.pool, 'reviewer@example.com', 'Google Play review');
    assert.equal(granted.ok, true);
    assert.equal(await state(s.accessToken), 'LIFETIME');
    const orders = await h.pool.query('SELECT count(*)::int AS n FROM payment_orders WHERE user_id = $1', [s.userId]);
    assert.equal(orders.rows[0].n, 0, 'no fake payment rows');
    assert.equal((await h.request('POST', '/api/payment/create', s.accessToken, { customerName: 'Rev', customerPhone: '01712345678' })).status, 409);

    assert.equal((await revokeComplimentary(h.pool, 'reviewer@example.com')).ok, true);
    assert.equal(await state(s.accessToken), 'TRIAL_NOT_STARTED');
  });

  test('unknown accounts are reported, not created', async () => {
    assert.deepEqual(await grantComplimentary(h.pool, 'nobody@example.com', 'x'), { ok: false, reason: 'USER_NOT_FOUND' });
  });

  test('revoking never touches paid access', async () => {
    const s = await h.signIn('paid-user');
    const created = await h.request('POST', '/api/payment/create', s.accessToken, { customerName: 'Paid', customerPhone: '01712345678' });
    h.paystation.setStatus(created.json.invoice, 'success');
    await h.request('POST', '/api/payment/ipn', undefined, { invoice_number: created.json.invoice });
    assert.equal(await state(s.accessToken), 'LIFETIME');
    assert.deepEqual(await revokeComplimentary(h.pool, 'paid-user@example.com'), { ok: false, reason: 'NO_COMPLIMENTARY_ACCESS' });
    assert.equal(await state(s.accessToken), 'LIFETIME');
  });

  test('a database constraint forbids entitlements with neither a payment nor a reason', async () => {
    const s = await h.signIn('constraint-user');
    await assert.rejects(
      h.pool.query(`INSERT INTO entitlements (user_id, kind, status) VALUES ($1, 'LIFETIME', 'ACTIVE')`, [s.userId]),
      /entitlements_source_or_reason/,
    );
  });
});
