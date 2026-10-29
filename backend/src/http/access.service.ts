import { Inject, Injectable } from '@nestjs/common';
import type { Config } from '../config.js';
import type { Pool } from '../db.js';
import { hmac } from '../auth/tokens.js';
import { EntitlementSigner, entitlementResponse, loadEntitlement } from '../entitlement.js';
import { CONFIG, POOL } from './tokens.js';

/** Builds the signed entitlement response for a user. */
@Injectable()
export class AccessService {
  constructor(
    @Inject(POOL) private readonly pool: Pool,
    @Inject(CONFIG) private readonly config: Config,
    @Inject(EntitlementSigner) private readonly signer: EntitlementSigner,
  ) {}

  /** Keyed hash of the user's Google subject, used to remember trial use across account deletion. */
  async subjectHash(userId: string): Promise<Buffer | null> {
    const { rows } = await this.pool.query<{ google_sub: string }>('SELECT google_sub FROM users WHERE id = $1', [userId]);
    return rows[0] ? hmac(this.config.TRIAL_HASH_SECRET, `sub:${rows[0].google_sub}`) : null;
  }

  async entitlementFor(userId: string) {
    return entitlementResponse(await loadEntitlement(this.pool, userId, await this.subjectHash(userId)), this.signer);
  }
}
