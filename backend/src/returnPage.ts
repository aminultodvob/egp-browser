import type { OrderStatus } from './payments/service.js';

const escapeHtml = (s: string) =>
  s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

/** Minimal, script-free result page shown in the browser after PayStation checkout. */
export function renderPaymentReturnPage(status: OrderStatus | null, appUrl: string): string {
  const [title, message] =
    status === 'PAID' ? ['Payment confirmed', 'Lifetime access is now active on your account. Return to the app to continue.']
    : status === 'PENDING' || status === 'PROCESSING' ? ['Payment being confirmed', 'We are waiting for confirmation from the payment provider. Return to the app; it will update automatically.']
    : status === 'FAILED' || status === 'CANCELLED' || status === 'EXPIRED' ? ['Payment not completed', 'No lifetime access was granted. You can try again from the app.']
    : status === 'VERIFICATION_REQUIRED' ? ['Payment under review', 'We could not confirm this payment automatically. Contact support from the app with your invoice number.']
    : status === 'REFUNDED' ? ['Payment refunded', 'This payment was refunded.']
    : ['Payment not found', 'Return to the app to check your access.'];
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(title)} · e-GP Browser</title>
<style>
body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#f8f9ff;color:#171b26;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{max-width:420px;margin:24px;padding:32px 24px;background:#fff;border-radius:24px;box-shadow:0 8px 32px rgba(2,39,133,.12);text-align:center}
h1{font-size:22px;margin:0 0 12px;color:#022785}p{line-height:1.5;color:#454a5c}
a{display:inline-block;margin-top:20px;padding:14px 28px;border-radius:28px;background:linear-gradient(135deg,#0074e7,#022785);color:#fff;text-decoration:none;font-weight:600}
small{display:block;margin-top:20px;color:#757a8e}
@media (prefers-color-scheme:dark){body{background:#0e1220;color:#e1e4f2}main{background:#181d2c}h1{color:#b5c6ff}p{color:#c3c7d9}}
</style></head>
<body><main><h1>${escapeHtml(title)}</h1><p>${escapeHtml(message)}</p>
<a href="${escapeHtml(appUrl)}">Return to e-GP Browser</a>
<small>Payments are processed by PayStation. e-GP Browser is an independent app.</small></main></body></html>`;
}
