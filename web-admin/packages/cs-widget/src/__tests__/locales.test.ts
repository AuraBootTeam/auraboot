import { describe, expect, it } from 'vitest';
import { DEFAULT_STRINGS } from '../ui';
import { LOCALE_STRINGS, detectHostLanguage, resolveLocaleStrings } from '../locales';

/**
 * The chrome language rules. The one that matters most is the LAST describe block: a site that
 * declares no language must keep the English frame, because the widget ships inside a backend
 * upgrade and silently flipping an existing customer's UI to another language is worse than a
 * plain default.
 */
describe('resolveLocaleStrings', () => {
  it('resolves every reasonable spelling of Chinese to the zh pack', () => {
    for (const tag of ['zh', 'zh-CN', 'zh-Hans', 'ZH', 'zh_TW', '  zh-cn  ']) {
      expect(resolveLocaleStrings(tag), tag).toBe(LOCALE_STRINGS.zh);
    }
  });

  it('translates the whole frame, not a subset', () => {
    // A half-translated frame looks broken; every key the widget renders must be covered.
    for (const key of Object.keys(DEFAULT_STRINGS) as (keyof typeof DEFAULT_STRINGS)[]) {
      expect(LOCALE_STRINGS.zh[key], key).toBeTruthy();
      expect(LOCALE_STRINGS.zh[key], key).not.toBe(DEFAULT_STRINGS[key]);
    }
  });

  it('returns undefined for unknown or empty tags so English is kept', () => {
    for (const tag of ['de', 'fr-FR', 'xx', '', '   ', null, undefined]) {
      expect(resolveLocaleStrings(tag)).toBeUndefined();
    }
  });

  it('maps en back to the shipped defaults', () => {
    expect(resolveLocaleStrings('en-GB')).toBe(DEFAULT_STRINGS);
  });
});

describe('detectHostLanguage', () => {
  it("prefers the site's own <html lang> over the visitor's browser", () => {
    // The site describes the site; navigator describes the visitor. A Chinese shop should not
    // render an English frame just because this particular visitor's browser is English.
    document.documentElement.setAttribute('lang', 'zh-CN');
    expect(detectHostLanguage()).toBe('zh-CN');
    document.documentElement.removeAttribute('lang');
  });

  it('falls back to the browser language when the page declares nothing', () => {
    document.documentElement.removeAttribute('lang');
    expect(detectHostLanguage()).toBe(navigator.language);
  });
});

describe('upgrade safety', () => {
  it('a site that declares no language keeps the English frame', () => {
    document.documentElement.removeAttribute('lang');
    // Simulate a visitor whose browser carries no useful hint.
    const strings = resolveLocaleStrings(undefined);
    expect(strings).toBeUndefined();
    // …so the widget composes to exactly the pre-existing defaults.
    expect({ ...strings }).toEqual({});
  });
});
