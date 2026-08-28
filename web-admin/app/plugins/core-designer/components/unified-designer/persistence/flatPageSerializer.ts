/**
 * Flat page (v4) serializer — the persistence-boundary inverse of
 * `migratePageSchemaV2ToV3`.
 *
 * The unified designer edits a recursive PageSchemaV3 tree in memory, but the
 * stored / runtime page dialect is the flat v4 block list
 * (`DslRegistry.PAGE_SCHEMA_CURRENT_VERSION = 4`): top-level blocks such as
 * `filters` / `toolbar` / `form-buttons` / `table` / `form-section` /
 * `detail-section` carry their leaves in `fields` / `columns` / `buttons` /
 * `rowActions` arrays, and the page kind lives on the row — not in a wrapper
 * block. Before 2026-08 the repository wrote the raw tree back with
 * `schemaVersion: 3`, which the plugin import gate hard-rejects and the
 * DynamicPageRenderer cannot render; every save now funnels through this
 * serializer instead so storage stays single-dialect v4.
 *
 * Fidelity contract: a v4 page produced by `PageSchemaDefaultBlockGenerator`
 * (or the plugin importer) round-trips byte-identically through
 * migrate → edit-free serialize. Trees authored natively in the designer
 * serialize to render-equivalent flat blocks. Structural content with no flat
 * representation (composite/dashboard kinds, designer-only widgets, misplaced
 * leaves) fails fast with the offending block id instead of being dropped.
 */
import type { DslBlockV3, LegacyDslBlockV2, PageSchemaV3, PageSchemaV3Kind } from '../types';
import { toStableBlockId } from '../utils/blockIds';

export const FLAT_PAGE_SCHEMA_VERSION = 4;

/** Tree block types that only exist inside the designer and have no flat v4 counterpart. */
const DESIGNER_ONLY_BLOCK_TYPES = new Set([
  'form',
  'dashboard',
  'repeater',
  'subform',
]);

/** Block types serialized generically: props + nested blocks pass through unchanged. */
const PASSTHROUGH_BLOCK_TYPES = new Set([
  'ai-fill-banner',
  'sub-table',
  'embedded-list',
  'activity-timeline',
  'record-comments',
  'field-history',
  'bpm-panel',
  'metric-strip',
  'stage-rail',
  'record-inspector',
  'candidate-list',
  'workbench-action-bar',
  'evidence-panel',
  'artifact-timeline',
  'review-drawer',
  'status-banner',
  'description',
  'divider',
  'rich-text',
  'chart',
  'stat-card',
  'monthly-grid',
  'card-grid',
  'code-snippet',
  'conversation-panel',
  'selection-info',
  'trace-graph',
  'gerber-viewer',
  'custom',
]);

export class FlatSerializationError extends Error {
  readonly issues: string[];

  constructor(issues: string[]) {
    super(
      `Page content cannot be serialized to the flat v4 runtime dialect: ${issues.join('; ')}`,
    );
    this.name = 'FlatSerializationError';
    this.issues = issues;
  }
}

export interface FlatPageDocument {
  schemaVersion: typeof FLAT_PAGE_SCHEMA_VERSION;
  kind: PageSchemaV3Kind;
  blocks: LegacyDslBlockV2[];
  /**
   * The editor tree's synthetic kind-root id, when the document had one. Flat
   * storage has no root block; persisting the id in the page extension lets
   * the load path rebuild the same root so outline ids, audit block paths and
   * undo history stay stable across a save/reload cycle.
   */
  rootBlockId?: string;
}

export function serializePageTreeToFlat(document: PageSchemaV3): FlatPageDocument {
  const issues: string[] = [];
  const kind = document.kind;
  if (kind !== 'list' && kind !== 'form' && kind !== 'detail') {
    throw new FlatSerializationError([
      `kind "${kind}" has no flat v4 runtime representation (expected list | form | detail)`,
    ]);
  }

  const { topLevel, rootBlockId } = resolveTopLevelBlocks(document);
  const blocks: LegacyDslBlockV2[] = [];
  for (const block of topLevel) {
    const flat = serializeBlock(block, issues);
    if (flat) blocks.push(flat);
  }
  if (issues.length > 0) {
    throw new FlatSerializationError(issues);
  }
  return { schemaVersion: FLAT_PAGE_SCHEMA_VERSION, kind, blocks, rootBlockId };
}

function resolveTopLevelBlocks(document: PageSchemaV3): {
  topLevel: DslBlockV3[];
  rootBlockId?: string;
} {
  const blocks = document.blocks ?? [];
  if (blocks.length === 1 && blocks[0].blockType === document.kind) {
    // Documents loaded from stored v4 rows are wrapped in a synthetic kind
    // root by migratePageSchemaV2ToV3; the wrapper is an editor artifact and
    // is dropped here. Its dataSource {model} is already mirrored on the page.
    const children = blocks[0].blocks ?? [];
    if (children.length > 0) return { topLevel: children, rootBlockId: blocks[0].id };
  }
  return { topLevel: blocks, rootBlockId: undefined };
}

