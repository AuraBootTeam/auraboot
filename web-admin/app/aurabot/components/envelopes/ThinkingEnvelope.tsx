import { useState, type ReactElement } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { ThinkingEnvelope as ThinkingEnvelopeData } from '../../types/envelope';

export function ThinkingEnvelope({
  envelope,
}: {
  envelope: ThinkingEnvelopeData;
}): ReactElement {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const label = t('aurabot.shell.thinking.label', undefined, 'Thinking…');

  return (
    <div
      data-aurabot-envelope="thinking"
      className="rounded-md border border-dashed border-gray-300 bg-gray-50 p-2 text-xs text-gray-600 dark:border-gray-700 dark:bg-gray-800/40 dark:text-gray-400"
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-2 font-medium"
        aria-expanded={open}
      >
        <span>
          {label}
          {typeof envelope.tokens === 'number' ? ` · ${envelope.tokens}` : ''}
        </span>
        <span aria-hidden>{open ? '▾' : '▸'}</span>
      </button>
      {open && (
        <pre className="mt-2 whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed">
          {envelope.text}
        </pre>
      )}
    </div>
  );
}
