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

// E2 batch — non-family display / chart / graph / layout / form / list blocks.
// `divider` is a generic separator surfaced on every concrete kind. The viz/display
// blocks (chart, rich-text, trace-graph, selection-info, gerber-viewer) belong to
// the cockpit kinds (detail + dashboard). form-buttons/form-wizard are form
// composition blocks; filters/toolbar are list tooling blocks.
const SHARED_FORM_COMPOSE_BLOCKS = ['form-buttons', 'form-wizard'] as const;
const SHARED_LIST_TOOL_BLOCKS = ['filters', 'toolbar'] as const;
const SHARED_VIZ_DISPLAY_BLOCKS = [
  'chart',
  'rich-text',
  'trace-graph',
  'selection-info',
  'gerber-viewer',
] as const;

const POLICIES: Record<PageSchemaV3Kind, KindPolicy> = {
  form: {
    rootBlockType: 'form',
    allowedBlockTypes: new Set<string>([
      'form',
      ...SHARED_FORM_BLOCKS,
      ...SHARED_LAYOUT_BLOCKS,
      ...SHARED_ACTION_BLOCKS,
      ...SHARED_WORKFLOW_BLOCKS,
      ...SHARED_FORM_COMPOSE_BLOCKS,
      'divider',
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
      ...SHARED_LIST_TOOL_BLOCKS,
      'divider',
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
      ...SHARED_VIZ_DISPLAY_BLOCKS,
      'toolbar',
      'divider',
    ]),
  },
  dashboard: {
    rootBlockType: 'dashboard',
    allowedBlockTypes: new Set<string>([
      'dashboard',
      'widget',
      ...SHARED_WORKBENCH_BLOCKS,
      ...SHARED_DASHBOARD_DISPLAY_BLOCKS,
      ...SHARED_VIZ_DISPLAY_BLOCKS,
      'divider',
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

// ── C4: page-kind switching ────────────────────────────────────────────────
// The concrete kinds a user can switch a page between. `composite` is the
// internal escape hatch (rootBlockType null, allows everything) and is NOT a
// normal authoring entry point, so it is excluded from the switch targets.
export const KIND_SWITCH_TARGETS: PageSchemaV3Kind[] = ['form', 'list', 'detail', 'dashboard'];

/** A block that is valid under the current kind but not under a target kind. */
export interface IncompatibleBlock {
  id: string;
  blockType: string;
}

/**
 * Blocks that would be invalid if the page switched to `targetKind`.
 *
 * The page root (`blocks[0]`, whose blockType is the current kind's root
 * container) is swapped to the target kind's root on switch, so it is excluded;
 * only its descendants are checked against the target kind's palette policy.
 * Returns [] for `composite` (allows everything).
 */
export function getIncompatibleBlocksForKind(
  blocks: DslBlockV3[] | undefined,
  targetKind: PageSchemaV3Kind,
): IncompatibleBlock[] {
  if (!getKindPolicy(targetKind).allowedBlockTypes) return [];
  const out: IncompatibleBlock[] = [];
  const walk = (bs: DslBlockV3[] | undefined): void => {
    for (const block of bs ?? []) {
      if (!isBlockTypeAllowedForKind(targetKind, block.blockType)) {
        out.push({ id: block.id, blockType: block.blockType });
      }
      walk(block.blocks);
    }
  };
  // Skip the root container itself; check every descendant.
  walk(blocks?.[0]?.blocks);
  return out;
}

/**
 * Whether the page may switch to `targetKind`. Per the C4 design (owner choice
 * 2026-06-18): the switch is BLOCKED when any descendant block is incompatible —
 * no silent data loss, the author removes the offending blocks first. Also
 * requires the standard single-root structure (every designer-authored page has
 * exactly one root container); multi-root / rootless documents are not switchable.
 */
export function canSwitchToKind(
  blocks: DslBlockV3[] | undefined,
  targetKind: PageSchemaV3Kind,
): boolean {
  if (!blocks || blocks.length !== 1) return false;
  return getIncompatibleBlocksForKind(blocks, targetKind).length === 0;
}
