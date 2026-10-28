import { createRemoteJWKSet, jwtVerify } from 'jose';
import { ApiError } from '../errors.js';

export interface GoogleIdentity {
  sub: string;
  email: string;
  name?: string;
}

export type GoogleVerifier = (idToken: string) => Promise<GoogleIdentity>;

const GOOGLE_ISSUERS = ['https://accounts.google.com', 'accounts.google.com'];

/**
 * Verifies a Google ID token as documented by Google: RS256 signature against Google's published
 * keys, issuer, audience (our OAuth client IDs), expiry, and a verified email address.
 */
export function googleVerifier(clientIds: string[]): GoogleVerifier {
  const jwks = createRemoteJWKSet(new URL('https://www.googleapis.com/oauth2/v3/certs'));
  return async (idToken) => {
    let payload;
    try {
      ({ payload } = await jwtVerify(idToken, jwks, {
        issuer: GOOGLE_ISSUERS,
        audience: clientIds,
        algorithms: ['RS256'],
      }));
    } catch {
      throw new ApiError(401, 'INVALID_GOOGLE_TOKEN', 'Google sign-in could not be verified.');
    }
    if (typeof payload.sub !== 'string' || typeof payload.email !== 'string' || payload.email_verified !== true) {
      throw new ApiError(401, 'GOOGLE_EMAIL_UNVERIFIED', 'Use a Google account with a verified email address.');
    }
    return { sub: payload.sub, email: payload.email, name: typeof payload.name === 'string' ? payload.name : undefined };
  };
}
