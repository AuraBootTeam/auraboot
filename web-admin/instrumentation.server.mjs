// Loaded before the HTTP server and fetch clients. No exporter means no SDK or collector dependency.
const endpoint = process.env.OTEL_EXPORTER_OTLP_TRACES_ENDPOINT;
if (endpoint) {
  const [{ NodeSDK }, { OTLPTraceExporter }, { HttpInstrumentation }, { UndiciInstrumentation }] =
    await Promise.all([
      import('@opentelemetry/sdk-node'),
      import('@opentelemetry/exporter-trace-otlp-http'),
      import('@opentelemetry/instrumentation-http'),
      import('@opentelemetry/instrumentation-undici'),
    ]);
  const sdk = new NodeSDK({
    serviceName:
      process.env.OTEL_SERVICE_NAME ||
      (process.argv.some((arg) => arg.includes('bff.server')) ? 'auraboot-bff' : 'auraboot-ssr'),
    traceExporter: new OTLPTraceExporter({ url: endpoint }),
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
  const shutdown = () => {
    void sdk.shutdown().catch(() => {}).finally(() => process.exit(0));
  };
  process.once('SIGTERM', shutdown);
  process.once('SIGINT', shutdown);
}
