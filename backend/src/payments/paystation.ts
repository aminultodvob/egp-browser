/**
 * PayStation REST client, implemented exactly as documented at https://paystation.com.bd/documentation
 * (API v1): POST /initiate-payment (form body with merchantId/password) and
 * POST /transaction-status (merchantId header, invoice_number form body).
 */

export interface InitiateRequest {
  invoiceNumber: string;
  amount: number;
  customerName: string;
  customerPhone: string;
  customerEmail: string;
  callbackUrl: string;
  reference: string;
  checkoutItems: string;
}

export type InitiateResult =
  | { ok: true; paymentUrl: string }
  | { ok: false; code: string; message: string };

export interface TransactionData {
  invoiceNumber: string;
  /** Lower-cased provider status: success | processing | failed | refund (anything else is unknown). */
  trxStatus: string;
  trxId: string;
  paymentAmount: string;
  paymentMethod: string;
}

export type StatusResult =
  | { kind: 'found'; data: TransactionData }
  | { kind: 'not_found'; code: string; message: string };

/** Network failure, timeout, HTTP 5xx or an unreadable response: the payment state is unknown. */
export class ProviderUnavailableError extends Error {}

export interface PayStationApi {
  initiate(request: InitiateRequest): Promise<InitiateResult>;
  transactionStatus(invoiceNumber: string): Promise<StatusResult>;
}

export interface PayStationOptions {
  baseUrl: string;
  merchantId: string;
  password: string;
  payWithCharge: '0' | '1';
  timeoutMs?: number;
}

export class PayStationClient implements PayStationApi {
  constructor(private readonly options: PayStationOptions) {}

  async initiate(request: InitiateRequest): Promise<InitiateResult> {
    const body = new URLSearchParams({
      merchantId: this.options.merchantId,
      password: this.options.password,
      invoice_number: request.invoiceNumber,
      currency: 'BDT',
      payment_amount: String(request.amount),
      pay_with_charge: this.options.payWithCharge,
      reference: request.reference,
      cust_name: request.customerName,
      cust_phone: request.customerPhone,
      cust_email: request.customerEmail,
      callback_url: request.callbackUrl,
      checkout_items: request.checkoutItems,
    });
    const json = await this.post('/initiate-payment', body, {});
    const code = String(json.status_code ?? '');
    if (code === '200' && json.status === 'success' && typeof json.payment_url === 'string') {
      return { ok: true, paymentUrl: json.payment_url };
    }
    return { ok: false, code: code || 'UNKNOWN', message: typeof json.message === 'string' ? json.message : 'Payment link not created' };
  }

  async transactionStatus(invoiceNumber: string): Promise<StatusResult> {
    const json = await this.post(
      '/transaction-status',
      new URLSearchParams({ invoice_number: invoiceNumber }),
      { merchantId: this.options.merchantId },
    );
    const code = String(json.status_code ?? '');
    const data = json.data as Record<string, unknown> | undefined;
    if (code === '200' && data && typeof data === 'object') {
      const str = (v: unknown) => (v === null || v === undefined ? '' : String(v));
      return {
        kind: 'found',
        data: {
          invoiceNumber: str(data.invoice_number),
          trxStatus: str(data.trx_status).trim().toLowerCase(),
          trxId: str(data.trx_id).trim(),
          paymentAmount: str(data.payment_amount).trim(),
          paymentMethod: str(data.payment_method).trim(),
        },
      };
    }
    if (code && code !== '200') {
      return { kind: 'not_found', code, message: typeof json.message === 'string' ? json.message : '' };
    }
    throw new ProviderUnavailableError('Unexpected transaction-status response');
  }

  private async post(path: string, body: URLSearchParams, headers: Record<string, string>): Promise<Record<string, unknown>> {
    let response: Response;
    try {
      response = await fetch(this.options.baseUrl.replace(/\/+$/, '') + path, {
        method: 'POST',
        headers: { Accept: 'application/json', 'Content-Type': 'application/x-www-form-urlencoded', ...headers },
        body,
        signal: AbortSignal.timeout(this.options.timeoutMs ?? 15_000),
        redirect: 'error',
      });
    } catch (error) {
      throw new ProviderUnavailableError(`PayStation request failed: ${(error as Error).name}`);
    }
    if (response.status >= 500) throw new ProviderUnavailableError(`PayStation HTTP ${response.status}`);
    try {
      const json = (await response.json()) as unknown;
      if (!json || typeof json !== 'object') throw new Error('not an object');
      return json as Record<string, unknown>;
    } catch {
      throw new ProviderUnavailableError(`PayStation returned a non-JSON response (HTTP ${response.status})`);
    }
  }
}
