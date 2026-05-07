import type { ReactElement } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { ResultEnvelope as ResultEnvelopeData } from '../../types/envelope';

export function ResultEnvelope({
  envelope,
}: {
  envelope: ResultEnvelopeData;
}): ReactElement {
  const { t } = useI18n();
  const title = t('aurabot.shell.result.title', undefined, 'Result');

  return (
    <div
      data-aurabot-envelope="result"
      className="rounded-md border border-emerald-300 bg-emerald-50 p-3 text-sm text-emerald-900 dark:border-emerald-700/60 dark:bg-emerald-950/30 dark:text-emerald-200"
    >
      <div className="mb-2 font-medium">{title}</div>
      <pre className="overflow-auto whitespace-pre-wrap break-words rounded bg-white/50 p-2 font-mono text-xs dark:bg-black/20">
        {JSON.stringify(envelope.payload, null, 2)}
      </pre>
    </div>
  );
}
