import { DEFAULT_STRINGS, type WidgetStrings } from './ui';

/**
 * Built-in chrome translations.
 *
 * Why the widget ships these at all: the integration is a single <script> tag, so there is no
 * build step, no bundler and no translation file on the customer's side. If the chrome could only
 * be localized by passing `strings` to `init()`, then every customer using the copy-paste snippet —
 * which is all of them — would keep the English frame no matter what language their site is in.
 *
 * Selection is SIGNAL-DRIVEN, never a blanket default flip: a site only gets a non-English frame
 * when it actually says it speaks that language (`<html lang>`, `data-lang`, or the visitor's
 * browser). A site that declares nothing keeps English exactly as before, which is the upgrade
 * safety the WidgetStrings contract asks for.
 */
export const LOCALE_STRINGS: Record<string, WidgetStrings> = {
  en: DEFAULT_STRINGS,
  zh: {
    title: '在线客服',
    send: '发送',
    placeholder: '输入您的问题…',
    handoff: '转人工',
    handoffQueued: '正在为您转接人工客服…',
    handoffQueuedNoSeats: '您已进入排队,有空闲客服会尽快回复您。',
    handoffTaken: '人工客服已加入对话。',
    closed: '本次会话已结束。',
  },
};

/**
 * Reduce a BCP-47 tag to a pack we ship. `zh-CN`, `zh-Hans`, `ZH` and `zh` all resolve to `zh`.
 * Returns undefined for anything unknown so the caller keeps the English default rather than
 * falling back to a half-translated frame.
 */
export function resolveLocaleStrings(tag: string | null | undefined): WidgetStrings | undefined {
  if (!tag) return undefined;
  const primary = tag.trim().toLowerCase().split(/[-_]/)[0];
  if (!primary) return undefined;
  return LOCALE_STRINGS[primary];
}

/**
 * What language the host page claims to be in. `<html lang>` is the site's own declaration and
 * wins over the browser, which describes the visitor rather than the site: a Chinese shop should
 * not render an English frame just because a visitor's browser is set to English.
 */
export function detectHostLanguage(): string | undefined {
  if (typeof document === 'undefined') return undefined;
  const declared = document.documentElement?.getAttribute('lang');
  if (declared) return declared;
  if (typeof navigator !== 'undefined' && navigator.language) return navigator.language;
  return undefined;
}