function serializeBlock(block: DslBlockV3, issues: string[]): LegacyDslBlockV2 | null {
  if (DESIGNER_ONLY_BLOCK_TYPES.has(block.blockType)) {
    issues.push(describeUnsupported(block));
    return null;
  }

  switch (block.blockType) {
    case 'filter-bar':
      return serializeFilterBar(block, issues);
    case 'action-bar':
      return serializeActionBar(block);
    case 'form-section':
    case 'detail-section':
      return serializeSection(block, issues);
    case 'table':
      return serializeTable(block);
    case 'tabs':
      return serializeTabs(block, issues);
    case 'widget': {
      // migrateWidgetLikeBlock maps flat `chart` / `stat-card` rows to editor
      // widgets. `number-card` converges to the flat stat-card vocabulary (the
      // list renderer dispatches stat-card via the BlockRenderer fallback);
      // other widget types belong to the dashboard designer and have no
      // page-side flat counterpart.
      const widgetType = block.widgetType ?? '';
      if (widgetType === 'chart' || widgetType === 'stat-card' || widgetType === 'number-card') {
        const flatType = widgetType === 'number-card' ? 'stat-card' : widgetType;
        return serializePassthrough({ ...block, blockType: flatType }, issues);
      }
      issues.push(describeUnsupported(block));
      return null;
    }
    case 'field':
    case 'filter-field':
    case 'column':
    case 'action':
      issues.push(
        `${describeBlock(block)} must be nested inside its container block (section / filter-bar / table / action-bar)`,
      );
      return null;
    default:
      if (!PASSTHROUGH_BLOCK_TYPES.has(block.blockType)) {
        issues.push(describeUnsupported(block));
        return null;
      }
      return serializePassthrough(block, issues);
  }
}

/**
 * filter-bar → `filters`. The flat filters block keeps its action/button
 * shorthand arrays; migratePageSchemaV2ToV3 preserves them inside the
 * filter-bar's `props`, so hoisting them back restores the original shape.
 * The `filters` flat block carries no `region` — that key is editor-side only.
 */
function serializeFilterBar(block: DslBlockV3, issues: string[]): LegacyDslBlockV2 {
  const { actions, buttons, ...residualProps } = stripNone(block.props);
  const flat: LegacyDslBlockV2 = {
    id: block.id,
    blockType: 'filters',
    fields: serializeFieldEntries(block.blocks ?? [], block.id, ['filter-field'], issues),
  };
  if (block.title !== undefined) flat.title = block.title;
  if (Array.isArray(actions)) flat.actions = actions as Array<string | Record<string, unknown>>;
  if (Array.isArray(buttons)) flat.buttons = buttons as Array<string | Record<string, unknown>>;
  applyCommonShape(block, flat, residualProps);
  return flat;
}

/**
 * action-bar → `form-buttons` when it carried the form footer, otherwise
 * `toolbar` (list toolbar / detail header). migratePageSchemaV2ToV3 maps
 * `toolbar` and `form-buttons` to these exact regions, so the region is a
 * reliable discriminator.
 */
function serializeActionBar(block: DslBlockV3): LegacyDslBlockV2 {
  const flatType = block.region === 'footer' ? 'form-buttons' : 'toolbar';
  const flat: LegacyDslBlockV2 = {
    id: block.id,
    blockType: flatType,
    buttons: serializeActionEntries(block.blocks ?? [], block.id),
  };
  if (block.title !== undefined) flat.title = block.title;
  applyCommonShape(block, flat);
  return flat;
}

/** form-section / detail-section → same-named flat block with its `fields` array restored. */
function serializeSection(block: DslBlockV3, issues: string[]): LegacyDslBlockV2 {
  const { columns, collapsible, defaultCollapsed, ...residualProps } = stripNone(block.props);
  const flat: LegacyDslBlockV2 = {
    id: block.id,
    blockType: block.blockType,
    fields: serializeFieldEntries(block.blocks ?? [], block.id, ['field'], issues),
  };
  if (block.title !== undefined) flat.title = block.title;
  if (block.region !== undefined) flat.region = block.region;
  if (columns !== undefined) flat.columns = columns as LegacyDslBlockV2['columns'];
  if (collapsible !== undefined) flat.collapsible = collapsible;
  if (defaultCollapsed !== undefined) flat.defaultCollapsed = defaultCollapsed;
  applyCommonShape(block, flat, residualProps);
  return flat;
}

