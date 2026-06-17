import type { DslBlockV3, PageSchemaV3Kind } from '../types';
import { isCustomBlockAllowedForKind } from './customBlockRegistry';

/**
 * Per-kind block policy.
 *
 * When a page has a concrete kind (form / list / detail / dashboard) the designer
 * collapses to that kind only: the palette exposes nothing but the blocks that
 * belong to it, and the page root holds a single kind container. The `composite`
 * kind keeps the full block set (internal escape hatch, not surfaced as a normal
 * authoring entry point).
 */
export interface KindPolicy {
  /** Single root container block type for this kind, or null for composite. */
  rootBlockType: string | null;
  /** Allowed block types for palette/insertion, or null to allow everything. */
  allowedBlockTypes: Set<string> | null;
}

const SHARED_FORM_BLOCKS = [
  'form-section',
  'field',
  'sub-table',
  'column',
  'repeater',
  'subform',
  'ai-fill-banner',
] as const;

const SHARED_DETAIL_BLOCKS = [
  'detail-section',
  'field',
  'sub-table',
  'column',
  'repeater',
  'subform',
  'widget',
  'ai-fill-banner',
] as const;

const SHARED_LAYOUT_BLOCKS = ['tabs', 'tab', 'columns'] as const;
const SHARED_ACTION_BLOCKS = ['action-bar', 'action'] as const;
const SHARED_WORKFLOW_BLOCKS = ['bpm-panel', 'activity-timeline', 'field-history'] as const;
// Workbench blocks (KPI metric strip + status banner + the batch-2 family:
// workbench action bar, review drawer, evidence panel, record inspector,
// candidate list, artifact timeline). Surfaced on detail and dashboard kinds —
// the two kinds that compose cockpit / workbench / reconciliation layouts.
const SHARED_WORKBENCH_BLOCKS = [
  'metric-strip',
  'status-banner',
  'workbench-action-bar',
  'review-drawer',
  'evidence-panel',
  'record-inspector',
  'candidate-list',
  'artifact-timeline',
] as const;

// Display / data blocks (non workbench-family). stat-card + description are
// generic display blocks usable on both detail and dashboard cockpits;
// record-comments + embedded-list are DETAIL-only (they resolve the surrounding
// record from the detail route, so they have no meaning on a dashboard).
const SHARED_DETAIL_DISPLAY_BLOCKS = [
  'stat-card',
  'description',
  'record-comments',
  'embedded-list',
] as const;
const SHARED_DASHBOARD_DISPLAY_BLOCKS = ['stat-card', 'description'] as const;

const POLICIES: Record<PageSchemaV3Kind, KindPolicy> = {
  form: {
    rootBlockType: 'form',
    allowedBlockTypes: new Set<string>([
      'form',
      ...SHARED_FORM_BLOCKS,
      ...SHARED_LAYOUT_BLOCKS,
      ...SHARED_ACTION_BLOCKS,
      ...SHARED_WORKFLOW_BLOCKS,
    ]),
  },
  list: {
    rootBlockType: 'list',
    allowedBlockTypes: new Set<string>([
      'list',
      'filter-bar',
      'filter-field',
      'table',
      'column',
      'widget',
      ...SHARED_LAYOUT_BLOCKS,
      ...SHARED_ACTION_BLOCKS,
    ]),
  },
  detail: {
    rootBlockType: 'detail',
    allowedBlockTypes: new Set<string>([
      'detail',
      ...SHARED_DETAIL_BLOCKS,
      ...SHARED_LAYOUT_BLOCKS,
      ...SHARED_ACTION_BLOCKS,
      ...SHARED_WORKFLOW_BLOCKS,
      ...SHARED_WORKBENCH_BLOCKS,
      ...SHARED_DETAIL_DISPLAY_BLOCKS,
    ]),
  },
  dashboard: {
    rootBlockType: 'dashboard',
    allowedBlockTypes: new Set<string>([
      'dashboard',
      'widget',
      ...SHARED_WORKBENCH_BLOCKS,
      ...SHARED_DASHBOARD_DISPLAY_BLOCKS,
    ]),
  },
  composite: {
    rootBlockType: null,
    allowedBlockTypes: null,
  },
};

export function getKindPolicy(kind: PageSchemaV3Kind): KindPolicy {
  return POLICIES[kind] ?? POLICIES.composite;
}

/** Whether a block type may appear in the palette / be inserted for this kind. */
export function isBlockTypeAllowedForKind(kind: PageSchemaV3Kind, blockType: string): boolean {
  const policy = getKindPolicy(kind);
  if (!policy.allowedBlockTypes) return true;
  if (policy.allowedBlockTypes.has(blockType)) return true;
  // Plugin-contributed custom blocks may opt into specific kinds (see customBlockRegistry).
  return isCustomBlockAllowedForKind(kind, blockType);
}
