import Fastify, { type FastifyInstance, type FastifyRequest } from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { z } from 'zod';
import { CURRENCY, PRICE_BDT, TRIAL_DAYS, type Config } from './config.js';
import { transaction, type Pool } from './db.js';
import { ApiError, unauthorized } from './errors.js';
import { EntitlementSigner, entitlementResponse, loadEntitlement } from './entitlement.js';
import type { GoogleVerifier } from './auth/google.js';
import { TokenService, hmac } from './auth/tokens.js';
import { ProviderUnavailableError, type PayStationApi } from './payments/paystation.js';
import { OPEN_STATUSES, PaymentService, recordEvent, type Order } from './payments/service.js';
import { renderPaymentReturnPage } from './returnPage.js';

export interface AppDeps {
  config: Config;
  pool: Pool;
  verifyGoogle: GoogleVerifier;
  paystation: PayStationApi;
  logger?: boolean;
}

declare module 'fastify' {
  interface FastifyRequest {
    userId: string;
  }
}

const INVOICE = /^[A-Z0-9]{8,40}$/;
/** Bangladeshi mobile number as PayStation expects it (e.g. 01712345678). */
const BD_MOBILE = /^01[3-9]\d{8}$/;
const STATUS_RECHECK_MS = 3_000;

const googleSignInBody = z.object({ idToken: z.string().min(20).max(4096) }).strict();
const refreshBody = z.object({ refreshToken: z.string().min(20).max(200) }).strict();
const trialBody = z.object({ deviceId: z.string().min(4).max(128).optional() }).strict();
const paymentBody = z
  .object({
    customerName: z.string().trim().min(2).max(80),
    customerPhone: z.string().trim().regex(BD_MOBILE, 'Use an 11-digit Bangladeshi mobile number'),
  })
  .strict();
const ipnBody = z.object({ invoice_number: z.string().max(64) }).passthrough();

