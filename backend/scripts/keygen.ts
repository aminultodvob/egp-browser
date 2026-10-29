/**
 * Generates the secrets the backend needs. Run once per environment and store the output in your
 * secret manager / .env (never in git). The PUBLIC key goes into the Android build as
 * EGP_ENTITLEMENT_PUBLIC_KEY (it is not secret).
 */
import { generateKeyPairSync, randomBytes } from 'node:crypto';

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
const privatePem = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString();
const publicDer = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');

console.log('# --- backend .env (secret) ---');
console.log(`JWT_SECRET=${randomBytes(48).toString('base64url')}`);
console.log(`TRIAL_HASH_SECRET=${randomBytes(48).toString('base64url')}`);
console.log(`CRON_SECRET=${randomBytes(32).toString('base64url')}`);
console.log(`ENTITLEMENT_PRIVATE_KEY="${privatePem.trim().replace(/\n/g, '\\n')}"`);
console.log('');
console.log('# --- Android build (public, not secret) ---');
console.log(`EGP_ENTITLEMENT_PUBLIC_KEY=${publicDer}`);
