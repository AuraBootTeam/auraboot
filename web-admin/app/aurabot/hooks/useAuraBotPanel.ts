/**
 * Convenience verbs over the panel state machine (Spec §3).
 *
 *   open         → expanded
 *   close        → hidden
 *   pin          → pinned
 *   unpin        → expanded
 *   fullscreen   → fullscreen
 *   minimize     → hidden (Esc default)
 */
import { useCallback } from 'react';
import { useAuraBotShell } from '../AuraBotProvider';
import type { PanelState } from '../types/panel';

export interface PanelControls {
  panelState: PanelState;
  open: () => void;
  close: () => void;
  pin: () => void;
  unpin: () => void;
  fullscreen: () => void;
  minimize: () => void;
  toggle: () => void;
}

export function useAuraBotPanel(): PanelControls {
  const { panelState, setPanelState } = useAuraBotShell();

  const open = useCallback(() => setPanelState('expanded'), [setPanelState]);
  const close = useCallback(() => setPanelState('hidden'), [setPanelState]);
  const pin = useCallback(() => setPanelState('pinned'), [setPanelState]);
  const unpin = useCallback(() => setPanelState('expanded'), [setPanelState]);
  const fullscreen = useCallback(() => setPanelState('fullscreen'), [setPanelState]);
  const minimize = useCallback(() => setPanelState('hidden'), [setPanelState]);

  const toggle = useCallback(() => {
    setPanelState(panelState === 'hidden' ? 'expanded' : 'hidden');
  }, [panelState, setPanelState]);

  return { panelState, open, close, pin, unpin, fullscreen, minimize, toggle };
}