export async function buildApp(deps: AppDeps): Promise<FastifyInstance> {
  const { config, pool } = deps;
  const app = Fastify({
    trustProxy: config.TRUST_PROXY,
    bodyLimit: 32 * 1024,
    logger: deps.logger === false ? false : {
      level: config.NODE_ENV === 'production' ? 'info' : 'debug',
      redact: ['req.headers.authorization', 'req.headers.cookie', 'req.headers.merchantid'],
    },
  });
  const tokens = new TokenService(config.JWT_SECRET, pool);
  const signer = await EntitlementSigner.create(config.ENTITLEMENT_PRIVATE_KEY);
  const checkoutHost = new URL(config.PAYSTATION_BASE_URL).hostname.toLowerCase();
  const payments = new PaymentService(pool, deps.paystation, {
    publicBaseUrl: config.PUBLIC_BASE_URL,
    checkoutHosts: [checkoutHost],
  });
  const subjectHash = async (userId: string) => {
    const { rows } = await pool.query<{ google_sub: string }>('SELECT google_sub FROM users WHERE id = $1', [userId]);
    return rows[0] ? hmac(config.TRIAL_HASH_SECRET, `sub:${rows[0].google_sub}`) : null;
  };
  const entitlementFor = async (userId: string) =>
    entitlementResponse(await loadEntitlement(pool, userId, await subjectHash(userId)), signer);

  await app.register(rateLimit, { global: false, hook: 'preHandler' });
  app.decorateRequest('userId', '');

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ApiError) {
      return reply.status(error.statusCode).send({ error: { code: error.code, message: error.message } });
    }
    if (error instanceof z.ZodError) {
      return reply.status(400).send({
        error: { code: 'INVALID_REQUEST', message: error.issues[0]?.message ?? 'Invalid request.' },
      });
    }
    const status = (error as { statusCode?: number }).statusCode;
    if (status && status >= 400 && status < 500) {
      return reply.status(status).send({ error: { code: status === 429 ? 'RATE_LIMITED' : 'INVALID_REQUEST', message: (error as Error).message } });
    }
    request.log.error({ err: error }, 'Unhandled error');
    return reply.status(500).send({ error: { code: 'INTERNAL', message: 'Something went wrong. Try again.' } });
  });

  const authenticate = async (request: FastifyRequest) => {
    const header = request.headers.authorization ?? '';
    if (!header.startsWith('Bearer ')) throw unauthorized();
    const userId = await tokens.verifyAccessToken(header.slice(7));
    const { rowCount } = await pool.query(`SELECT 1 FROM users WHERE id = $1 AND status = 'active'`, [userId]);
    if (!rowCount) throw unauthorized();
    request.userId = userId;
  };
  const perUser = (max: number) => ({
    rateLimit: { max, timeWindow: '1 minute', keyGenerator: (r: FastifyRequest) => r.userId || r.ip },
  });
  const perIp = (max: number) => ({ rateLimit: { max, timeWindow: '1 minute' } });

  app.get('/health', async () => {
    await pool.query('SELECT 1');
    return { ok: true };
  });

  // ---- Accounts -------------------------------------------------------------------------------

  app.post('/api/auth/google', { config: perIp(20) }, async (request) => {
    const { idToken } = googleSignInBody.parse(request.body);
    const identity = await deps.verifyGoogle(idToken);
    const { rows } = await pool.query<{ id: string; email: string; display_name: string | null; status: string }>(
      `INSERT INTO users (google_sub, email, display_name, last_login_at) VALUES ($1, $2, $3, now())
       ON CONFLICT (google_sub) DO UPDATE
         SET email = EXCLUDED.email, display_name = COALESCE(EXCLUDED.display_name, users.display_name), last_login_at = now()
       RETURNING id, email, display_name, status`,
      [identity.sub, identity.email, identity.name ?? null],
    );
    const user = rows[0]!;
    if (user.status !== 'active') throw new ApiError(403, 'ACCOUNT_SUSPENDED', 'This account is suspended. Contact support.');
    return {
      ...(await tokens.issue(user.id)),
      user: { id: user.id, email: user.email, name: user.display_name },
      entitlement: await entitlementFor(user.id),
    };
  });

  app.post('/api/auth/refresh', { config: perIp(60) }, async (request) => {
    const { refreshToken } = refreshBody.parse(request.body);
    const { userId: _userId, ...issued } = await tokens.rotate(refreshToken);
    return issued;
  });

  app.post('/api/auth/logout', { config: perIp(60) }, async (request, reply) => {
    const { refreshToken } = refreshBody.parse(request.body);
    await tokens.revokeFamily(refreshToken);
    return reply.status(204).send();
  });

  app.get('/api/me', { onRequest: authenticate }, async (request) => {
    const { rows } = await pool.query<{ id: string; email: string; display_name: string | null; created_at: Date }>(
      'SELECT id, email, display_name, created_at FROM users WHERE id = $1',
      [request.userId],
    );
    const user = rows[0]!;
    return { id: user.id, email: user.email, name: user.display_name, createdAt: user.created_at.toISOString() };
  });

  /**
   * Deletes the account and personal data. Payment records are kept without the account link for
   * accounting; a pseudonymous trial claim is kept so the free trial cannot be restarted.
   */
  app.delete('/api/account', { onRequest: authenticate, config: perUser(5) }, async (request, reply) => {
    await transaction(pool, async (client) => {
      await client.query('UPDATE payment_orders SET user_id = NULL WHERE user_id = $1', [request.userId]);
      await client.query('DELETE FROM entitlements WHERE user_id = $1', [request.userId]);
      await client.query('DELETE FROM users WHERE id = $1', [request.userId]);
    });
    return reply.status(204).send();
  });

  // ---- Trial and entitlement ------------------------------------------------------------------

  app.get('/api/entitlement', { onRequest: authenticate, config: perUser(60) }, async (request) => entitlementFor(request.userId));

  app.post('/api/trial/activate', { onRequest: authenticate, config: perUser(10) }, async (request) => {
    const { deviceId } = trialBody.parse(request.body ?? {});
    const sHash = await subjectHash(request.userId);
    const deviceHash = deviceId ? hmac(config.TRIAL_HASH_SECRET, `device:${deviceId}`) : null;
    await transaction(pool, async (client) => {
      // Serialise activations per subject so a double tap cannot create two claims.
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [sHash!.toString('hex')]);
      const existing = await client.query('SELECT 1 FROM trials WHERE user_id = $1', [request.userId]);
      if (existing.rowCount) return; // Idempotent: never restarts.
      const lifetime = await client.query(
        `SELECT 1 FROM entitlements WHERE user_id = $1 AND kind = 'LIFETIME' AND status = 'ACTIVE'`,
        [request.userId],
      );
      if (lifetime.rowCount) return;
      const claimed = await client.query('SELECT 1 FROM trial_claims WHERE subject_hash = $1', [sHash]);
      if (claimed.rowCount) throw new ApiError(409, 'TRIAL_ALREADY_USED', 'The free trial was already used with this Google account.');
      if (deviceHash) {
        const { rows } = await client.query<{ n: string }>('SELECT count(*) AS n FROM trial_claims WHERE device_hash = $1', [deviceHash]);
        if (Number(rows[0]!.n) >= config.MAX_TRIALS_PER_DEVICE) {
          throw new ApiError(409, 'TRIAL_DEVICE_LIMIT', 'The free trial was already used on this device.');
        }
      }
      await client.query(
        `INSERT INTO trials (user_id, started_at, expires_at) VALUES ($1, now(), now() + make_interval(days => $2))`,
        [request.userId, TRIAL_DAYS],
      );
      await client.query('INSERT INTO trial_claims (subject_hash, device_hash) VALUES ($1, $2)', [sHash, deviceHash]);
    });
    return entitlementFor(request.userId);
  });

  // ---- Payments -------------------------------------------------------------------------------

  const orderView = (order: Order) => ({
    invoice: order.invoice_number,
    status: order.status,
    amount: PRICE_BDT,
    currency: CURRENCY,
    paidAt: order.paid_at?.toISOString() ?? null,
  });

  app.post('/api/payment/create', { onRequest: authenticate, config: perUser(5) }, async (request) => {
    const body = paymentBody.parse(request.body);
    const { rows } = await pool.query<{ email: string }>('SELECT email FROM users WHERE id = $1', [request.userId]);
    const order = await payments.createCheckout(request.userId, {
      name: body.customerName,
      phone: body.customerPhone,
      email: rows[0]!.email,
    });
    return { ...orderView(order), paymentUrl: order.payment_url };
  });

  app.get<{ Params: { invoice: string } }>(
    '/api/payment/status/:invoice',
    { onRequest: authenticate, config: perUser(60) },
    async (request) => {
      const invoice = request.params.invoice;
      if (!INVOICE.test(invoice)) throw new ApiError(404, 'NOT_FOUND', 'Payment not found.');
      let order = await payments.findForUser(request.userId, invoice);
      const stale = !order.last_checked_at || Date.now() - order.last_checked_at.getTime() > STATUS_RECHECK_MS;
      let providerReachable = true;
      if (OPEN_STATUSES.includes(order.status) && stale) {
        try {
          order = await payments.verify(invoice);
        } catch (error) {
          if (!(error instanceof ProviderUnavailableError)) throw error;
          providerReachable = false;
        }
      }
      return { ...orderView(order), providerReachable, entitlement: await entitlementFor(request.userId) };
    },
  );

  /**
   * PayStation IPN (sent only for successful payments, unsigned). The body is logged and used only to
   * find the order; PAID is decided solely by the server-to-server transaction-status check.
   */
  app.post('/api/payment/ipn', { config: perIp(120) }, async (request, reply) => {
    const parsed = ipnBody.safeParse(request.body);
    if (!parsed.success) return reply.status(400).send({ status: 'error' });
    const invoice = parsed.data.invoice_number;
    const order = INVOICE.test(invoice) ? await payments.findByInvoice(invoice) : null;
    await recordEvent(pool, 'IPN_RECEIVED', order, { body: request.body as Record<string, unknown>, ip: request.ip }, invoice);
    if (!order) return reply.status(404).send({ status: 'error' });
    if (order.status === 'PAID') return { status: 'success' };
    try {
      await payments.verify(invoice);
    } catch (error) {
      if (error instanceof ProviderUnavailableError) return reply.status(503).send({ status: 'retry' }); // PayStation retries.
      throw error;
    }
    return { status: 'success' };
  });

  /** PayStation's callback_url: a page for the payer's browser. It shows the verified status only. */
  app.get<{ Params: { invoice: string } }>('/payment/return/:invoice', { config: perIp(30) }, async (request, reply) => {
    const invoice = request.params.invoice;
    let order = INVOICE.test(invoice) ? await payments.findByInvoice(invoice) : null;
    if (order && OPEN_STATUSES.includes(order.status)) {
      try {
        order = await payments.verify(invoice);
      } catch (error) {
        if (!(error instanceof ProviderUnavailableError)) throw error;
      }
    }
    return reply
      .header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; img-src data:")
      .header('Cache-Control', 'no-store')
      .header('Referrer-Policy', 'no-referrer')
      .type('text/html; charset=utf-8')
      .send(renderPaymentReturnPage(order?.status ?? null, order ? `${config.APP_RETURN_URL}?invoice=${encodeURIComponent(invoice)}` : config.APP_RETURN_URL));
  });

  return app;
}
