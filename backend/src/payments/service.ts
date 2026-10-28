import { randomBytes } from 'node:crypto';
import type pg from 'pg';
import { CURRENCY, PRICE_BDT } from '../config.js';
import { transaction, type Pool, type Queryable } from '../db.js';
import { ApiError, notFound } from '../errors.js';
import { ProviderUnavailableError, type PayStationApi, type TransactionData } from './paystation.js';

export type OrderStatus =
  | 'PENDING' | 'PROCESSING' | 'PAID' | 'FAILED' | 'CANCELLED' | 'EXPIRED' | 'REFUNDED' | 'VERIFICATION_REQUIRED';

/** Statuses that a later provider check may still change. */
export const OPEN_STATUSES: OrderStatus[] = ['PENDING', 'PROCESSING'];

export interface Order {
  id: string;
  invoice_number: string;
  user_id: string | null;
  amount: string;
  currency: string;
  status: OrderStatus;
  payment_url: string | null;
  provider_trx_id: string | null;
  created_at: Date;
  paid_at: Date | null;
  last_checked_at: Date | null;
}

/** Unpaid orders older than this, unknown to PayStation, are expired. */
const ORDER_EXPIRY_HOURS = 24;
/** A still-pending checkout link is reused instead of creating a new invoice. */
const REUSE_PENDING_MINUTES = 30;

export interface CustomerDetails {
  name: string;
  phone: string;
  email: string;
}

export function newInvoiceNumber(): string {
  // Server-generated, unique and unguessable: "EGP" + time (base36) + 10 random hex chars.
  return `EGP${Date.now().toString(36).toUpperCase()}${randomBytes(5).toString('hex').toUpperCase()}`;
}

export async function recordEvent(
  db: Queryable,
  kind: string,
  order: { id: string; invoice_number: string } | null,
  detail: Record<string, unknown>,
  invoiceNumber?: string,
): Promise<void> {
  await db.query('INSERT INTO payment_events (order_id, invoice_number, kind, detail) VALUES ($1, $2, $3, $4)', [
    order?.id ?? null,
    order?.invoice_number ?? invoiceNumber ?? null,
    kind,
    JSON.stringify(detail),
  ]);
}

export class PaymentService {
  constructor(
    private readonly pool: Pool,
    private readonly provider: PayStationApi,
    private readonly options: { publicBaseUrl: string; checkoutHosts: string[] },
  ) {}

  /** Creates (or reuses) a pending ৳250 order and returns PayStation's hosted checkout URL. */
  async createCheckout(userId: string, customer: CustomerDetails): Promise<Order> {
    const owned = await this.pool.query(
      `SELECT 1 FROM entitlements WHERE user_id = $1 AND kind = 'LIFETIME' AND status = 'ACTIVE'`,
      [userId],
    );
    if (owned.rowCount) throw new ApiError(409, 'ALREADY_OWNED', 'You already have lifetime access.');

    const reusable = await this.pool.query<Order>(
      `SELECT * FROM payment_orders
        WHERE user_id = $1 AND status = 'PENDING' AND payment_url IS NOT NULL
          AND created_at > now() - make_interval(mins => $2)
        ORDER BY created_at DESC LIMIT 1`,
      [userId, REUSE_PENDING_MINUTES],
    );
    if (reusable.rows[0]) return reusable.rows[0];

    const invoice = newInvoiceNumber();
    const { rows } = await this.pool.query<Order>(
      `INSERT INTO payment_orders (invoice_number, user_id, amount, currency, status)
       VALUES ($1, $2, $3, $4, 'PENDING') RETURNING *`,
      [invoice, userId, PRICE_BDT, CURRENCY],
    );
    const order = rows[0]!;

    let result;
    try {
      result = await this.provider.initiate({
        invoiceNumber: invoice,
        amount: PRICE_BDT,
        customerName: customer.name,
        customerPhone: customer.phone,
        customerEmail: customer.email,
        callbackUrl: `${this.options.publicBaseUrl.replace(/\/+$/, '')}/payment/return/${invoice}`,
        reference: 'e-GP Browser lifetime access',
        checkoutItems: 'e-GP Browser lifetime access (one-time)',
      });
    } catch (error) {
      await this.fail(order, 'PROVIDER_UNAVAILABLE');
      if (error instanceof ProviderUnavailableError) {
        throw new ApiError(502, 'PAYMENT_PROVIDER_UNAVAILABLE', 'The payment service is not responding. Try again shortly.');
      }
      throw error;
    }
    if (!result.ok) {
      await this.fail(order, `INITIATE_${result.code}`, { message: result.message });
      throw new ApiError(502, 'PAYMENT_PROVIDER_ERROR', 'The payment could not be started. Try again shortly.');
    }
    if (!this.isTrustedCheckoutUrl(result.paymentUrl)) {
      await this.fail(order, 'UNTRUSTED_CHECKOUT_URL', { host: safeHost(result.paymentUrl) });
      throw new ApiError(502, 'PAYMENT_PROVIDER_ERROR', 'The payment could not be started. Try again shortly.');
    }
    const updated = await this.pool.query<Order>(
      `UPDATE payment_orders SET payment_url = $2, updated_at = now() WHERE id = $1 RETURNING *`,
      [order.id, result.paymentUrl],
    );
    await recordEvent(this.pool, 'INITIATED', order, { amount: PRICE_BDT, currency: CURRENCY });
    return updated.rows[0]!;
  }

