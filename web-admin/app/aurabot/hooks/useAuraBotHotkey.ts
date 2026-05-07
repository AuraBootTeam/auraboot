/**
 * Document-level hotkey listener for the AuraBot V3 shell (Spec §4).
 *
 *   Cmd/Ctrl + K           → toggle hidden ↔ expanded
 *   Cmd/Ctrl + Shift + K   → open + focus input
 *   Escape                 → minimize unless fullscreen
 *
 * Bails when the active element is inside `[data-no-aurabot-hotkey]` so that
 * Monaco / CodeMirror / form inputs that need Cmd+K stay unimpeded.
 *
 * Listener attaches in useEffect → SSR-safe; no top-level `window` access.
 */
import { useEffect } from 'react';
import { useAuraBotShell } from '../AuraBotProvider';

export interface HotkeyOptions {
  /** Called on Cmd/Ctrl+Shift+K so the panel can focus its input. */
  onFocusInput?: () => void;
}

function isInsideOptOutSubtree(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  return target.closest('[data-no-aurabot-hotkey]') != null;
}

export function useAuraBotHotkey(options: HotkeyOptions = {}): void {
  const { panelState, setPanelState } = useAuraBotShell();
  const { onFocusInput } = options;

  useEffect(() => {
    if (typeof document === 'undefined') return undefined;

    const handler = (event: KeyboardEvent) => {
      const meta = event.metaKey || event.ctrlKey;
      const key = event.key.toLowerCase();

      // Escape: minimize except in fullscreen.
      if (event.key === 'Escape') {
        if (panelState !== 'hidden' && panelState !== 'fullscreen') {
          setPanelState('hidden');
        }
        return;
      }

      if (!meta) return;

      // Opt-out subtree (Monaco, CodeMirror, etc.) keeps its native binding.
      if (isInsideOptOutSubtree(document.activeElement)) return;

      if (key === 'k') {
        if (event.shiftKey) {
          event.preventDefault();
          setPanelState('expanded');
          // Let React commit the open state, then yield focus to the input.
          if (onFocusInput) {
            queueMicrotask(onFocusInput);
          }
          return;
        }
        event.preventDefault();
        setPanelState(panelState === 'hidden' ? 'expanded' : 'hidden');
      }
    };

    document.addEventListener('keydown', handler);
    return () => {
      document.removeEventListener('keydown', handler);
    };
  }, [panelState, setPanelState, onFocusInput]);
}