/** table → flat table block; `columns` / `rowActions` arrays are restored from children and props. */
function serializeTable(block: DslBlockV3): LegacyDslBlockV2 {
  const { selection, rowActions, ...residualProps } = stripNone(block.props);
  const columns: LegacyDslBlockV2['columns'] = [];
  const serializedRowActions: Array<string | Record<string, unknown>> = [];
  for (const child of block.blocks ?? []) {
    if (child.blockType === 'column') {
      columns.push(serializeColumnEntry(child, block.id));
    } else if (child.blockType === 'action') {
      serializedRowActions.push(serializeActionEntry(child, block.id));
    } else {
      // Not reachable through migratePageSchemaV2ToV3; keep the guard loud.
      columns.push(child as unknown as Record<string, unknown>);
    }
  }
  const flat: LegacyDslBlockV2 = {
    id: block.id,
    blockType: 'table',
    columns,
  };
  if (block.title !== undefined) flat.title = block.title;
  if (block.region !== undefined) flat.region = block.region;
  if (serializedRowActions.length > 0) flat.rowActions = serializedRowActions;
  else if (Array.isArray(rowActions)) {
    flat.rowActions = rowActions as Array<string | Record<string, unknown>>;
  }
  if (selection !== undefined) flat.selection = selection as Record<string, unknown>;
  applyCommonShape(block, flat, residualProps);
  return flat;
}

/** tabs → flat tabs container; each `tab` child becomes a `tabs[]` entry with its nested blocks. */
function serializeTabs(block: DslBlockV3, issues: string[]): LegacyDslBlockV2 {
  const flat: LegacyDslBlockV2 = {
    id: block.id,
    blockType: 'tabs',
    tabs: (block.blocks ?? []).map((tab, index) =>
      serializeTabEntry(tab, block.id, index, issues),
    ),
  };
  if (block.title !== undefined) flat.title = block.title;
  if (block.region !== undefined) flat.region = block.region;
  applyCommonShape(block, flat);
  return flat;
}

function serializeTabEntry(
  tab: DslBlockV3,
  parentId: string,
  index: number,
  issues: string[],
): Record<string, unknown> {
  const entry: Record<string, unknown> = {
    ...stripNone(tab.props),
    blocks: (tab.blocks ?? []).map((child) => {
      const flat = serializeBlock(child, issues);
      return flat ?? {};
    }),
  };
  if (tab.title !== undefined) entry.label = tab.title;
  entry.key = recoverTabKey(tab, parentId, index);
  // Keep the tree child id so a reload rebuilds the identical outline node.
  if (tab.id) entry.id = tab.id;
  return entry;
}

/** Leaf passthrough: props + nested blocks survive as-is (matches stored v4 custom content). */
function serializePassthrough(block: DslBlockV3, issues: string[]): LegacyDslBlockV2 {
  const flat: LegacyDslBlockV2 = {
    id: block.id,
    blockType: block.blockType,
  };
  if (block.region !== undefined) flat.region = block.region;
  if (block.title !== undefined) flat.title = block.title;
  if (block.dataSource !== undefined) flat.dataSource = unwrapDataSourceRef(block.dataSource);
  applyCommonShape(block, flat, stripNone(block.props));
  const children = block.blocks ?? [];
  if (children.length > 0) {
    flat.blocks = children
      .map((child) => serializeBlock(child, issues))
      .filter((block): block is LegacyDslBlockV2 => block !== null);
  }
  return flat;
}

function applyCommonShape(
  block: DslBlockV3,
  flat: LegacyDslBlockV2,
  residualProps?: Record<string, unknown>,
): void {
  const layout = (block.layout ?? {}) as Record<string, unknown>;
  if (typeof layout.span === 'number') flat.span = layout.span;
  if (typeof layout.rowSpan === 'number') flat.rowSpan = layout.rowSpan;
  if (block.dataSource !== undefined) flat.dataSource = unwrapDataSourceRef(block.dataSource);
  const props = { ...(residualProps ?? {}) };
  const residualLayout = { ...layout };
  delete residualLayout.span;
  delete residualLayout.rowSpan;
  if (Object.keys(residualLayout).length > 0) flat.layout = residualLayout;
  if (Object.keys(props).length > 0) flat.props = props;
}

/**
 * field / filter-field → `fields[]` entry; plain references collapse back to
 * strings. Object entries keep the tree block's stable id — except when it
 * equals the canonical id `migrateFieldRef` would synthesize on reload, so
 * generator-shaped stored pages round-trip deep-equal.
 */
