import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { FastifyAdapter, type NestFastifyApplication } from '@nestjs/platform-fastify';
import { AppModule, type AppDeps } from './app.module.js';

export type { AppDeps } from './app.module.js';

/** Creates the NestJS application (Fastify adapter). Used by server.ts and by the tests. */
export async function createApp(deps: AppDeps, options: { logger?: boolean } = {}): Promise<NestFastifyApplication> {
  const adapter = new FastifyAdapter({ trustProxy: deps.config.TRUST_PROXY, bodyLimit: 32 * 1024 });
  const app = await NestFactory.create<NestFastifyApplication>(AppModule.forRoot(deps), adapter, {
    logger: options.logger === false ? false : ['error', 'warn', 'log'],
    abortOnError: false,
  });
  await app.init();
  return app;
}
