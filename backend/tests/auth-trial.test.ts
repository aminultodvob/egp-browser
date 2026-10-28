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

describe('accounts', () => {
  test('Google sign-in creates the account and returns tokens and entitlement', async () => {
    const res = await h.request('POST', '/api/auth/google', undefined, { idToken: 'test-google-token:alice:alice@example.com' });
    assert.equal(res.status, 200);
    assert.ok(res.json.accessToken && res.json.refreshToken);
    assert.equal(res.json.user.email, 'alice@example.com');
    assert.equal(res.json.entitlement.state, 'TRIAL_NOT_STARTED');
  });

  test('invalid Google token is rejected', async () => {
    const res = await h.request('POST', '/api/auth/google', undefined, { idToken: 'forged-token-forged-token-forged' });
    assert.equal(res.status, 401);
  });

  test('protected endpoints need a valid access token', async () => {
    assert.equal((await h.request('GET', '/api/entitlement')).status, 401);
    assert.equal((await h.request('GET', '/api/entitlement', 'not-a-jwt')).status, 401);
  });

  test('refresh tokens rotate; reusing a spent token revokes the whole family', async () => {
    const first = await h.signIn('rotate');
    const r1 = await h.request('POST', '/api/auth/refresh', undefined, { refreshToken: first.refreshToken });
    assert.equal(r1.status, 200);
    const r2 = await h.request('POST', '/api/auth/refresh', undefined, { refreshToken: r1.json.refreshToken });
    assert.equal(r2.status, 200);
    // Replaying the first (spent) token = theft signal.
    const replay = await h.request('POST', '/api/auth/refresh', undefined, { refreshToken: first.refreshToken });
    assert.equal(replay.status, 401);
    // ...and the newest token of that family no longer works either.
    const after = await h.request('POST', '/api/auth/refresh', undefined, { refreshToken: r2.json.refreshToken });
    assert.equal(after.status, 401);
  });

  test('sign-in attempts are rate limited per IP', async () => {
    const statuses: number[] = [];
    for (let i = 0; i < 22; i++) {
      statuses.push((await h.request('POST', '/api/auth/google', undefined, { idToken: 'test-google-token:bot:bot@example.com' }, '192.0.2.50')).status);
    }
    assert.equal(statuses.filter((s) => s === 200).length, 20);
    assert.equal(statuses.at(-1), 429);
  });

  test('logout revokes the refresh token', async () => {
    const s = await h.signIn('logout');
    assert.equal((await h.request('POST', '/api/auth/logout', undefined, { refreshToken: s.refreshToken })).status, 204);
    assert.equal((await h.request('POST', '/api/auth/refresh', undefined, { refreshToken: s.refreshToken })).status, 401);
  });
});

describe('7-day trial', () => {
  test('first activation starts the trial once, using the server clock, for exactly 7 days', async () => {
    const s = await h.signIn('trial-once');
    const a = await h.request('POST', '/api/trial/activate', s.accessToken, {});
    assert.equal(a.status, 200);
    assert.equal(a.json.state, 'TRIAL_ACTIVE');
    const started = Date.parse(a.json.trial.startedAt);
    const expires = Date.parse(a.json.trial.expiresAt);
    assert.equal(expires - started, 7 * 24 * 3600 * 1000);
    assert.ok(Math.abs(Date.parse(a.json.serverTime) - started) < 5_000);

    const again = await h.request('POST', '/api/trial/activate', s.accessToken, {});
    assert.equal(again.json.trial.startedAt, a.json.trial.startedAt, 'second activation must not restart the trial');
  });

  test('client-supplied times are rejected (device clock cannot extend the trial)', async () => {
    const s = await h.signIn('trial-clock');
    const res = await h.request('POST', '/api/trial/activate', s.accessToken, { startedAt: '2099-01-01T00:00:00Z' });
    assert.equal(res.status, 400);
  });

  test('trial expires after seven elapsed days', async () => {
    const s = await h.signIn('trial-expire');
    await h.request('POST', '/api/trial/activate', s.accessToken, {});
    await h.pool.query(
      `UPDATE trials SET started_at = now() - interval '7 days 1 second', expires_at = now() - interval '1 second' WHERE user_id = $1`,
      [s.userId],
    );
    const e = await h.request('GET', '/api/entitlement', s.accessToken);
    assert.equal(e.json.state, 'TRIAL_EXPIRED');
  });

  test('signing in again on a new device (or after clearing data / reinstalling) keeps the same trial', async () => {
    const s1 = await h.signIn('trial-reinstall');
    const a = await h.request('POST', '/api/trial/activate', s1.accessToken, { deviceId: 'device-A' });
    const s2 = await h.signIn('trial-reinstall');
    const e = await h.request('GET', '/api/entitlement', s2.accessToken);
    assert.equal(e.json.trial.startedAt, a.json.trial.startedAt);
    const again = await h.request('POST', '/api/trial/activate', s2.accessToken, { deviceId: 'device-B' });
    assert.equal(again.json.trial.startedAt, a.json.trial.startedAt);
  });

  test('deleting the account and signing up again does not grant a new trial', async () => {
    const s1 = await h.signIn('trial-delete');
    await h.request('POST', '/api/trial/activate', s1.accessToken, {});
    assert.equal((await h.request('DELETE', '/api/account', s1.accessToken)).status, 204);
    assert.equal((await h.request('GET', '/api/entitlement', s1.accessToken)).status, 401, 'old tokens die with the account');
    const s2 = await h.signIn('trial-delete');
    const e = await h.request('GET', '/api/entitlement', s2.accessToken);
    assert.equal(e.json.state, 'TRIAL_EXPIRED');
    assert.equal(e.json.trialAlreadyUsed, true);
    const res = await h.request('POST', '/api/trial/activate', s2.accessToken, {});
    assert.equal(res.status, 409);
    assert.equal(res.json.error.code, 'TRIAL_ALREADY_USED');
  });

  test('a second account on the same device gets no new trial', async () => {
    const a = await h.signIn('device-owner-1');
    assert.equal((await h.request('POST', '/api/trial/activate', a.accessToken, { deviceId: 'shared-device' })).status, 200);
    const b = await h.signIn('device-owner-2');
    const res = await h.request('POST', '/api/trial/activate', b.accessToken, { deviceId: 'shared-device' });
    assert.equal(res.status, 409);
    assert.equal(res.json.error.code, 'TRIAL_DEVICE_LIMIT');
  });

  test('device IDs are stored only as keyed hashes', async () => {
    const { rows } = await h.pool.query(`SELECT device_hash FROM trial_claims WHERE device_hash IS NOT NULL LIMIT 1`);
    assert.ok(Buffer.isBuffer(rows[0].device_hash));
    assert.ok(!rows[0].device_hash.toString('utf8').includes('device'));
  });
});
