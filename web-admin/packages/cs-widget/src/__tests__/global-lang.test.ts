import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { LOCALE_STRINGS } from '../locales';

/**
 * The WIRING, not the phrasebook.
 *
 * locales.test.ts proves the zh pack is complete, but that pack was useless for two releases for a
 * different reason: `WidgetStrings` and the `strings` option already existed and simply nobody
 * passed them, so every copy-paste embed rendered an English frame. A test that only exercises
 * resolveLocaleStrings() would stay green through exactly that regression. These assert that init()
 * actually hands the resolved strings to the widget.
 */
const constructed: any[] = [];
vi.mock('../ui', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../ui')>();
  return {
    ...actual,
    CsWidget: class {
      constructor(options: any) {
        constructed.push(options);
      }
    },
  };
});

async function freshInit() {
  vi.resetModules();
  constructed.length = 0;
  return (await import('../global')).init;
}

function scriptTag(attrs: Record<string, string>) {
  document.body.innerHTML = '';
  const el = document.createElement('script');
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  document.body.appendChild(el);
  return el;
}

describe('init() — chrome language wiring', () => {
  beforeEach(() => {
    document.documentElement.removeAttribute('lang');
  });
  afterEach(() => {
    document.body.innerHTML = '';
    document.documentElement.removeAttribute('lang');
  });

  it('a Chinese page gets the Chinese frame from the plain copy-paste snippet', async () => {
    document.documentElement.setAttribute('lang', 'zh-CN');
    scriptTag({ 'data-site-key': 'csk_test' });
    const init = await freshInit();

    init();

    expect(constructed).toHaveLength(1);
    expect(constructed[0].strings.send).toBe(LOCALE_STRINGS.zh.send);
    expect(constructed[0].strings.handoff).toBe(LOCALE_STRINGS.zh.handoff);
  });

  it('data-lang overrides the page language', async () => {
    document.documentElement.setAttribute('lang', 'en');
    scriptTag({ 'data-site-key': 'csk_test', 'data-lang': 'zh' });
    const init = await freshInit();

    init();

    expect(constructed[0].strings.title).toBe(LOCALE_STRINGS.zh.title);
  });

  it('explicit strings win over the resolved locale', async () => {
    document.documentElement.setAttribute('lang', 'zh-CN');
    // Deliberately WITHOUT data-site-key: a tag carrying it auto-inits on import, and init() is a
    // singleton (`if (widget) return widget`), so overrides passed afterwards would be silently
    // dropped. That is the documented "call AuraCS.init yourself" path, and this test would
    // otherwise assert against the auto-init widget instead of the one it configures.
    scriptTag({});
    const init = await freshInit();

    init({ siteKey: 'csk_test', strings: { send: '寄出' } });

    expect(constructed[0].strings.send).toBe('寄出');
    // …while everything it did not override still comes from the zh pack.
    expect(constructed[0].strings.handoff).toBe(LOCALE_STRINGS.zh.handoff);
  });

  it('an undeclared page passes no overrides, so the widget keeps its English defaults', async () => {
    // Upgrade safety: existing customers must not have their frame silently switched.
    vi.spyOn(navigator, 'language', 'get').mockReturnValue('en-US');
    scriptTag({ 'data-site-key': 'csk_test' });
    const init = await freshInit();

    init();

    // en resolves to the shipped defaults, so nothing is being forced to another language.
    expect(constructed[0].strings.send).toBe('Send');
    vi.restoreAllMocks();
  });
});
