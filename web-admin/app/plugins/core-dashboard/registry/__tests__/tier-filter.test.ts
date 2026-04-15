import { describe, it, expect } from 'vitest';
import { getWidgetDefinitions } from '../getWidgetDefinitions';

describe('getWidgetDefinitions', () => {
  it('returns only OSS widgets when tier=oss', () => {
    const defs = getWidgetDefinitions({ tier: 'oss' });
    expect(defs.length).toBeGreaterThan(0);
    expect(defs.every((d) => d.tier === 'oss')).toBe(true);
    expect(defs.find((d) => d.type === 'smart-bar-chart')).toBeDefined();
    expect(defs.find((d) => d.type === 'smart-wordcloud-chart')).toBeUndefined();
  });

  it('returns enterprise widgets when tier=enterprise', () => {
    const defs = getWidgetDefinitions({ tier: 'enterprise' });
    expect(defs.every((d) => d.tier === 'enterprise')).toBe(true);
    expect(defs.find((d) => d.type === 'smart-bar-chart')).toBeUndefined();
  });

  it('returns all widgets when tier=all', () => {
    const defs = getWidgetDefinitions({ tier: 'all' });
    expect(defs.find((d) => d.type === 'smart-bar-chart')).toBeDefined();
    // At least one enterprise widget should exist too
    expect(defs.some((d) => d.tier === 'enterprise')).toBe(true);
  });
});
