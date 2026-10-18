import { startTransition, StrictMode } from 'react';
import { hydrateRoot } from 'react-dom/client';
import { HydratedRouter } from 'react-router/dom';
import { installRouteRequestDiagnostics } from './shared/observability/routeRequestDiagnostics';

const { installBrowserTracing } = await import('./shared/observability/browserTracing');
installBrowserTracing(
  import.meta.env.VITE_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT,
  Number(import.meta.env.VITE_OTEL_TRACES_SAMPLE_RATE || '1'),
);

installRouteRequestDiagnostics();

startTransition(() => {
  hydrateRoot(
    document,
    <StrictMode>
      <HydratedRouter />
    </StrictMode>,
  );
});
