import { useState, type ReactElement } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { ConfirmEnvelope as ConfirmEnvelopeData } from '../../types/envelope';

export interface ConfirmEnvelopeProps {
  envelope: ConfirmEnvelopeData;
  onConfirm?: (previewToken: string) => void;
  onCancel?: (previewToken: string) => void;
}

export function ConfirmEnvelope({
  envelope,
  onConfirm,
  onCancel,
}: ConfirmEnvelopeProps): ReactElement {
  const { t } = useI18n();
  const [text, setText] = useState('');

  const requireText = envelope.requireTextConfirm;
  const enabled = requireText == null || text === requireText;

  const commitLabel = t('aurabot.shell.confirm.commit', undefined, 'Confirm');
  const cancelLabel = t('aurabot.shell.confirm.cancel', undefined, 'Cancel');
  const promptDefault =
    requireText != null
      ? t(
          'aurabot.shell.confirm.requireText',
          { value: requireText },
          `Type ${requireText} to confirm`,
        )
      : '';
  const prompt = envelope.prompt ?? promptDefault;

  return (
    <div
      data-aurabot-envelope="confirm"
      className="rounded-md border border-rose-300 bg-rose-50 p-3 text-sm text-rose-900 dark:border-rose-700/60 dark:bg-rose-950/30 dark:text-rose-200"
    >
      {prompt && <div className="mb-2 text-xs">{prompt}</div>}
      {requireText != null && (
        <input
          type="text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          aria-label={prompt || requireText}
          data-aurabot-confirm-input
          className="mb-2 w-full rounded border border-rose-300 bg-white px-2 py-1 font-mono text-xs text-rose-900 outline-none focus:border-rose-500 dark:border-rose-700 dark:bg-black/40 dark:text-rose-100"
        />
      )}
      <div className="flex justify-end gap-2">
        <button
          type="button"
          onClick={() => onCancel?.(envelope.previewToken)}
          className="rounded border border-rose-300 bg-white px-2 py-1 text-xs font-medium text-rose-900 hover:bg-rose-50 dark:border-rose-700 dark:bg-transparent dark:text-rose-100"
        >
          {cancelLabel}
        </button>
        <button
          type="button"
          disabled={!enabled}
          onClick={() => enabled && onConfirm?.(envelope.previewToken)}
          data-aurabot-confirm-commit
          className="rounded bg-rose-600 px-2 py-1 text-xs font-medium text-white hover:bg-rose-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {commitLabel}
        </button>
      </div>
    </div>
  );
}
