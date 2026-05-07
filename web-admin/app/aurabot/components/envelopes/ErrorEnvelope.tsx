import type { ReactElement } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { ErrorEnvelope as ErrorEnvelopeData } from '../../types/envelope';

export function ErrorEnvelope({ envelope }: { envelope: ErrorEnvelopeData }): ReactElement {
  const { t } = useI18n();
  const retryLabel = t('aurabot.shell.error.retry', undefined, 'Retry');

  return (
    <div
      data-aurabot-envelope="error"
      role="alert"
      className="rounded-md border border-red-300 bg-red-50 p-3 text-sm text-red-900 dark:border-red-700 dark:bg-red-950/30 dark:text-red-200"
    >
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="font-medium">{envelope.message}</div>
          <div className="mt-0.5 text-xs opacity-70">code: {envelope.code}</div>
        </div>
        {envelope.retry && (
          <button
            type="button"
            onClick={() => envelope.retry?.()}
            data-aurabot-error-retry
            className="rounded border border-red-300 bg-white px-2 py-1 text-xs font-medium text-red-700 hover:bg-red-50 dark:border-red-700 dark:bg-transparent dark:text-red-200"
          >
            {retryLabel}
          </button>
        )}
      </div>
    </div>
  );
}
