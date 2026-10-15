import {
  context,
  propagation,
  ROOT_CONTEXT,
  SpanKind,
  SpanStatusCode,
  trace,
} from '@opentelemetry/api';
import { W3CTraceContextPropagator } from '@opentelemetry/core';
import { resourceFromAttributes } from '@opentelemetry/resources';
import {
  BatchSpanProcessor,
  ParentBasedSampler,
  TraceIdRatioBasedSampler,
} from '@opentelemetry/sdk-trace-base';
import { WebTracerProvider } from '@opentelemetry/sdk-trace-web';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';

/** Trace only same-origin application traffic; never attach context to third-party URLs. */
export function installBrowserTracing(endpoint: string, sampleRate = 0.1): void {
  const provider = new WebTracerProvider({
    resource: resourceFromAttributes({ 'service.name': 'auraboot-web' }),
    sampler: new ParentBasedSampler({ root: new TraceIdRatioBasedSampler(sampleRate) }),
    spanProcessors: [new BatchSpanProcessor(new OTLPTraceExporter({ url: endpoint }))],
  });
  provider.register({ propagator: new W3CTraceContextPropagator() });
  const tracer = provider.getTracer('auraboot-web');
  const parent = document.querySelector('meta[name="traceparent"]')?.getAttribute('content');
  const pageSpan = tracer.startSpan(
    'ui.page',
    { attributes: { 'url.path': location.pathname } },
    propagation.extract(ROOT_CONTEXT, parent ? { traceparent: parent } : {}),
  );
  let pageContext = trace.setSpan(ROOT_CONTEXT, pageSpan);
  const finishLoad = () => pageSpan.end();
  if (document.readyState === 'complete') finishLoad();
  else window.addEventListener('load', finishLoad, { once: true });

  const originalFetch = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input), location.href);
    if (
      url.origin !== location.origin ||
      !(url.pathname.startsWith('/api/') || url.pathname.endsWith('.data'))
    ) {
      return originalFetch(input, init);
    }
    const navigation = url.pathname.endsWith('.data')
      ? tracer.startSpan(
          'ui.navigation',
          { attributes: { 'url.path': url.pathname } },
          ROOT_CONTEXT,
        )
      : undefined;
    if (navigation) pageContext = trace.setSpan(ROOT_CONTEXT, navigation);
    const span = tracer.startSpan(
      'http.client',
      {
        kind: SpanKind.CLIENT,
        attributes: {
          'http.request.method': init?.method || (input instanceof Request ? input.method : 'GET'),
          'url.path': url.pathname,
        },
      },
      pageContext,
    );
    const headers = new Headers(
      init?.headers || (input instanceof Request ? input.headers : undefined),
    );
    const carrier: Record<string, string> = {};
    propagation.inject(trace.setSpan(context.active(), span), carrier);
    for (const [key, value] of Object.entries(carrier)) headers.set(key, value);
    try {
      const response = await originalFetch(input, { ...init, headers });
      span.setAttribute('http.response.status_code', response.status);
      if (response.status >= 500) span.setStatus({ code: SpanStatusCode.ERROR });
      return response;
    } catch (error) {
      span.setStatus({ code: SpanStatusCode.ERROR });
      throw error;
    } finally {
      span.end();
      navigation?.end();
    }
  };
  // Flush the bounded batch on navigation away; telemetry never delays a user action.
  window.addEventListener('pagehide', () => {
    void provider.forceFlush().catch(() => {});
  });
}
