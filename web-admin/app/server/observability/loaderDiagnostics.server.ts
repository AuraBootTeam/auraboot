import { RootDependencyUnavailableError } from '~/shared/observability/rootReadErrors';
export { RootDependencyUnavailableError } from '~/shared/observability/rootReadErrors';
import { randomUUID } from 'node:crypto';
import { trace, SpanStatusCode } from '@opentelemetry/api';
import logger from '~/server/utils/logger';

export function requestCorrelation(request: Request) {
  const supplied = request.headers.get('x-request-id') || '';
  const requestId = /^[A-Za-z0-9_-]{1,128}$/.test(supplied) ? supplied : randomUUID();
  const parent = request.headers.get('traceparent') || '';
  const propagated = /^00-([a-f0-9]{32})-([a-f0-9]{16})-[a-f0-9]{2}$/.exec(parent);
  const active = trace.getActiveSpan()?.spanContext().traceId;
  const incoming =
    propagated && !/^0+$/.test(propagated[1]) && !/^0+$/.test(propagated[2])
      ? propagated[1]
      : undefined;
  const traceId = active && !/^0+$/.test(active) ? active : incoming;
  return { requestId, traceId, path: new URL(request.url).pathname, method: request.method };
}

// Never serialize an Error object: HTTP client errors can contain credentials and bodies.
export function safeLoaderError(error: unknown): Record<string, unknown> {
  if (
    !(error instanceof Error) &&
    !(typeof DOMException !== 'undefined' && error instanceof DOMException)
  )
    return { name: 'NonErrorThrow', type: typeof error };
  const scrub = (value: string) =>
    value
      .replace(/Bearer\s+[^\s,;]+/gi, 'Bearer [REDACTED]')
      .replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[REDACTED_JWT]')
      .replace(
        /((?:cookie|authorization|password|token|secret)\s*[:=]\s*)[^\s,;]+/gi,
        '$1[REDACTED]',
      )
      .replace(/https?:\/\/[^\s)]+/g, (url) => {
        try {
          const parsed = new URL(url);
          return parsed.origin + parsed.pathname;
        } catch {
          return '[URL]';
        }
      });
  const result: Record<string, unknown> = {
    name: error.name,
    message: scrub(error.message).slice(0, 2000),
    stack: error.stack
      ?.split('\n')
      .filter((line, index) => index === 0 || /^\s+at /.test(line))
      .map(scrub)
      .join('\n')
      .slice(0, 8000),
  };
  // Preserve transport cause identity without logging the HTTP client's request/config.
  if ((error as Error).cause instanceof Error) {
    const cause = (error as Error).cause as Error & { code?: unknown };
    const code = cause.code;
    result.cause = {
      name: cause.name,
      message: scrub(cause.message).slice(0, 1000),
      ...(typeof code === 'string' && /^[A-Z0-9_]{1,64}$/.test(code) ? { code } : {}),
    };
  }
  return result;
}

export function createLoaderDiagnostics(request: Request) {
  const correlation = requestCorrelation(request);
  let failedStage: string | undefined;
  return {
    correlation,
    get failedStage() {
      return failedStage;
    },
    async stage<T>(stage: string, operation: () => Promise<T>): Promise<T> {
      const started = performance.now();
      return trace
        .getTracer('auraboot-ssr')
        .startActiveSpan(`root.loader.${stage}`, async (span) => {
          span.setAttribute('request.id', correlation.requestId);
          span.setAttribute('url.path', correlation.path);
          try {
            const result = await operation();
            logger.debug(
              {
                event: 'root-loader-stage',
                ...correlation,
                stage,
                durationMs: Math.round(performance.now() - started),
                outcome: 'success',
              },
              'Root loader stage completed',
            );
            return result;
          } catch (error) {
            failedStage ??= stage;
            const status = error instanceof Response ? error.status : undefined;
            const expectedResponse = status !== undefined && status < 500;
            const fields = {
              event: 'root-loader-stage',
              ...correlation,
              stage,
              status,
              durationMs: Math.round(performance.now() - started),
              outcome: expectedResponse ? 'response' : 'failure',
              ...(expectedResponse ? {} : { exception: safeLoaderError(error) }),
            };
            if (expectedResponse) logger.debug(fields, 'Root loader returned a response');
            else {
              logger.error(fields, 'Root loader stage failed');
              span.setStatus({ code: SpanStatusCode.ERROR });
              const safe = safeLoaderError(error);
              span.recordException({
                name: String(safe.name),
                message: String(safe.message || ''),
                stack: String(safe.stack || ''),
              });
            }
            throw error;
          } finally {
            span.end();
          }
        });
    },
  };
}

export function reportRouterError(error: unknown, request: Request): void {
  logger.error(
    {
      event: 'react-router-request-error',
      ...requestCorrelation(request),
      exception: safeLoaderError(error),
    },
    'React Router request failed',
  );
}

export function isRootDependencyUnavailable(error: unknown): boolean {
  if (error instanceof RootDependencyUnavailableError) return true;
  if (!error || typeof error !== 'object') return false;
  const details = error as { name?: string; message?: string; code?: string };
  if (details.name === 'TimeoutError') return true;
  if (
    details.name === 'UserInfoUnavailableError' &&
    ['network_error', 'timeout_error'].includes(details.code || '')
  )
    return true;
  return (
    details.name === 'TypeError' &&
    /fetch failed|failed to fetch|networkerror/i.test(details.message || '')
  );
}
