import { Body, Controller, Get, HttpCode, Inject, Param, Post, Req, Res, UseGuards } from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import type { FastifyReply, FastifyRequest } from 'fastify';
import { CURRENCY, PRICE_BDT, type Config } from '../config.js';
import type { Pool } from '../db.js';
import { ApiError } from '../errors.js';
import { ProviderUnavailableError } from '../payments/paystation.js';
import { OPEN_STATUSES, PaymentService, recordEvent, type Order } from '../payments/service.js';
import { renderPaymentReturnPage } from '../returnPage.js';
import { AccessService } from './access.service.js';
import { AuthGuard, UserThrottlerGuard, type AuthedRequest } from './guards.js';
import { INVOICE, ipnBody, paymentBody } from './schemas.js';
import { CONFIG, POOL } from './tokens.js';

const MINUTE = 60_000;
/** Status checks ask PayStation at most this often per order. */
const STATUS_RECHECK_MS = 3_000;

const orderView = (order: Order) => ({
  invoice: order.invoice_number,
  status: order.status,
  amount: PRICE_BDT,
  currency: CURRENCY,
  paidAt: order.paid_at?.toISOString() ?? null,
});

@Controller()
export class PaymentController {
  constructor(
    @Inject(POOL) private readonly pool: Pool,
    @Inject(CONFIG) private readonly config: Config,
    @Inject(PaymentService) private readonly payments: PaymentService,
    @Inject(AccessService) private readonly access: AccessService,
  ) {}

  /** Creates (or reuses) a pending ৳250 order and returns PayStation's hosted checkout URL. */
  @Post('api/payment/create')
  @HttpCode(200)
  @UseGuards(AuthGuard, UserThrottlerGuard)
  @Throttle({ default: { limit: 5, ttl: MINUTE } })
  async create(@Req() request: AuthedRequest, @Body() body: unknown) {
    const input = paymentBody.parse(body);
    const { rows } = await this.pool.query<{ email: string }>('SELECT email FROM users WHERE id = $1', [request.userId]);
    const order = await this.payments.createCheckout(request.userId, {
      name: input.customerName,
      phone: input.customerPhone,
      email: rows[0]!.email,
    });
    return { ...orderView(order), paymentUrl: order.payment_url };
  }

  /** The owner's order status; asks PayStation (server to server) while the order is still open. */
  @Get('api/payment/status/:invoice')
  @UseGuards(AuthGuard, UserThrottlerGuard)
  @Throttle({ default: { limit: 60, ttl: MINUTE } })
  async status(@Req() request: AuthedRequest, @Param('invoice') invoice: string) {
    if (!INVOICE.test(invoice)) throw new ApiError(404, 'NOT_FOUND', 'Payment not found.');
    let order = await this.payments.findForUser(request.userId, invoice);
    const stale = !order.last_checked_at || Date.now() - order.last_checked_at.getTime() > STATUS_RECHECK_MS;
    let providerReachable = true;
    if (OPEN_STATUSES.includes(order.status) && stale) {
      try {
        order = await this.payments.verify(invoice);
      } catch (error) {
        if (!(error instanceof ProviderUnavailableError)) throw error;
        providerReachable = false;
      }
    }
    return { ...orderView(order), providerReachable, entitlement: await this.access.entitlementFor(request.userId) };
  }

  /**
   * PayStation IPN (sent only for successful payments, unsigned). The body is logged and used only to
   * find the order; PAID is decided solely by the server-to-server transaction-status check.
   */
  @Post('api/payment/ipn')
  @UseGuards(UserThrottlerGuard)
  @Throttle({ default: { limit: 120, ttl: MINUTE } })
  async ipn(@Req() request: FastifyRequest, @Body() body: unknown, @Res({ passthrough: true }) reply: FastifyReply) {
    const parsed = ipnBody.safeParse(body);
    if (!parsed.success) {
      reply.status(400);
      return { status: 'error' };
    }
    const invoice = parsed.data.invoice_number;
    const order = INVOICE.test(invoice) ? await this.payments.findByInvoice(invoice) : null;
    await recordEvent(this.pool, 'IPN_RECEIVED', order, { body: body as Record<string, unknown>, ip: request.ip }, invoice);
    if (!order) {
      reply.status(404);
      return { status: 'error' };
    }
    if (order.status !== 'PAID') {
      try {
        await this.payments.verify(invoice);
      } catch (error) {
        if (!(error instanceof ProviderUnavailableError)) throw error;
        reply.status(503); // PayStation retries.
        return { status: 'retry' };
      }
    }
    reply.status(200);
    return { status: 'success' };
  }

  /** PayStation's callback_url: a script-free page for the payer's browser showing the verified status. */
  @Get('payment/return/:invoice')
  @UseGuards(UserThrottlerGuard)
  @Throttle({ default: { limit: 30, ttl: MINUTE } })
  async returnPage(@Param('invoice') invoice: string, @Res() reply: FastifyReply): Promise<void> {
    let order = INVOICE.test(invoice) ? await this.payments.findByInvoice(invoice) : null;
    if (order && OPEN_STATUSES.includes(order.status)) {
      try {
        order = await this.payments.verify(invoice);
      } catch (error) {
        if (!(error instanceof ProviderUnavailableError)) throw error;
      }
    }
    const appUrl = order ? `${this.config.APP_RETURN_URL}?invoice=${encodeURIComponent(invoice)}` : this.config.APP_RETURN_URL;
    await reply
      .header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; img-src data:")
      .header('Cache-Control', 'no-store')
      .header('Referrer-Policy', 'no-referrer')
      .type('text/html; charset=utf-8')
      .send(renderPaymentReturnPage(order?.status ?? null, appUrl));
  }
}
