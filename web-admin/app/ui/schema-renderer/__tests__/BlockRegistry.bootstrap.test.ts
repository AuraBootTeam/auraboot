import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';
import { BlockRegistry, initBlockRegistry } from '../BlockRegistry';

describe('BlockRegistry bootstrap', () => {
  it('initBlockRegistry registers the exact runtime block set for this source baseline', () => {
    initBlockRegistry();


    // Must mirror the dispatch table in
    // web-admin/app/framework/meta/rendering/BlockRenderer.tsx — every
    // blockType the meta dispatcher used to load via _fallbackRenderers must
    // resolve through the registry. `monthly-grid` is intentionally omitted
    // (handled by enclosing detail page renderer); `custom` is handled inline
    // by BlockRenderer via ComponentLoader.
    const expected = [
      'table',
      'filters',
      'toolbar',
      'form',
      'form-section',
      'detail-section',
      'form-buttons',
      'form-wizard',
      'ai-fill-banner',
      'description',
      'text',
      'chart',
      'chart-card',
      'tabs',
      'sub-table',
      'embedded-list',
      'stat-card',
      'selection-info',
      'metric-strip',
      'stage-rail',
      'dag-canvas',
      'record-inspector',
      'candidate-list',
      'workbench-action-bar',
      'evidence-panel',
      'gerber-viewer',
      'artifact-timeline',
      'review-drawer',
      'status-banner',
      'rich-text',
      'divider',
      'trace-graph',
      'card-grid',
      'code-snippet',
      'conversation-panel',
    ];
    // Optional renderer assets must agree with registration across supported source baselines.
    expect(existsSync(resolve(process.cwd(), 'app/ui/schema-renderer/BlockRegistry.ts'))).toBe(true);
    if (existsSync(resolve(process.cwd(), 'app/framework/meta/rendering/blocks/PrerequisiteTableBlockRenderer.tsx'))) {
      expected.push('prerequisite-table');
    }
    expect(BlockRegistry.list().map(([type]) => type).sort()).toEqual([...expected].sort());
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
