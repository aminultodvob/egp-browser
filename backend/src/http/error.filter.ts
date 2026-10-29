import { Catch, HttpException, Logger, type ArgumentsHost, type ExceptionFilter } from '@nestjs/common';
import { ThrottlerException } from '@nestjs/throttler';
import type { FastifyReply } from 'fastify';
import { z } from 'zod';
import { ApiError } from '../errors.js';

/**
 * One error format for every endpoint: {"error": {"code", "message"}}. Messages are safe to show to
 * users; unexpected errors are logged by type only and reported as INTERNAL.
 */
@Catch()
export class ApiExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger('ApiExceptionFilter');

  catch(exception: unknown, host: ArgumentsHost): void {
    const reply = host.switchToHttp().getResponse<FastifyReply>();
    const send = (status: number, code: string, message: string) =>
      void reply.status(status).send({ error: { code, message } });

    if (exception instanceof ApiError) return send(exception.statusCode, exception.code, exception.message);
    if (exception instanceof z.ZodError) {
      return send(400, 'INVALID_REQUEST', exception.issues[0]?.message ?? 'Invalid request.');
    }
    if (exception instanceof ThrottlerException) return send(429, 'RATE_LIMITED', 'Too many requests. Try again shortly.');
    if (exception instanceof HttpException) {
      const status = exception.getStatus();
      if (status < 500) return send(status, status === 404 ? 'NOT_FOUND' : 'INVALID_REQUEST', exception.message);
    }
    this.logger.error(`Unhandled ${(exception as Error)?.name ?? typeof exception}`, (exception as Error)?.stack);
    send(500, 'INTERNAL', 'Something went wrong. Try again.');
  }
}
