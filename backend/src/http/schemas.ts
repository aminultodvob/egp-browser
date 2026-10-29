import { z } from 'zod';

export const INVOICE = /^[A-Z0-9]{8,40}$/;
/** Bangladeshi mobile number as PayStation expects it (e.g. 01712345678). */
export const BD_MOBILE = /^01[3-9]\d{8}$/;

// .strict(): unknown fields (e.g. a client-chosen amount or start time) are rejected, not ignored.
export const googleSignInBody = z.object({ idToken: z.string().min(20).max(4096) }).strict();
export const refreshBody = z.object({ refreshToken: z.string().min(20).max(200) }).strict();
export const trialBody = z.object({ deviceId: z.string().min(4).max(128).optional() }).strict();
export const paymentBody = z
  .object({
    customerName: z.string().trim().min(2).max(80),
    customerPhone: z.string().trim().regex(BD_MOBILE, 'Use an 11-digit Bangladeshi mobile number'),
  })
  .strict();
export const ipnBody = z.object({ invoice_number: z.string().max(64) }).passthrough();
