/**
 * Global window augmentations.
 *
 * `__AURA_E2E_MODE__` is set by Playwright via `page.addInitScript` so the
 * SmartKanban (and any future component that needs to swap sensors / disable
 * animations / enable test hooks) can opt into deterministic behaviour. It is
 * never set in production runtime.
 */
declare global {
  interface Window {
    __AURA_E2E_MODE__?: boolean;
  }
}

export {};
