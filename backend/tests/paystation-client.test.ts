import assert from 'node:assert/strict';
import { createServer, type IncomingMessage, type Server } from 'node:http';
import { after, before, describe, test } from 'node:test';
import { PayStationClient, ProviderUnavailableError } from '../src/payments/paystation.js';
import { isExactAmount } from '../src/payments/service.js';

interface Captured { path: string; headers: IncomingMessage['headers']; body: URLSearchParams }

let server: Server;
let baseUrl: string;
let captured: Captured[] = [];
let respond: (path: string) => { status: number; body: string } = () => ({ status: 200, body: '{}' });

before(async () => {
  server = createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      captured.push({ path: req.url!, headers: req.headers, body: new URLSearchParams(raw) });
      const r = respond(req.url!);
      res.writeHead(r.status, { 'Content-Type': 'application/json' }).end(r.body);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  baseUrl = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
});
after(() => server.close());

const client = () => new PayStationClient({ baseUrl, merchantId: 'M-1', password: 'secret-pass', payWithCharge: '0', timeoutMs: 2000 });

describe('PayStationClient (documented API v1)', () => {
  test('initiate-payment sends the documented form fields', async () => {
    captured = [];
    respond = () => ({ status: 200, body: JSON.stringify({ status_code: '200', status: 'success', message: 'Payment Link Created Successfully.', payment_amount: '250', invoice_number: 'EGPX', payment_url: 'https://api.paystation.com.bd/checkout/1/abc' }) });
    const result = await client().initiate({
      invoiceNumber: 'EGPX', amount: 250, customerName: 'Rahim', customerPhone: '01712345678', customerEmail: 'r@example.com',
      callbackUrl: 'https://api.test.example/payment/return/EGPX', reference: 'ref', checkoutItems: 'items',
    });
    assert.deepEqual(result, { ok: true, paymentUrl: 'https://api.paystation.com.bd/checkout/1/abc' });
    const req = captured[0]!;
    assert.equal(req.path, '/initiate-payment');
    assert.match(String(req.headers['content-type']), /application\/x-www-form-urlencoded/);
    assert.deepEqual(Object.fromEntries(req.body), {
      merchantId: 'M-1', password: 'secret-pass', invoice_number: 'EGPX', currency: 'BDT', payment_amount: '250',
      pay_with_charge: '0', reference: 'ref', cust_name: 'Rahim', cust_phone: '01712345678', cust_email: 'r@example.com',
      callback_url: 'https://api.test.example/payment/return/EGPX', checkout_items: 'items',
    });
  });

  test('initiate-payment failure codes are surfaced', async () => {
    respond = () => ({ status: 200, body: JSON.stringify({ status_code: '1008', status: 'failed', message: 'Duplicate invoice number.' }) });
    assert.deepEqual(await client().initiate({
      invoiceNumber: 'EGPX', amount: 250, customerName: 'R', customerPhone: '01712345678', customerEmail: 'r@e.com',
      callbackUrl: 'https://x/y', reference: 'r', checkoutItems: 'c',
    }), { ok: false, code: '1008', message: 'Duplicate invoice number.' });
  });

  test('transaction-status sends merchantId as a header (never the password) and normalises status', async () => {
    captured = [];
    respond = () => ({ status: 200, body: JSON.stringify({ status_code: '200', status: 'success', message: 'Transaction found.', data: { invoice_number: 'EGPX', trx_status: 'Success', trx_id: '10XB9900', payment_amount: '250', payment_method: 'bkash' } }) });
    const result = await client().transactionStatus('EGPX');
    assert.equal(result.kind, 'found');
    assert.equal(result.kind === 'found' && result.data.trxStatus, 'success');
    const req = captured[0]!;
    assert.equal(req.path, '/transaction-status');
    assert.equal(req.headers.merchantid, 'M-1');
    assert.deepEqual(Object.fromEntries(req.body), { invoice_number: 'EGPX' });
  });

  test('not-found and outages are distinguished', async () => {
    respond = () => ({ status: 200, body: JSON.stringify({ status_code: '2001', status: 'failed', message: 'Invalid Token.' }) });
    assert.equal((await client().transactionStatus('EGPX')).kind, 'not_found');
    respond = () => ({ status: 502, body: 'bad gateway' });
    await assert.rejects(client().transactionStatus('EGPX'), ProviderUnavailableError);
    respond = () => ({ status: 200, body: '<html>maintenance</html>' });
    await assert.rejects(client().transactionStatus('EGPX'), ProviderUnavailableError);
    const unreachable = new PayStationClient({ baseUrl: 'http://127.0.0.1:1', merchantId: 'M', password: 'p', payWithCharge: '0', timeoutMs: 500 });
    await assert.rejects(unreachable.transactionStatus('EGPX'), ProviderUnavailableError);
  });
});

describe('amount check', () => {
  test('only exactly ৳250 matches', () => {
    for (const ok of ['250', '250.0', '250.00']) assert.equal(isExactAmount(ok, 250), true, ok);
    for (const bad of ['249.99', '250.01', '2500', '25', '', 'abc', '250.001', '-250', '1.00']) assert.equal(isExactAmount(bad, 250), false, bad);
  });
});
