import type { ReactElement } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { PreviewEnvelope as PreviewEnvelopeData } from '../../types/envelope';

export function PreviewEnvelope({
  envelope,
}: {
  envelope: PreviewEnvelopeData;
}): ReactElement {
  const { t } = useI18n();
  const title = t('aurabot.shell.preview.title', undefined, 'Operation preview');

  return (
    <div
      data-aurabot-envelope="preview"
      className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-700/60 dark:bg-amber-950/30 dark:text-amber-200"
    >
      <div className="mb-2 flex items-center justify-between">
        <span className="font-medium">{title}</span>
        <span
          className="rounded bg-amber-200 px-1.5 py-0.5 text-xs font-medium text-amber-900 dark:bg-amber-800 dark:text-amber-100"
          data-aurabot-risk={envelope.riskLevel}
        >
          {envelope.riskLevel}
        </span>
      </div>
      <pre className="overflow-auto whitespace-pre-wrap break-words rounded bg-white/50 p-2 font-mono text-xs dark:bg-black/20">
        {JSON.stringify(envelope.preview, null, 2)}
      </pre>
    </div>
  );
}
