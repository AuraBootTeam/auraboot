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

// PDA offline shell (WMS D3): ServiceWorker registration + offline replay
// executor. Best-effort — unsupported browsers and failed registrations
// degrade silently (never blocks hydration).
import('./shared/pda/offlineReplay')
  .then((m) => m.startPdaOfflineShell())
  .catch((err) => console.warn('[pda-offline] shell start failed', err));

startTransition(() => {
  hydrateRoot(
    document,
    <StrictMode>
      <HydratedRouter />
    </StrictMode>,
  );
});
