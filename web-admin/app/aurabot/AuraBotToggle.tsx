import type { ReactElement } from 'react';
/**
 * AuraBotToggle — fixed floating button at the bottom-right corner.
 *
 * SSR-safe: pure markup. Hidden when the panel is open so the panel header
 * close/pin buttons take over.
 */

import { useI18n } from '~/contexts/I18nContext';
import { useAuraBotShell } from './AuraBotProvider';

export function AuraBotShellToggle(): ReactElement | null {
  const { t } = useI18n();
  const { panelState, setPanelState } = useAuraBotShell();

  if (panelState !== 'hidden') return null;

  const aria = t('aurabot.shell.toggle.aria', undefined, 'Open AuraBot assistant');

  return (
    <button
      type="button"
      onClick={() => setPanelState('expanded')}
      aria-label={aria}
      data-aurabot-toggle
      className="fixed bottom-5 right-5 z-40 flex h-12 w-12 items-center justify-center rounded-full bg-blue-600 text-white shadow-lg transition-transform hover:scale-105 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-300"
    >
      <span aria-hidden className="text-lg font-semibold">A</span>
    </button>
  );
}
