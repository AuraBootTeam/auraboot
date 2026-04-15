import { describe, it, expect, beforeEach, vi } from 'vitest';

describe('getCurrentEditionTier', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  it("returns 'oss' when VITE_EDITION is unset", async () => {
    vi.stubEnv('VITE_EDITION', '');
    const { getCurrentEditionTier } = await import('../edition');
    expect(getCurrentEditionTier()).toBe('oss');
  });

  it("returns 'oss' when VITE_EDITION='oss'", async () => {
    vi.stubEnv('VITE_EDITION', 'oss');
    const { getCurrentEditionTier } = await import('../edition');
    expect(getCurrentEditionTier()).toBe('oss');
  });

  it("returns 'all' when VITE_EDITION='enterprise'", async () => {
    vi.stubEnv('VITE_EDITION', 'enterprise');
    const { getCurrentEditionTier } = await import('../edition');
    expect(getCurrentEditionTier()).toBe('all');
  });
});

describe('edition + getWidgetDefinitions integration', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  it('OSS edition hides enterprise widgets', async () => {
    vi.stubEnv('VITE_EDITION', 'oss');
    const { getCurrentEditionTier } = await import('../edition');
    const { getWidgetDefinitions } = await import('../getWidgetDefinitions');
    const defs = getWidgetDefinitions({ tier: getCurrentEditionTier() });
    expect(defs.find((d) => d.type === 'smart-bar-chart')).toBeDefined();
    expect(defs.find((d) => d.type === 'smart-wordcloud-chart')).toBeUndefined();
    expect(defs.every((d) => d.tier === 'oss')).toBe(true);
  });

  it('Enterprise edition exposes all widgets', async () => {
    vi.stubEnv('VITE_EDITION', 'enterprise');
    const { getCurrentEditionTier } = await import('../edition');
    const { getWidgetDefinitions } = await import('../getWidgetDefinitions');
    const defs = getWidgetDefinitions({ tier: getCurrentEditionTier() });
    expect(defs.find((d) => d.type === 'smart-bar-chart')).toBeDefined();
    expect(defs.find((d) => d.type === 'smart-wordcloud-chart')).toBeDefined();
  });
});
