/**
 * BlockRegistry — runtime lookup table for blockType → renderer + data normalizer.
 *
 * Replaces the per-call switch chains in DataSourceManager / SchemaRenderer /
 * SmartViewRenderer (consumer migrations tracked separately; see
 * docs/plans/2026-04/2026-04-25-blockrenderer-runtime-registry-design.md).
 *
 * Components are lazy so registering all 10 entries does not pull every
 * block renderer into the main bundle.
 */

import React from 'react';
import { createRegistry } from './createRegistry';

export interface BlockSpec {
  component: React.ComponentType<any>;
  /**
   * Optional data shaper. Called with the raw API payload and the block
   * config. Returns the shape the component expects (e.g. `{ records, total,
   * current, pageSize }` for `table`). Blocks without custom shaping omit
   * this and consumers pass the raw payload through.
   */
  normalizeData?: (raw: unknown, block: unknown) => unknown;
}

export const BlockRegistry = createRegistry<BlockSpec>('BlockRegistry');

/** Pagination/list payload shaper used by `table`. */
function normalizeTableData(raw: unknown): unknown {
  if (raw && typeof raw === 'object') {
    const r = raw as Record<string, unknown>;
    return {
      records: (r.records as unknown[] | undefined) ?? (r.list as unknown[] | undefined) ?? [],
      total: (r.total as number | undefined) ?? 0,
      current: (r.current as number | undefined) ?? 1,
      pageSize: (r.pageSize as number | undefined) ?? 10,
    };
  }
  return raw;
}

let initialized = false;

export function initBlockRegistry(): void {
  if (initialized) return;
  initialized = true;

  const lazy = (loader: () => Promise<{ [k: string]: React.ComponentType<any> }>, exportName: string) =>
    React.lazy(async () => {
      const mod = await loader();
      return { default: mod[exportName] };
    });

  // Data / list blocks
  BlockRegistry.register('table', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/TableBlockRenderer'), 'TableBlockRenderer'),
    normalizeData: normalizeTableData,
  });
  BlockRegistry.register('filters', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/FiltersBlockRenderer'), 'FiltersBlockRenderer'),
  });
  BlockRegistry.register('toolbar', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/ToolbarBlockRenderer'), 'ToolbarBlockRenderer'),
  });

  // Form blocks
  BlockRegistry.register('form', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/FormBlockRenderer'), 'FormBlockRenderer'),
  });
  BlockRegistry.register('form-section', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/FormSectionBlockRenderer'), 'FormSectionBlockRenderer'),
  });
  BlockRegistry.register('form-buttons', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/FormButtonsBlockRenderer'), 'FormButtonsBlockRenderer'),
  });
  BlockRegistry.register('form-wizard', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/FormWizardBlockRenderer'), 'FormWizardBlockRenderer'),
  });
  // P1' vertical-slice ai-fill banner. To be replaced in P2' by a generic
  // schema-driven AI fill widget that derives endpoint + field schema from
  // the surrounding form's modelCode.
  BlockRegistry.register('ai-fill-banner', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/AiFillBannerBlockRenderer'), 'AiFillBannerBlockRenderer'),
  });

  // Detail / display blocks
  BlockRegistry.register('description', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/DescriptionBlockRenderer'), 'DescriptionBlockRenderer'),
  });
  BlockRegistry.register('chart', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/ChartBlockRenderer'), 'ChartBlockRenderer'),
  });
  BlockRegistry.register('tabs', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/TabsBlockRenderer'), 'TabsBlockRenderer'),
  });
  BlockRegistry.register('sub-table', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/SubTableViewer'), 'SubTableViewer'),
  });
  BlockRegistry.register('stat-card', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/StatCardBlockRenderer'), 'StatCardBlockRenderer'),
  });
  BlockRegistry.register('rich-text', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/RichTextBlockRenderer'), 'RichTextBlockRenderer'),
  });
  BlockRegistry.register('divider', {
    component: lazy(() => import('~/framework/meta/rendering/blocks/DividerBlockRenderer'), 'DividerBlockRenderer'),
  });
}
