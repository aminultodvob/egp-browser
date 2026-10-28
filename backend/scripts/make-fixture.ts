// Generates app/src/testDirect/resources/entitlement-fixture.json: tokens signed by the real
// backend signer, so the Android verifier is tested against actual jose output.
import { generateKeyPairSync } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { EntitlementSigner } from '../src/entitlement.js';

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
const signer = await EntitlementSigner.create(privateKey.export({ type: 'pkcs8', format: 'pem' }).toString());
const serverTime = new Date('2026-10-28T10:00:00Z');
const base = { userId: '6f1c2c3e-0000-4000-8000-000000000001', serverTime, trialAlreadyUsed: true, pendingPayment: null };
const lifetime = await signer.sign({ ...base, state: 'LIFETIME', trial: null, lifetime: { grantedAt: serverTime } });
const trial = await signer.sign({ ...base, state: 'TRIAL_ACTIVE', lifetime: null,
  trial: { startedAt: serverTime, expiresAt: new Date(serverTime.getTime() + 7 * 86400000) } });
const other = generateKeyPairSync('ec', { namedCurve: 'P-256' }).publicKey;
writeFileSync('../app/src/testDirect/resources/entitlement-fixture.json', JSON.stringify({
  publicKey: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
  otherPublicKey: other.export({ type: 'spki', format: 'der' }).toString('base64'),
  userId: base.userId, serverTimeMs: serverTime.getTime(), lifetime, trial,
}, null, 2) + '\n');
console.log('fixture written');
