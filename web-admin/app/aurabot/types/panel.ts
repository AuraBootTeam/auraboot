/** Panel state machine per Spec §3. */

export type PanelState = 'hidden' | 'expanded' | 'pinned' | 'fullscreen';

export const PANEL_STATE_STORAGE_KEY = 'aurabot.shell.panelState';

export const PANEL_STATES: readonly PanelState[] = [
  'hidden',
  'expanded',
  'pinned',
  'fullscreen',
] as const;

export function isPanelState(value: unknown): value is PanelState {
  return typeof value === 'string' && (PANEL_STATES as readonly string[]).includes(value);
}
