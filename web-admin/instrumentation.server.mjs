// Preload before HTTP/fetch modules in every environment. Exporting is optional; tracing is not.
import { appendFile, chmod, mkdir, rename, stat, unlink } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { createRequire, syncBuiltinESMExports } from 'node:module';

/** Retain a bounded, credential-free local index even without an OTLP collector. */
export class LocalSpanExporter {
  constructor(directory, serviceName, maxBytes = 10 * 1024 * 1024) {
    this.directory = resolve(directory);
    this.file = join(this.directory, serviceName.replace(/[^a-zA-Z0-9_-]/g, '_') + '.jsonl');
    this.maxBytes = maxBytes;
    this.pending = Promise.resolve();
    this.warned = false;
  }
  export(spans, callback) {
    const records = spans.map((span) => {
      const attributes = {};
      for (const key of [
        'request.id',
        'url.path',
        'http.request.method',
        'http.response.status_code',
        'http.method',
        'http.status_code',
        'db.system',
      ]) {
        const value = span.attributes[key];
        if (typeof value === 'string' || typeof value === 'number')
          attributes[key] = String(value).split('?')[0].slice(0, 512);
      }
      return JSON.stringify({
        event: 'trace-span',
        traceId: span.spanContext().traceId,
        spanId: span.spanContext().spanId,
        parentSpanId: span.parentSpanContext?.spanId,
        name: span.name.split('?')[0].slice(0, 256),
        kind: span.kind,
        startTime: span.startTime,
        duration: span.duration,
        status: span.status.code,
        attributes,
        // Only our loader tracer records pre-sanitized exception messages/stacks.
        events:
          span.instrumentationScope?.name === 'auraboot-ssr'
            ? span.events
                .filter((event) => event.name === 'exception')
                .map((event) => ({
                  name: 'exception',
                  exceptionType: event.attributes?.['exception.type'],
                  message: String(event.attributes?.['exception.message'] || '').slice(0, 2000),
                  stack: String(event.attributes?.['exception.stacktrace'] || '').slice(0, 8000),
                }))
            : [],
      });
    });
    this.pending = this.pending
      .then(async () => {
        await mkdir(this.directory, { recursive: true, mode: 0o700 });
        for (const record of records) {
          const size = await stat(this.file).then(
            (value) => value.size,
            (error) => {
              if (error.code === 'ENOENT') return 0;
              throw error;
            },
          );
          if (size + Buffer.byteLength(record) + 1 > this.maxBytes) {
            await unlink(this.file + '.4').catch((error) => {
              if (error.code !== 'ENOENT') throw error;
            });
            for (let index = 3; index >= 0; index--) {
              await rename(
                this.file + (index ? '.' + index : ''),
                this.file + '.' + (index + 1),
              ).catch((error) => {
                if (error.code !== 'ENOENT') throw error;
              });
            }
          }
          await appendFile(this.file, record + '\n', { mode: 0o600 });
          await chmod(this.file, 0o600);
        }
        callback({ code: 0 });
      })
      .catch((error) => {
        if (!this.warned) {
          console.error('[tracing] Local span retention unavailable:', error.code || error.name);
          this.warned = true;
        }
        callback({ code: 1, error });
      });
  }
  async shutdown() {
    await this.pending;
  }
  async forceFlush() {
    await this.pending;
  }
}

if (process.env.OTEL_SDK_DISABLED !== 'true') {
  const [
    { NodeSDK },
    { OTLPTraceExporter },
    { HttpInstrumentation },
    { UndiciInstrumentation },
    { BatchSpanProcessor },
  ] = await Promise.all([
    import('@opentelemetry/sdk-node'),
    import('@opentelemetry/exporter-trace-otlp-http'),
    import('@opentelemetry/instrumentation-http'),
    import('@opentelemetry/instrumentation-undici'),
    import('@opentelemetry/sdk-trace-base'),
  ]);
  const serviceName =
    process.env.OTEL_SERVICE_NAME ||
    (process.argv.some((arg) => arg.includes('bff.server')) ? 'auraboot-bff' : 'auraboot-ssr');
  const processors = [
    new BatchSpanProcessor(
      new LocalSpanExporter(process.env.AURA_TRACE_LOG_DIR || './logs/traces', serviceName),
    ),
  ];
  const endpoint = process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT;
  if (endpoint) processors.push(new BatchSpanProcessor(new OTLPTraceExporter({ url: endpoint })));
  const sdk = new NodeSDK({
    serviceName,
    spanProcessors: processors,
    instrumentations: [
      new HttpInstrumentation({
        ignoreIncomingRequestHook: (request) =>
          /\.(?:js|tsx?|css|map|woff2?|png|svg)(?:\?|$)/.test(request.url || '') ||
          (request.url || '').startsWith('/@'),
      }),
      new UndiciInstrumentation(),
    ],
  });
  sdk.start();
  // Trigger the builtin require hooks before an ESM entrypoint imports node:http.
  const require = createRequire(import.meta.url);
  require('http');
  require('https');
  syncBuiltinESMExports();
  const shutdown = () => {
    void sdk
      .shutdown()
      .catch(() => {})
      .finally(() => process.exit(0));
  };
  process.once('SIGTERM', shutdown);
  process.once('SIGINT', shutdown);
}