  async findForUser(userId: string, invoice: string): Promise<Order> {
    const { rows } = await this.pool.query<Order>(
      'SELECT * FROM payment_orders WHERE invoice_number = $1 AND user_id = $2',
      [invoice, userId],
    );
    if (!rows[0]) throw notFound('Payment');
    return rows[0];
  }

  async findByInvoice(invoice: string): Promise<Order | null> {
    const { rows } = await this.pool.query<Order>('SELECT * FROM payment_orders WHERE invoice_number = $1', [invoice]);
    return rows[0] ?? null;
  }

  /**
   * The only path to PAID: asks PayStation's transaction-status API (server to server) and applies
   * the answer. IPNs, redirects and app reports merely trigger this check.
   * Throws ProviderUnavailableError when PayStation cannot be reached; nothing is changed then.
   */
  async verify(invoice: string): Promise<Order> {
    const result = await this.provider.transactionStatus(invoice);
    return transaction(this.pool, async (client) => {
      const { rows } = await client.query<Order>('SELECT * FROM payment_orders WHERE invoice_number = $1 FOR UPDATE', [invoice]);
      const order = rows[0];
      if (!order) throw notFound('Payment');
      await client.query('UPDATE payment_orders SET last_checked_at = now() WHERE id = $1', [order.id]);

      if (result.kind === 'not_found') {
        await recordEvent(client, 'STATUS_NOT_FOUND', order, { code: result.code });
        const ageHours = (Date.now() - order.created_at.getTime()) / 3_600_000;
        if (OPEN_STATUSES.includes(order.status) && ageHours >= ORDER_EXPIRY_HOURS) {
          return this.setStatus(client, order, 'EXPIRED', { reason: 'NOT_FOUND_AT_PROVIDER' });
        }
        return order;
      }

      const data = result.data;
      await recordEvent(client, 'STATUS_CHECKED', order, {
        trxStatus: data.trxStatus, amount: data.paymentAmount, method: data.paymentMethod, trxId: data.trxId,
      });
      if (data.invoiceNumber !== order.invoice_number) {
        return this.setStatus(client, order, 'VERIFICATION_REQUIRED', { reason: 'INVOICE_MISMATCH' }, data);
      }
      switch (data.trxStatus) {
        case 'success':
          return this.applySuccess(client, order, data);
        case 'processing':
          return order.status === 'PENDING' ? this.setStatus(client, order, 'PROCESSING', {}, data) : order;
        case 'failed':
          if (order.status === 'PAID') {
            // Never silently take away a verified purchase; flag it for a person to review.
            await recordEvent(client, 'ANOMALY', order, { reason: 'PROVIDER_REPORTS_FAILED_AFTER_PAID' });
            return order;
          }
          return OPEN_STATUSES.includes(order.status) ? this.setStatus(client, order, 'FAILED', {}, data) : order;
        case 'refund':
          return this.applyRefund(client, order, data);
        default:
          await recordEvent(client, 'UNKNOWN_PROVIDER_STATUS', order, { trxStatus: data.trxStatus });
          return order;
      }
    });
  }

