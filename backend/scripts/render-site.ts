/**
 * Builds deploy/site/: the public website served by Caddy. It contains the policy pages (rendered
 * from the same HTML the app ships, so the texts never drift apart) and a simple landing page with
 * the APK download link.
 *
 *   EGP_DEVELOPER_NAME="..." EGP_SUPPORT_EMAIL=... EGP_PUBLISHER_WEBSITE=https://example.com \
 *   EGP_POLICY_EFFECTIVE_DATE=2026-11-01 EGP_APP_VERSION=1.1.0 npx tsx scripts/render-site.ts
 */
import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const out = join(root, 'deploy', 'site');
const required = ['EGP_DEVELOPER_NAME', 'EGP_SUPPORT_EMAIL', 'EGP_PUBLISHER_WEBSITE', 'EGP_POLICY_EFFECTIVE_DATE'] as const;
const missing = required.filter((k) => !process.env[k]);
if (missing.length) throw new Error(`Set ${missing.join(', ')} (real, verified values)`);

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
const values: Record<string, string> = {
  DEVELOPER_NAME: esc(process.env.EGP_DEVELOPER_NAME!),
  SUPPORT_EMAIL: `<a href="mailto:${esc(process.env.EGP_SUPPORT_EMAIL!)}">${esc(process.env.EGP_SUPPORT_EMAIL!)}</a>`,
  WEBSITE: `<a href="${esc(process.env.EGP_PUBLISHER_WEBSITE!)}">${esc(process.env.EGP_PUBLISHER_WEBSITE!)}</a>`,
  EFFECTIVE_DATE: esc(process.env.EGP_POLICY_EFFECTIVE_DATE!),
  VERSION: esc(process.env.EGP_APP_VERSION ?? '1.1.0'),
};

const STYLE = `<meta name="viewport" content="width=device-width,initial-scale=1"><style>
body{margin:0;font:16px/1.6 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#f8f9ff;color:#171b26}
main{max-width:760px;margin:0 auto;padding:24px 20px 64px}header{background:linear-gradient(135deg,#0074e7,#022785);color:#fff;padding:20px}
header a{color:#fff;text-decoration:none;font-weight:600;font-size:18px}h2{color:#022785}h3{margin-top:28px}a{color:#0038b0}
nav{margin-top:32px;font-size:14px}nav a{margin-right:16px}
@media (prefers-color-scheme:dark){body{background:#0e1220;color:#e1e4f2}h2{color:#b5c6ff}a{color:#9dcaff}}
</style>`;
const NAV = `<nav><a href="/">Home</a><a href="/legal/privacy.html">Privacy (website edition)</a><a href="/legal/privacy-play.html">Privacy (Google Play edition)</a><a href="/legal/terms.html">Terms</a><a href="/legal/refund.html">Payment &amp; refunds</a></nav>`;

function page(sourceRelative: string): string {
  let html = readFileSync(join(root, sourceRelative), 'utf8');
  for (const [key, value] of Object.entries(values)) html = html.replaceAll(`{{${key}}}`, value);
  const left = html.match(/\{\{[A-Z_]+\}\}/);
  if (left) throw new Error(`Unfilled placeholder ${left[0]} in ${sourceRelative}`);
  return html
    .replace('</head>', `${STYLE}</head>`)
    .replace('<body>', '<body><header><a href="/">e-GP Browser</a></header><main>')
    .replace('</body>', `${NAV}</main></body>`);
}

mkdirSync(join(out, 'legal'), { recursive: true });
mkdirSync(join(out, 'download'), { recursive: true });
const pages: Record<string, string> = {
  'legal/privacy.html': 'app/src/direct/assets/legal/privacy.html',
  'legal/terms.html': 'app/src/direct/assets/legal/terms.html',
  'legal/refund.html': 'app/src/direct/assets/legal/refund.html',
  'legal/privacy-play.html': 'app/src/main/assets/legal/privacy.html',
  'legal/terms-play.html': 'app/src/main/assets/legal/terms.html',
};
for (const [target, source] of Object.entries(pages)) writeFileSync(join(out, target), page(source));

copyFileSync(join(root, 'store', 'play-icon-512.png'), join(out, 'icon.png'));
const apk = 'download/e-GP-Browser.apk';
writeFileSync(join(out, 'index.html'), `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>e-GP Browser</title>${STYLE}</head>
<body><header><a href="/">e-GP Browser</a></header><main>
<p><img src="/icon.png" alt="" width="96" height="96"></p>
<h2>Bangladesh's e-GP portal, made easy on your phone</h2>
<p>A secure Android browser for the public e-GP portal (www.eprocure.gov.bd): desktop-site mode, tap-friendly menus, uploads and downloads.</p>
<p><b>Website edition:</b> 7-day free trial, then ৳250 once for lifetime access (paid securely through PayStation).
${existsSync(join(out, apk)) ? `<br><a href="/${apk}">Download the APK</a> (Android 8.0 or newer)` : '<br><i>Download coming soon.</i>'}</p>
<p><small>e-GP Browser is an independent app. It is not made by, affiliated with or endorsed by the Government of Bangladesh, BPPA, CPTU or the e-GP portal. Support: ${values.SUPPORT_EMAIL}</small></p>
${NAV}</main></body></html>`);
console.log(`Site written to ${out}`);
