import { describe, it, expect } from 'vitest';
import { ViewRegistry, initViewRegistry } from '../ViewRegistry';

describe('ViewRegistry bootstrap', () => {
  it('initViewRegistry registers all 8 view types', () => {
    initViewRegistry();

    expect(ViewRegistry.size()).toBe(8);

    const expected = ['table', 'kanban', 'calendar', 'gallery', 'gantt', 'tree', 'timeline', 'form'];
    for (const type of expected) {
      const spec = ViewRegistry.get(type);
      expect(spec, `viewType "${type}" must be registered`).toBeDefined();
      expect(spec!.component).toBeDefined();
    }
  });

  it('initViewRegistry is idempotent', () => {
    initViewRegistry();
    const sizeAfterFirst = ViewRegistry.size();
    initViewRegistry();
    expect(ViewRegistry.size()).toBe(sizeAfterFirst);
  });
});
