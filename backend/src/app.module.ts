import { Module, type DynamicModule } from '@nestjs/common';
import { APP_FILTER } from '@nestjs/core';
import { ThrottlerModule } from '@nestjs/throttler';
import type { Config } from './config.js';
import type { Pool } from './db.js';
import type { GoogleVerifier } from './auth/google.js';
import { TokenService } from './auth/tokens.js';
import { EntitlementSigner } from './entitlement.js';
import type { PayStationApi } from './payments/paystation.js';
import { PaymentService } from './payments/service.js';
import { AccessController } from './http/access.controller.js';
import { AccessService } from './http/access.service.js';
import { AuthController } from './http/auth.controller.js';
import { ApiExceptionFilter } from './http/error.filter.js';
import { AuthGuard, CronGuard, UserThrottlerGuard } from './http/guards.js';
import { OpsController } from './http/ops.controller.js';
import { PaymentController } from './http/payment.controller.js';
import { CONFIG, GOOGLE_VERIFIER, PAYSTATION, POOL } from './http/tokens.js';

/** External dependencies, injected so tests can supply fakes for Google and PayStation. */
export interface AppDeps {
  config: Config;
  pool: Pool;
  verifyGoogle: GoogleVerifier;
  paystation: PayStationApi;
}

@Module({})
export class AppModule {
  static forRoot(deps: AppDeps): DynamicModule {
    return {
      module: AppModule,
      // Default budget per route and client; sensitive routes set tighter limits with @Throttle.
      imports: [ThrottlerModule.forRoot([{ name: 'default', ttl: 60_000, limit: 120 }])],
      controllers: [AuthController, AccessController, PaymentController, OpsController],
      providers: [
        { provide: CONFIG, useValue: deps.config },
        { provide: POOL, useValue: deps.pool },
        { provide: GOOGLE_VERIFIER, useValue: deps.verifyGoogle },
        { provide: PAYSTATION, useValue: deps.paystation },
        { provide: TokenService, useFactory: (config: Config, pool: Pool) => new TokenService(config.JWT_SECRET, pool), inject: [CONFIG, POOL] },
        { provide: EntitlementSigner, useFactory: (config: Config) => EntitlementSigner.create(config.ENTITLEMENT_PRIVATE_KEY), inject: [CONFIG] },
        {
          provide: PaymentService,
          useFactory: (config: Config, pool: Pool, paystation: PayStationApi) =>
            new PaymentService(pool, paystation, {
              publicBaseUrl: config.PUBLIC_BASE_URL,
              // Only PayStation's own HTTPS checkout host (from the configured base URL) is accepted.
              checkoutHosts: [new URL(config.PAYSTATION_BASE_URL).hostname.toLowerCase()],
            }),
          inject: [CONFIG, POOL, PAYSTATION],
        },
        AccessService,
        AuthGuard,
        CronGuard,
        UserThrottlerGuard,
        { provide: APP_FILTER, useClass: ApiExceptionFilter },
      ],
    };
  }
}
