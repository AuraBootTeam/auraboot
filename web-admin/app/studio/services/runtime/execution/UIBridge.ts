/**
 * UIBridge - Imperative bridge for triggering UI actions from non-React code
 *
 * Problem: ActionExecutor classes are plain TypeScript, not React components,
 * so they cannot use React hooks (useToast, etc.) to trigger UI side-effects.
 *
 * Solution: Use CustomEvent to dispatch UI intents that React providers listen to.
 * This is the same pattern used by ErrorBoundary and GlobalShortcutManager in
 * this codebase.
 *
 * Event protocol:
 * - `aura:toast`    — { message, variant, duration }
 * - `aura:modal`    — { action: 'show'|'hide', modalId, title, content, size, ... }
 * - `aura:loading`  — { visible: boolean }
 */

// ---- Toast ----

export interface ToastEventDetail {
  message: string;
  variant: 'success' | 'error' | 'warning' | 'info';
  duration?: number;
}

/**
 * Show a toast notification from non-React code.
 * Requires ToastProvider to have registered the `aura:toast` listener.
 */
export function dispatchToast(detail: ToastEventDetail): void {
  window.dispatchEvent(new CustomEvent<ToastEventDetail>('aura:toast', { detail }));
}

// ---- Modal ----

export interface ModalShowEventDetail {
  action: 'show';
  modalId: string;
  title?: string;
  content?: string;
  size?: 'sm' | 'md' | 'lg' | 'xl' | 'full';
  closable?: boolean;
  maskClosable?: boolean;
  data?: unknown;
}

export interface ModalHideEventDetail {
  action: 'hide';
  modalId: string;
}

export type ModalEventDetail = ModalShowEventDetail | ModalHideEventDetail;

/**
 * Show or hide a modal from non-React code.
 * Requires a ModalProvider (or similar) to listen for `aura:modal`.
 */
export function dispatchModal(detail: ModalEventDetail): void {
  window.dispatchEvent(new CustomEvent<ModalEventDetail>('aura:modal', { detail }));
}

// ---- Loading ----

export interface LoadingEventDetail {
  visible: boolean;
}

/**
 * Show or hide a global loading overlay from non-React code.
 * Requires a LoadingProvider (or similar) to listen for `aura:loading`.
 */
export function dispatchLoading(detail: LoadingEventDetail): void {
  window.dispatchEvent(new CustomEvent<LoadingEventDetail>('aura:loading', { detail }));
}

// ---- Visibility Toggle ----

export interface VisibilityEventDetail {
  targetId: string;
  visible?: boolean; // undefined = toggle
}

/**
 * Toggle element visibility from non-React code.
 * Components can listen for `aura:visibility` events targeting their ID.
 */
export function dispatchVisibility(detail: VisibilityEventDetail): void {
  window.dispatchEvent(new CustomEvent<VisibilityEventDetail>('aura:visibility', { detail }));
}
