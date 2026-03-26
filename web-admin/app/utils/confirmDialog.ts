/**
 * Imperative API for ConfirmDialog
 *
 * Allows non-React code (e.g. ActionRegistry.ts) to show confirmation dialogs
 * without access to React Context.
 *
 * The ConfirmDialogProvider registers itself on mount and unregisters on unmount.
 */

export interface ConfirmOptions {
  title?: string;
  content: string;
  confirmText?: string;
  cancelText?: string;
  variant?: 'default' | 'danger';
}

type ConfirmFn = (opts: ConfirmOptions) => Promise<boolean>;

let globalConfirm: ConfirmFn | null = null;

export function registerConfirmDialog(fn: ConfirmFn): void {
  globalConfirm = fn;
}

export function unregisterConfirmDialog(): void {
  globalConfirm = null;
}

/**
 * Show a confirmation dialog and return the user's choice.
 *
 * When called within a mounted ConfirmDialogProvider, uses the custom dialog.
 * Falls back to native window.confirm when the provider is not available.
 */
export async function confirmDialog(opts: ConfirmOptions): Promise<boolean> {
  if (globalConfirm) return globalConfirm(opts);
  // Fallback for SSR or outside React tree
  return window.confirm(opts.content);
}
