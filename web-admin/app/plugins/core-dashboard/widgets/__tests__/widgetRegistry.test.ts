import { describe, expect, it } from 'vitest';
import { widgetRegistry, widgetDefinitions } from '../widgetRegistry';

// BACKLOG-WIDGET-001 (P2 design): widgetRegistry self-registers in its
// constructor at module import time. The G1 incident (memory:
// feedback_g1_init_registry_bootstrap) showed that lazy / partial
// registration silently breaks schema-driven panels — they render `null`
// without throwing. This test pins three invariants so a future refactor
// that drops eager registration fails loudly instead of silently:
//
//   1. Importing the module is enough — no separate init() call required.
//   2. Every entry in the canonical `widgetDefinitions` array is reachable
//      via `widgetRegistry.get(type)`.
//   3. A representative cross-section of widget types resolves so that
//      regressions which prune one category (e.g. a typo in the chart
//      block) are caught before they reach a dashboard.
describe('widgetRegistry bootstrap', () => {
  it('exposes one registered definition per widgetDefinitions entry', () => {
    expect(widgetDefinitions.length).toBeGreaterThan(0);
    expect(widgetRegistry.getAll().length).toBe(widgetDefinitions.length);
  });

  it('resolves every widget type declared in widgetDefinitions', () => {
    for (const def of widgetDefinitions) {
      const resolved = widgetRegistry.get(def.type);
      expect(resolved, `widget type "${def.type}" must resolve via registry.get()`).toBeDefined();
      expect(resolved!.type).toBe(def.type);
    }
  });

  it('registers a representative cross-section of widget types', () => {
    // Pick one canonical type per category we expect to ship in OSS.
    // If a future refactor accidentally prunes a category, this fails fast.
    const canonical = [
      'smart-number-card',  // metric
      'smart-bar-chart',    // chart
      'smart-line-chart',   // chart
      'smart-pie-chart',    // chart
      'smart-table-chart',  // chart-table
      'smart-rich-text',    // text
      'smart-iframe',       // embed
      'smart-shortcuts',    // navigation
    ];
    for (const t of canonical) {
      expect(widgetRegistry.get(t), `"${t}" must be registered`).toBeDefined();
    }
  });
});
