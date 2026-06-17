/**
 * Single source of control "chrome" shared by app/ui/ui controls and the smart
 * form controls that import it. Values reference the UX Design System tokens
 * (see app/framework/meta/runtime/theme/tokens.ts → app/styles/tokens.theme.css).
 *
 * Light-mode classes use semantic tokens; the `dark:` classes are kept verbatim
 * until T3 introduces dark-mode token values. Export names/keys are stable so
 * downstream consumers don't break.
 */

export const fieldSizeStyles = {
  small: 'px-2 py-1 text-body',
  medium: 'px-3 py-1.5 text-body',
  large: 'px-4 py-2 text-section',
};

export const fieldInputHeightStyles = {
  small: 'h-[var(--ds-control-sm)]', // 28
  medium: 'h-[var(--ds-control-field)]', // 34 — form-field default
  large: 'h-[var(--ds-control-lg)]', // 40
};

export const fieldVariantStyles = {
  default:
    'border-border-strong bg-panel text-text dark:border-gray-600 dark:bg-gray-700 dark:text-white',
  outline: 'border-border-strong bg-transparent text-text dark:border-gray-600 dark:text-white',
  filled: 'border-border bg-subtle text-text dark:border-gray-600 dark:bg-gray-800 dark:text-white',
  error: 'border-status-red text-text dark:border-red-600',
};

// Unified focus ring = 0 0 0 3px accent-weak (standard §2), via the shadow-focus
// utility. The ring alone conveys focus — no border-color swap.
export const fieldFocusStyles = 'focus-visible:outline-none focus-visible:shadow-focus';

export const fieldErrorFocusStyles = 'focus-visible:shadow-focus';

export const fieldControlBase =
  'w-full rounded-control border shadow-card transition-colors duration-200';
export const fieldContainerBase = 'relative w-full';
export const fieldBaseStyles = fieldControlBase;
