import { CsWidget, type WidgetStrings } from './ui';
import { detectHostLanguage, resolveLocaleStrings } from './locales';
import type { CsIdentity } from './client';

/**
 * The embeddable entry point. One <script> tag on the customer's site is the whole integration:
 *
 *   <script src="https://<host>/api/public/cs/widget.js" data-site-key="csk_..." async></script>
 *
 * The widget finds its own configuration from that tag, so there is nothing else for the site
 * owner to wire up. The API base is derived from the script's own src rather than hard-coded,
 * because the bundle is served by the very backend it needs to call.
 *
 * To identify a signed-in user, do NOT rely on auto-init — call AuraCS.init yourself with an
 * identity whose userHash was computed on your server:
 *
 *   AuraCS.init({ siteKey: 'csk_...', identity: { externalUserId, userHash } })
 *
 * The chrome (button labels) follows the host page's language automatically — `<html lang="zh">`
 * is enough. Override with data-lang on the tag, or pass `strings` to init() for full control.
 */

export interface InitOptions {
  siteKey?: string;
  apiBase?: string;
  identity?: CsIdentity;
  /** BCP-47 tag for the chrome, e.g. 'zh-CN'. Defaults to the host page's declared language. */
  lang?: string;
  /** Per-string overrides. Wins over the resolved locale; anything omitted falls through. */
  strings?: Partial<WidgetStrings>;
}

let widget: CsWidget | null = null;

export function init(options: InitOptions = {}): CsWidget | null {
  const script = currentScript();
  const siteKey = options.siteKey ?? script?.getAttribute('data-site-key') ?? '';
  const apiBase = options.apiBase ?? script?.getAttribute('data-api-base') ?? originOf(script);

  if (!siteKey) {
    console.warn('[AuraCS] no site key: add data-site-key to the script tag, or pass siteKey to init()');
    return null;
  }
  if (widget) return widget;

  // Precedence: explicit strings > explicit lang > data-lang > host page language > English.
  const lang = options.lang ?? script?.getAttribute('data-lang') ?? detectHostLanguage();
  const strings: Partial<WidgetStrings> = { ...resolveLocaleStrings(lang), ...options.strings };

  widget = new CsWidget({ apiBase, siteKey, identity: options.identity, strings });
  return widget;
}

function currentScript(): HTMLScriptElement | null {
  // Duck-typed rather than `instanceof HTMLScriptElement`: the constructor does not exist outside
  // a real DOM, and referencing it throws a ReferenceError instead of simply being false.
  const current = document.currentScript as HTMLScriptElement | null;
  if (current && typeof current.getAttribute === 'function') return current;
  // async/defer scripts have lost document.currentScript by the time this runs.
  return document.querySelector<HTMLScriptElement>('script[data-site-key]');
}

function originOf(script: HTMLScriptElement | null): string {
  if (script?.src) {
    try {
      return new URL(script.src).origin;
    } catch {
      // fall through to the page's own origin
    }
  }
  return window.location.origin;
}

// Auto-init for the anonymous case, which is the copy-paste snippet the embed centre hands out.
// A site that identifies its users calls init() explicitly instead, with a signed identity.
if (typeof document !== 'undefined' && currentScript()?.hasAttribute('data-site-key')) {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => init());
  } else {
    init();
  }
}