  private async applySuccess(client: pg.PoolClient, order: Order, data: TransactionData): Promise<Order> {
    if (order.status === 'PAID') return order; // Idempotent: repeated IPNs and checks change nothing.
    if (order.status === 'REFUNDED') return order;
    if (!isExactAmount(data.paymentAmount, PRICE_BDT)) {
      return this.setStatus(client, order, 'VERIFICATION_REQUIRED', { reason: 'AMOUNT_MISMATCH', amount: data.paymentAmount }, data);
    }
    if (!data.trxId) {
      return this.setStatus(client, order, 'VERIFICATION_REQUIRED', { reason: 'MISSING_TRX_ID' }, data);
    }
    if (!order.user_id) {
      return this.setStatus(client, order, 'VERIFICATION_REQUIRED', { reason: 'ACCOUNT_DELETED' }, data);
    }
    const duplicateTrx = await client.query('SELECT 1 FROM payment_orders WHERE provider_trx_id = $1 AND id <> $2', [data.trxId, order.id]);
    if (duplicateTrx.rowCount) {
      return this.setStatus(client, order, 'VERIFICATION_REQUIRED', { reason: 'TRX_ID_ALREADY_USED' }, data);
    }
    const alreadyPaid = await client.query(
      `SELECT 1 FROM payment_orders WHERE user_id = $1 AND status = 'PAID' AND id <> $2`,
      [order.user_id, order.id],
    );
    if (alreadyPaid.rowCount) {
      // A second successful payment for the same account: keep the money traceable for a refund.
      return this.setStatus(client, order, 'VERIFICATION_REQUIRED', { reason: 'DUPLICATE_PAYMENT_REFUND_DUE' }, data);
    }
    let paid: Order;
    await client.query('SAVEPOINT mark_paid');
    try {
      paid = await this.setStatus(client, order, 'PAID', {}, data, true);
    } catch (error) {
      // Another invoice of this user was marked paid concurrently (unique index on paid orders).
      if ((error as { code?: string }).code !== '23505') throw error;
      await client.query('ROLLBACK TO SAVEPOINT mark_paid');
      return this.setStatus(client, order, 'VERIFICATION_REQUIRED', { reason: 'DUPLICATE_PAYMENT_REFUND_DUE' }, data);
    }
    await client.query(
      `INSERT INTO entitlements (user_id, kind, status, source_order_id)
       VALUES ($1, 'LIFETIME', 'ACTIVE', $2)
       ON CONFLICT (user_id, kind) DO UPDATE
         SET status = 'ACTIVE', source_order_id = EXCLUDED.source_order_id, granted_at = now(),
             revoked_at = NULL, revoke_reason = NULL`,
      [order.user_id, order.id],
    );
    await recordEvent(client, 'ENTITLEMENT_GRANTED', order, { kind: 'LIFETIME' });
    return paid;
  }

  private async applyRefund(client: pg.PoolClient, order: Order, data: TransactionData): Promise<Order> {
    if (order.status === 'REFUNDED') return order;
    const refunded = await this.setStatus(client, order, 'REFUNDED', {}, data);
    const revoked = await client.query(
      `UPDATE entitlements SET status = 'REVOKED', revoked_at = now(), revoke_reason = 'REFUNDED'
        WHERE source_order_id = $1 AND status = 'ACTIVE'`,
      [order.id],
    );
    if (revoked.rowCount) await recordEvent(client, 'ENTITLEMENT_REVOKED', order, { reason: 'REFUNDED' });
    return refunded;
  }

  private async setStatus(
    client: pg.PoolClient,
    order: Order,
    status: OrderStatus,
    detail: Record<string, unknown>,
    data?: TransactionData,
    paid = false,
  ): Promise<Order> {
    const { rows } = await client.query<Order>(
      `UPDATE payment_orders
          SET status = $2, updated_at = now(), verified_at = now(),
              provider_status = COALESCE($3, provider_status),
              provider_trx_id = CASE WHEN $6 THEN $4 ELSE provider_trx_id END,
              payment_method = COALESCE(NULLIF($5, ''), payment_method),
              failure_reason = $7,
              paid_at = CASE WHEN $6 THEN now() ELSE paid_at END
        WHERE id = $1 RETURNING *`,
      [order.id, status, data?.trxStatus ?? null, data?.trxId || null, data?.paymentMethod ?? null, paid,
        typeof detail.reason === 'string' ? detail.reason : null],
    );
    await recordEvent(client, 'STATE_CHANGE', order, { from: order.status, to: status, ...detail });
    return rows[0]!;
  }

  private async fail(order: Order, reason: string, detail: Record<string, unknown> = {}): Promise<void> {
    await this.pool.query(
      `UPDATE payment_orders SET status = 'FAILED', failure_reason = $2, updated_at = now() WHERE id = $1`,
      [order.id, reason],
    );
    await recordEvent(this.pool, 'STATE_CHANGE', order, { from: order.status, to: 'FAILED', reason, ...detail });
  }

  private isTrustedCheckoutUrl(url: string): boolean {
    try {
      const parsed = new URL(url);
      return parsed.protocol === 'https:' && this.options.checkoutHosts.includes(parsed.hostname.toLowerCase());
    } catch {
      return false;
    }
  }
}

/** "250", "250.0" and "250.00" match ৳250; anything else (including "250.01") does not. */
export function isExactAmount(value: string, expected: number): boolean {
  if (!/^\d+(\.\d{1,2})?$/.test(value)) return false;
  const [whole, fraction = ''] = value.split('.');
  return Number(whole) === expected && /^0*$/.test(fraction);
}

function safeHost(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return 'invalid';
  }
}
