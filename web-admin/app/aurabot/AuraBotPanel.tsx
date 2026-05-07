/**
 * AuraBot V3 Shell Panel — header + message list + input area.
 *
 * Wraps the body in <ClientOnly> because the message list relies on
 * scrollIntoView, the input on textarea focus, and the hotkey hook on
 * `document.addEventListener` — all of which would mismatch SSR output.
 */

import { useCallback, useRef, type ReactElement } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { ClientOnly } from '~/utils/ClientOnly';
import { cn } from '~/utils/cn';
import { useAuraBotShell } from './AuraBotProvider';
import { useAuraBotPanel } from './hooks/useAuraBotPanel';
import { useAuraBotHotkey } from './hooks/useAuraBotHotkey';
import { useSkillCall } from './hooks/useSkillCall';
import { MessageList } from './components/MessageList';
import { InputArea, type InputAreaHandle } from './components/InputArea';
import type { PanelState } from './types/panel';

const PANEL_LAYOUT: Record<PanelState, string> = {
  hidden: 'hidden',
  expanded:
    'fixed right-4 bottom-4 top-4 z-40 flex w-[420px] flex-col rounded-lg border border-gray-200 bg-white shadow-2xl dark:border-gray-700 dark:bg-gray-900',
  pinned:
    'fixed right-0 top-0 bottom-0 z-40 flex w-[420px] flex-col border-l border-gray-200 bg-white shadow-2xl dark:border-gray-700 dark:bg-gray-900',
  fullscreen:
    'fixed inset-0 z-50 flex flex-col bg-white dark:bg-gray-900',
};

function PanelHeader(): ReactElement {
  const { t } = useI18n();
  const { panelState, close, pin, unpin, fullscreen, minimize } = useAuraBotPanel();

  const title = t('aurabot.shell.panel.title', undefined, 'AuraBot Assistant');
  const closeLabel = t('aurabot.shell.panel.close', undefined, 'Close');
  const pinLabel = t('aurabot.shell.panel.pin', undefined, 'Pin to side');
  const unpinLabel = t('aurabot.shell.panel.unpin', undefined, 'Unpin');
  const fullscreenLabel = t('aurabot.shell.panel.fullscreen', undefined, 'Full screen');
  const minimizeLabel = t('aurabot.shell.panel.minimize', undefined, 'Minimize');

  return (
    <div className="flex items-center justify-between gap-2 border-b border-gray-200 px-3 py-2 dark:border-gray-700">
      <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">
        {title}
      </span>
      <div className="flex items-center gap-1 text-xs text-gray-600 dark:text-gray-300">
        {panelState === 'pinned' ? (
          <button
            type="button"
            onClick={unpin}
            aria-label={unpinLabel}
            className="rounded px-1.5 py-0.5 hover:bg-gray-100 dark:hover:bg-gray-800"
          >
            {unpinLabel}
          </button>
        ) : (
          <button
            type="button"
            onClick={pin}
            aria-label={pinLabel}
            data-aurabot-pin
            className="rounded px-1.5 py-0.5 hover:bg-gray-100 dark:hover:bg-gray-800"
          >
            {pinLabel}
          </button>
        )}
        {panelState === 'fullscreen' ? (
          <button
            type="button"
            onClick={minimize}
            aria-label={minimizeLabel}
            className="rounded px-1.5 py-0.5 hover:bg-gray-100 dark:hover:bg-gray-800"
          >
            {minimizeLabel}
          </button>
        ) : (
          <button
            type="button"
            onClick={fullscreen}
            aria-label={fullscreenLabel}
            className="rounded px-1.5 py-0.5 hover:bg-gray-100 dark:hover:bg-gray-800"
          >
            {fullscreenLabel}
          </button>
        )}
        <button
          type="button"
          onClick={close}
          aria-label={closeLabel}
          data-aurabot-close
          className="rounded px-1.5 py-0.5 hover:bg-gray-100 dark:hover:bg-gray-800"
        >
          ✕
        </button>
      </div>
    </div>
  );
}

function EmptyState(): ReactElement {
  const { t } = useI18n();
  const hint = t(
    'aurabot.shell.empty.hint',
    undefined,
    'Try typing "echo hello" to test the round-trip',
  );
  const example = t('aurabot.shell.empty.example', undefined, 'echo hello');

  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 text-center">
      <p className="text-sm text-gray-600 dark:text-gray-300">{hint}</p>
      <span
        className="rounded-full border border-gray-200 bg-gray-50 px-3 py-1 font-mono text-xs text-gray-700 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200"
        data-aurabot-example
      >
        {example}
      </span>
    </div>
  );
}

function PanelBody(): ReactElement {
  const { messages } = useAuraBotShell();
  const { send, busy } = useSkillCall({ skillName: 'echo' });
  const inputRef = useRef<InputAreaHandle | null>(null);

  const onFocusInput = useCallback(() => inputRef.current?.focus(), []);
  useAuraBotHotkey({ onFocusInput });

  return (
    <>
      <PanelHeader />
      {messages.length === 0 ? (
        <EmptyState />
      ) : (
        <MessageList messages={messages} />
      )}
      <InputArea ref={inputRef} onSubmit={send} disabled={busy} />
    </>
  );
}

export function AuraBotShellPanel(): ReactElement | null {
  const { panelState } = useAuraBotShell();
  if (panelState === 'hidden') return null;

  return (
    <aside
      className={cn(PANEL_LAYOUT[panelState])}
      role="complementary"
      data-aurabot-panel-state={panelState}
    >
      <ClientOnly>
        <PanelBody />
      </ClientOnly>
    </aside>
  );
}
