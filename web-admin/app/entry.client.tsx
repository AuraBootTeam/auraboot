import { startTransition, StrictMode } from 'react';
import { hydrateRoot } from 'react-dom/client';
import { HydratedRouter } from 'react-router/dom';
import { installRouteRequestDiagnostics } from './shared/observability/routeRequestDiagnostics';

const endpoint = import.meta.env.VITE_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT;
if (endpoint) {
  const { installBrowserTracing } = await import('./shared/observability/browserTracing');
  installBrowserTracing(endpoint, Number(import.meta.env.VITE_OTEL_TRACES_SAMPLE_RATE || '0.1'));
}

installRouteRequestDiagnostics();

startTransition(() => {
  hydrateRoot(
    document,
    <StrictMode>
      <HydratedRouter />
    </StrictMode>,
  );
});
