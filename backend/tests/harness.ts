import { generateKeyPairSync, type KeyObject } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import EmbeddedPostgres from 'embedded-postgres';
import type { NestFastifyApplication } from '@nestjs/platform-fastify';
import type { FastifyInstance } from 'fastify';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { createPool, type Pool } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { ApiError } from '../src/errors.js';
import type { InitiateRequest, InitiateResult, PayStationApi, StatusResult } from '../src/payments/paystation.js';
import { ProviderUnavailableError } from '../src/payments/paystation.js';

/** Scriptable PayStation stand-in. Tests set what the provider "says". */
export class FakePayStation implements PayStationApi {
  initiateCalls: InitiateRequest[] = [];
  statusCalls: string[] = [];
  initiateResult: (req: InitiateRequest) => InitiateResult | 'unavailable' = (req) => ({
    ok: true,
    paymentUrl: `https://sandbox.paystation.com.bd/checkout/${req.invoiceNumber}/token`,
  });
  statuses = new Map<string, StatusResult | 'unavailable'>();

  async initiate(request: InitiateRequest): Promise<InitiateResult> {
    this.initiateCalls.push(request);
    const result = this.initiateResult(request);
    if (result === 'unavailable') throw new ProviderUnavailableError('down');
    return result;
  }

  async transactionStatus(invoice: string): Promise<StatusResult> {
    this.statusCalls.push(invoice);
    const result = this.statuses.get(invoice) ?? { kind: 'not_found', code: '2001', message: 'Transaction not found in system' };
    if (result === 'unavailable') throw new ProviderUnavailableError('down');
    return result;
  }

  /** What PayStation's transaction-status returns for a real payment of [amount]. */
  setStatus(invoice: string, trxStatus: string, amount = '250.00', trxId = `TRX${invoice.slice(-6)}`, reportedInvoice = invoice) {
    this.statuses.set(invoice, {
      kind: 'found',
      data: { invoiceNumber: reportedInvoice, trxStatus, trxId: trxStatus === 'failed' ? '' : trxId, paymentAmount: amount, paymentMethod: 'bKash' },
    });
  }
}

export interface Harness {
  /** Underlying Fastify instance of the NestJS app (for inject()). */
  app: FastifyInstance;
  nest: NestFastifyApplication;
  cronSecret: string;
  pool: Pool;
  paystation: FakePayStation;
  entitlementPublicKey: KeyObject;
  signIn(sub: string, email?: string): Promise<{ accessToken: string; refreshToken: string; userId: string }>;
  request(method: 'GET' | 'POST' | 'DELETE', url: string, token?: string, body?: unknown, ip?: string): Promise<{ status: number; json: any }>;
  close(): Promise<void>;
}

async function freePort(): Promise<number> {
  return new Promise((resolve) => {
    const server = createServer();
    server.listen(0, () => {
      const port = (server.address() as { port: number }).port;
      server.close(() => resolve(port));
    });
  });
}

export async function startHarness(): Promise<Harness> {
  const dir = await mkdtemp(join(tmpdir(), 'egp-pg-'));
  const port = await freePort();
  const pg = new EmbeddedPostgres({ databaseDir: dir, port, user: 'postgres', password: 'test-password', persistent: false, onLog: () => {} });
  await pg.initialise();
  await pg.start();
  await pg.createDatabase('egp_test');
  const databaseUrl = `postgres://postgres:test-password@localhost:${port}/egp_test`;

  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const config = loadConfig({
    NODE_ENV: 'test',
    DATABASE_URL: databaseUrl,
    PUBLIC_BASE_URL: 'https://api.test.example',
    JWT_SECRET: 'test-jwt-secret-that-is-long-enough-0123456789',
    TRIAL_HASH_SECRET: 'test-trial-secret-that-is-long-enough-0123456789',
    ENTITLEMENT_PRIVATE_KEY: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
    GOOGLE_CLIENT_IDS: 'test-client.apps.googleusercontent.com',
    PAYSTATION_BASE_URL: 'https://sandbox.paystation.com.bd',
    PAYSTATION_MERCHANT_ID: 'test-merchant',
    PAYSTATION_PASSWORD: 'test-password',
    CRON_SECRET: 'test-cron-secret-that-is-long-enough-0123456789',
  });
  const pool = createPool(databaseUrl);
  await migrate(pool);
  const paystation = new FakePayStation();
  const nest = await createApp({
    config,
    pool,
    paystation,
    // Test identities look like "test-google-token:<sub>:<email>".
    verifyGoogle: async (idToken) => {
      const [prefix, sub, email] = idToken.split(':');
      if (prefix !== 'test-google-token' || !sub || !email) throw new ApiError(401, 'INVALID_GOOGLE_TOKEN', 'bad token');
      return { sub, email, name: `User ${sub}` };
    },
  }, { logger: false });
  const app = nest.getHttpAdapter().getInstance() as unknown as FastifyInstance;

  const request: Harness['request'] = async (method, url, token, body, ip = '127.0.0.1') => {
    const response = await app.inject({
      method,
      url,
      remoteAddress: ip,
      headers: token ? { authorization: `Bearer ${token}` } : {},
      ...(body === undefined ? {} : { payload: body as object }),
    });
    return { status: response.statusCode, json: response.body ? safeJson(response.body) : null };
  };

  let signIns = 0;
  return {
    app,
    nest,
    cronSecret: config.CRON_SECRET,
    pool,
    paystation,
    entitlementPublicKey: publicKey,
    request,
    async signIn(sub, email = `${sub}@example.com`) {
      // Each simulated user signs in from its own address so per-IP rate limits don't interfere.
      signIns += 1;
      const ip = `10.${(signIns >> 16) & 255}.${(signIns >> 8) & 255}.${signIns & 255}`;
      const res = await request('POST', '/api/auth/google', undefined, { idToken: `test-google-token:${sub}:${email}` }, ip);
      if (res.status !== 200) throw new Error(`sign-in failed: ${res.status} ${JSON.stringify(res.json)}`);
      return { accessToken: res.json.accessToken, refreshToken: res.json.refreshToken, userId: res.json.user.id };
    },
    async close() {
      await nest.close();
      await pool.end();
      await pg.stop();
      await rm(dir, { recursive: true, force: true }).catch(() => undefined);
    },
  };
}

function safeJson(body: string): any {
  try {
    return JSON.parse(body);
  } catch {
    return body;
  }
}
