import { useState, type ReactElement } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { CodeEnvelope as CodeEnvelopeData } from '../../types/envelope';

export function CodeEnvelope({ envelope }: { envelope: CodeEnvelopeData }): ReactElement {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const copyLabel = t('aurabot.shell.code.copy', undefined, 'Copy');

  const handleCopy = async () => {
    if (typeof navigator === 'undefined' || !navigator.clipboard) return;
    try {
      await navigator.clipboard.writeText(envelope.code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    } catch {
      // Clipboard unavailable / denied — silent.
    }
  };

  return (
    <div
      data-aurabot-envelope="code"
      data-aurabot-code-language={envelope.language}
      className="overflow-hidden rounded-md border border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-900/40"
    >
      <div className="flex items-center justify-between border-b border-gray-200 bg-gray-100 px-2 py-1 text-xs text-gray-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400">
        <span className="font-mono">{envelope.language}</span>
        <button
          type="button"
          onClick={handleCopy}
          className="rounded px-1.5 py-0.5 hover:bg-gray-200 dark:hover:bg-gray-700"
        >
          {copied ? '✓' : copyLabel}
        </button>
      </div>
      <pre className="overflow-auto p-2 font-mono text-xs leading-relaxed text-gray-900 dark:text-gray-100">
        <code>{envelope.code}</code>
      </pre>
    </div>
  );
}
