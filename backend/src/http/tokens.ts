/**
 * Dependency-injection tokens. Every constructor parameter is injected with an explicit token
 * (@Inject(...)), so the app does not depend on TypeScript's emitted type metadata.
 */
export const CONFIG = Symbol('CONFIG');
export const POOL = Symbol('POOL');
export const GOOGLE_VERIFIER = Symbol('GOOGLE_VERIFIER');
export const PAYSTATION = Symbol('PAYSTATION');
