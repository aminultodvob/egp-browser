/**
 * Admin CLI for complimentary lifetime access (e.g. the Google Play review account).
 *
 *   node --env-file=.env dist/grant.js grant  reviewer@example.com "Google Play review"
 *   node --env-file=.env dist/grant.js revoke reviewer@example.com
 *
 * The account must have signed in to the app once.
 */
import { grantComplimentary, revokeComplimentary } from './admin.js';
import { createPool } from './db.js';

const [action, email, ...reasonParts] = process.argv.slice(2);
const reason = reasonParts.join(' ').trim();
if (!process.env.DATABASE_URL) throw new Error('DATABASE_URL is required');
if (!email || (action === 'grant' && !reason) || (action !== 'grant' && action !== 'revoke')) {
  console.error('Usage: grant <email> <reason>  |  revoke <email>');
  process.exit(2);
}
const pool = createPool(process.env.DATABASE_URL);
try {
  const result = action === 'grant' ? await grantComplimentary(pool, email, reason) : await revokeComplimentary(pool, email);
  console.log(JSON.stringify(result));
  process.exitCode = result.ok ? 0 : 1;
} finally {
  await pool.end();
}
