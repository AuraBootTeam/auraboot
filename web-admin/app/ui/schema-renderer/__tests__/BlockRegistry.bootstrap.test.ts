import { describe, it, expect } from 'vitest';
import { BlockRegistry, initBlockRegistry } from '../BlockRegistry';

describe('BlockRegistry bootstrap', () => {
  it('initBlockRegistry registers all 10 runtime block types', () => {
    initBlockRegistry();

    expect(BlockRegistry.size()).toBe(10);

    const expected = [
      'table',
      'filters',
      'toolbar',
      'form-section',
      'chart',
      'tabs',
      'sub-table',
      'stat-card',
      'rich-text',
      'divider',
    ];
    for (const type of expected) {
      const spec = BlockRegistry.get(type);
      expect(spec, `blockType "${type}" must be registered`).toBeDefined();
      expect(spec!.component).toBeDefined();
    }
  });

  it('table spec carries normalizeData and shapes paginated payload', () => {
    initBlockRegistry();
    const spec = BlockRegistry.get('table');
    expect(spec?.normalizeData).toBeTypeOf('function');

    const out = spec!.normalizeData!(
      { records: [{ id: 1 }], total: 42, current: 2, pageSize: 20 },
      {},
    );
    expect(out).toEqual({
      records: [{ id: 1 }],
      total: 42,
      current: 2,
      pageSize: 20,
    });
  });

  it('table normalizeData passes through non-object payloads', () => {
    initBlockRegistry();
    const spec = BlockRegistry.get('table');
    expect(spec!.normalizeData!(null, {})).toBeNull();
  });

  it('initBlockRegistry is idempotent', () => {
    initBlockRegistry();
    const sizeAfterFirst = BlockRegistry.size();
    initBlockRegistry();
    expect(BlockRegistry.size()).toBe(sizeAfterFirst);
  });
});
