import { useI18n } from '~/contexts/I18nContext';
import { diagnosticText } from './diagnosticsCopy';

/** Explicit recovery only; no background retry or successful-state fallback. */
export function DecisionLoadError({ testId, error, onRetry, busy = false }: {
  testId: string; error?: unknown; onRetry: () => unknown; busy?: boolean;
}) {
  const { locale } = useI18n();
  return <div role="alert" className="decisionops-state is-error" data-testid={testId}>
    <strong>{diagnosticText('loadFailed', locale)}</strong>
    <p>{diagnosticText('loadHelp', locale)}</p>
    <button type="button" disabled={busy} onClick={() => { void onRetry(); }}>{diagnosticText(busy ? 'retrying' : 'retry', locale)}</button>
    {error instanceof Error && <details className="mt-2 text-xs"><summary>{diagnosticText('technical', locale)}</summary>{error.message}</details>}
  </div>;
}
