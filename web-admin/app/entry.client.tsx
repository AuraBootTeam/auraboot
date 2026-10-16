import { startTransition, StrictMode } from 'react';
import { hydrateRoot } from 'react-dom/client';
import { HydratedRouter } from 'react-router/dom';

const endpoint = import.meta.env.VITE_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT;
if (endpoint) {
  const { installBrowserTracing } = await import('./shared/observability/browserTracing');
  installBrowserTracing(endpoint, Number(import.meta.env.VITE_OTEL_TRACES_SAMPLE_RATE || '0.1'));
}

startTransition(() => {
  hydrateRoot(
    document,
    <StrictMode>
      <HydratedRouter />
    </StrictMode>,
  );
});
