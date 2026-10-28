import { z } from 'zod';

/** One-time lifetime price. Fixed in code on purpose: clients can never influence it. */
export const PRICE_BDT = 250;
export const CURRENCY = 'BDT';
export const TRIAL_DAYS = 7;

const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  HOST: z.string().default('0.0.0.0'),
  PORT: z.coerce.number().int().positive().default(8080),
  /** Set to true only when running behind a reverse proxy that sets X-Forwarded-For. */
  TRUST_PROXY: z.enum(['true', 'false']).default('false').transform((v) => v === 'true'),
  DATABASE_URL: z.string().min(1),
  /** Public HTTPS origin of this API, e.g. https://api.example.com. Used for PayStation callback_url. */
  PUBLIC_BASE_URL: z.string().url(),
  /** HMAC key for access tokens; at least 32 random bytes. */
  JWT_SECRET: z.string().min(32),
  /** HMAC key used to pseudonymise Google subjects and device IDs for trial abuse prevention. */
  TRIAL_HASH_SECRET: z.string().min(32),
  /** ES256 private key (PKCS#8 PEM, "\n" escapes allowed) that signs entitlement tokens. */
  ENTITLEMENT_PRIVATE_KEY: z.string().min(1),
  /** Comma-separated OAuth client IDs whose Google ID tokens are accepted (the Android app's web client ID). */
  GOOGLE_CLIENT_IDS: z
    .string()
    .min(1)
    .transform((s) => s.split(',').map((x) => x.trim()).filter(Boolean)),
  PAYSTATION_BASE_URL: z.string().url().default('https://sandbox.paystation.com.bd'),
  PAYSTATION_MERCHANT_ID: z.string().min(1),
  PAYSTATION_PASSWORD: z.string().min(1),
  /** 0: merchant pays the gateway charge (keeps the verified amount exactly ৳250). */
  PAYSTATION_PAY_WITH_CHARGE: z.enum(['0', '1']).default('0'),
  /** Deep link the payment result page offers to reopen the app. */
  APP_RETURN_URL: z.string().default('egpbrowser://payment-result'),
  MAX_TRIALS_PER_DEVICE: z.coerce.number().int().min(1).default(1),
});

export type Config = z.infer<typeof schema>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = schema.safeParse(env);
  if (!parsed.success) {
    const fields = parsed.error.issues.map((i) => i.path.join('.')).join(', ');
    // Only field names are reported, never values.
    throw new Error(`Invalid or missing configuration: ${fields}`);
  }
  const config = parsed.data;
  if (config.NODE_ENV === 'production') {
    for (const key of ['PUBLIC_BASE_URL', 'PAYSTATION_BASE_URL'] as const) {
      if (!config[key].startsWith('https://')) throw new Error(`${key} must use https in production`);
    }
  }
  config.ENTITLEMENT_PRIVATE_KEY = config.ENTITLEMENT_PRIVATE_KEY.replace(/\\n/g, '\n');
  return config;
}