function serializeFieldEntries(
  children: DslBlockV3[],
  parentId: string,
  allowedLeafTypes: readonly string[],
  issues: string[],
): LegacyDslBlockV2['fields'] {
  return children
    .map((child) => {
      if (!allowedLeafTypes.includes(child.blockType)) {
        // A container nested inside a section / filter-bar has no flat fields[]
        // representation; recording it as a field entry would silently drop its
        // whole subtree, so it must fail the save instead.
        issues.push(
          `${describeBlock(child)} cannot live inside a section / filter-bar: it has no flat fields[] representation and would be silently dropped`,
        );
        return null;
      }
      return serializeFieldLikeEntry(child, parentId);
    })
    .filter((entry): entry is string | Record<string, unknown> => entry !== null);
}

function serializeColumnEntry(child: DslBlockV3, parentId: string): string | Record<string, unknown> {
  return serializeFieldLikeEntry(child, parentId);
}

function serializeFieldLikeEntry(block: DslBlockV3, parentId: string): string | Record<string, unknown> {
  const field = block.field ?? 'field';
  const layout = (block.layout ?? {}) as Record<string, unknown>;
  const props = { ...stripNone(block.props) };
  const idIsCanonical = !block.id || block.id === toStableBlockId(parentId, field);
  if (Object.keys(props).length === 0 && Object.keys(layout).length === 0 && idIsCanonical) {
    return field;
  }
  const entry: Record<string, unknown> = { ...props };
  entry.field = field;
  if (!idIsCanonical) entry.id = block.id;
  if (typeof layout.span === 'number') entry.span = layout.span;
  if (typeof layout.width === 'number') entry.width = layout.width;
  return entry;
}

/**
 * action → `buttons[]` / `actions[]` / `rowActions[]` entry; bare verbs collapse
 * back to strings. The v4 renderers read semantics from `action` / `navigateTo`
 * / `commandCode` — a top-level `actionType` is only emitted for designer-authored
 * actions that carry none of those keys, so generator-shaped pages round-trip
 * deep-equal.
 */
function serializeActionEntries(
  children: DslBlockV3[],
  parentId: string,
): Array<string | Record<string, unknown>> {
  return children.map((child) => serializeActionEntry(child, parentId));
}

function serializeActionEntry(block: DslBlockV3, parentId: string): string | Record<string, unknown> {
  const actionType = block.actionType ?? 'custom';
  const props = stripNone(block.props);
  const code = typeof props.code === 'string' ? props.code : actionType;
  const idIsCanonical = !block.id || block.id === toStableBlockId(parentId, code);
  if (Object.keys(props).length === 0 && block.title === undefined && idIsCanonical) {
    return actionType;
  }
  const { code: _code, actionType: _actionType, ...rest } = props;
  const entry: Record<string, unknown> = { ...rest };
  entry.code = code;
  // Always stamp the current actionType: migrateActionRef leaks the stored
  // entry's `code` into props, and without this stamp a changed actionType
  // would silently revert on reload because normalizeActionType falls back to
  // the stale code. The v4 renderers ignore the extra key.
  entry.actionType = actionType;
  if (!idIsCanonical) entry.id = block.id;
  if (block.title !== undefined) entry.title = block.title;
  const layout = (block.layout ?? {}) as Record<string, unknown>;
  if (typeof layout.span === 'number') entry.span = layout.span;
  return entry;
}

/** migrateToV3 wraps string dataSources as {ref}; stored flat pages carry the bare string. */
function unwrapDataSourceRef(dataSource: unknown): unknown {
  if (
    dataSource &&
    typeof dataSource === 'object' &&
    !Array.isArray(dataSource) &&
    Object.keys(dataSource as Record<string, unknown>).length === 1
  ) {
    const ref = (dataSource as Record<string, unknown>).ref;
    if (typeof ref === 'string') return ref;
  }
  return dataSource;
}

function stripNone(props: Record<string, unknown> | undefined): Record<string, unknown> {
  if (!props) return {};
  const next: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(props)) {
    if (value !== undefined && value !== null) next[key] = value;
  }
  return next;
}

/**
 * migrateTabRef slugs the original tab key into the child id
 * (`toStableBlockId(parentId, key)`), so the best recoverable key is the
 * id suffix after the parent prefix; fall back to a stable positional key.
 */
function recoverTabKey(tab: DslBlockV3, parentId: string, index: number): string {
  const explicit = typeof tab.props?.key === 'string' ? tab.props.key : undefined;
  if (explicit) return explicit;
  const parentSlug = toStableBlockId(parentId);
  if (parentSlug && tab.id?.startsWith(`${parentSlug}_`)) {
    const suffix = tab.id.slice(parentSlug.length + 1);
    if (suffix) return suffix;
  }
  return `tab_${index + 1}`;
}

function describeBlock(block: DslBlockV3): string {
  return `block "${block.id}" (${block.blockType})`;
}

function describeUnsupported(block: DslBlockV3): string {
  return `${describeBlock(block)} has no flat v4 runtime counterpart`;
}
