import type { PropertySchema } from '~/shared/designer';
import type { DslBlockV3 } from '../types';

export class InspectorSchemaRegistry {
  private readonly schemas = new Map<string, PropertySchema<string>[]>();
  private readonly dynamicSchemas = new Map<
    string,
    (block: DslBlockV3) => PropertySchema<string>[]
  >();

  constructor(private readonly fallbackFields: PropertySchema<string>[]) {}

  register(blockType: string, fields: PropertySchema<string>[]): void {
    this.schemas.set(blockType, fields);
  }

  registerDynamic(
    blockType: string,
    resolver: (block: DslBlockV3) => PropertySchema<string>[],
  ): void {
    this.dynamicSchemas.set(blockType, resolver);
  }

  registerAll(entries: Record<string, PropertySchema<string>[]>): void {
    Object.entries(entries).forEach(([blockType, fields]) => this.register(blockType, fields));
  }

  getFields(blockType: string): PropertySchema<string>[] {
    return this.schemas.get(blockType) ?? this.fallbackFields;
  }

  getFieldsForBlock(block: DslBlockV3): PropertySchema<string>[] {
    const base = this.dynamicSchemas.get(block.blockType)?.(block) ?? this.getFields(block.blockType);
    // MD-2: every block is lockable — append the AI-lock toggle unless the
    // block's own schema already declares it (e.g. form fields from D5).
    return base.some((field) => field.key === aiLockField.key) ? base : [...base, aiLockField];
  }
}

const defaultFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
  { key: 'region', label: 'Region', type: 'text' },
];

const permissionCodeField: PropertySchema<string> = {
  key: 'props.permissionCode',
  label: 'Permission code',
  // D2 — rich control: a selector over the live permission registry (with a
  // manual-entry fallback) instead of a free-text box.
  type: 'permission-select',
};

// MD-2 — block-level AI lock. Lifts D5's field-level lock to a universal inspector
// toggle (appended to every block by getFieldsForBlock) so an author can mark ANY
// block aiLocked and the in-designer AI copilot won't overwrite it on re-generate
// (enforced by applyDesignBlocks.isBlockLocked).
const aiLockField: PropertySchema<string> = {
  key: 'props.aiLocked',
  label: 'AI locked',
  type: 'boolean',
};

const modelContainerFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'dataSource.model', label: 'Model', type: 'model' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const formSectionFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'props.description', label: 'Description', type: 'text' },
  { key: 'props.collapsible', label: 'Collapsible', type: 'boolean' },
  { key: 'props.visibleWhen', label: 'Visible when JSON', type: 'json' },
  { key: 'layout.columns', label: 'Columns', type: 'number' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const columnsContainerFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'layout.columns', label: 'Columns', type: 'number' },
  { key: 'layout.gap', label: 'Gap', type: 'number' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const listFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'dataSource.model', label: 'Model', type: 'model' },
  {
    key: 'props.selectionMode',
    label: 'Selection',
    type: 'select',
    options: [
      { label: 'Single row', value: 'single' },
      { label: 'Multiple rows', value: 'multiple' },
    ],
  },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const dashboardFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
  { key: 'layout.cols', label: 'Columns', type: 'number' },
  { key: 'layout.rowHeight', label: 'Row height', type: 'number' },
  { key: 'layout.gap', label: 'Gap', type: 'number' },
];

const fieldFields: PropertySchema<string>[] = [
  { key: 'field', label: 'Field', type: 'field-select' },
  { key: 'props.label', label: 'Label', type: 'text' },
  {
    key: 'props.component',
    label: 'Component',
    type: 'select',
    options: [
      { label: 'Input', value: 'input' },
      { label: 'Textarea', value: 'textarea' },
      { label: 'Select', value: 'select' },
      { label: 'Multi-select', value: 'multiselect' },
      { label: 'Date', value: 'date' },
      { label: 'Date range', value: 'daterange' },
      { label: 'Time picker', value: 'timepicker' },
      { label: 'Time range', value: 'timerangepicker' },
      { label: 'Number', value: 'number' },
      { label: 'Money', value: 'moneyinput' },
      { label: 'Progress', value: 'progress' },
      { label: 'Rating', value: 'rating' },
      { label: 'Checkbox', value: 'checkbox' },
      { label: 'Switch', value: 'switch' },
      { label: 'Radio', value: 'radio' },
      { label: 'Color picker', value: 'colorpicker' },
      { label: 'Cascade select', value: 'cascadeselect' },
      { label: 'Tree select', value: 'treeselect' },
      { label: 'User select', value: 'userselect' },
      { label: 'Member picker', value: 'memberpicker' },
      { label: 'Organization select', value: 'organizationselect' },
      { label: 'Address', value: 'addressfield' },
      { label: 'Upload', value: 'upload' },
      { label: 'File attachment', value: 'fileattachment' },
      { label: 'Picker', value: 'picker' },
      { label: 'Rich text', value: 'rich-text' },
      { label: 'AI field', value: 'aifield' },
    ],
  },
  { key: 'props.dataType', label: 'Data type', type: 'text' },
  { key: 'props.dictCode', label: 'Dict code', type: 'dict-select' },
  { key: 'props.required', label: 'Required', type: 'boolean' },
  { key: 'props.readOnly', label: 'Read only', type: 'boolean' },
  aiLockField,
  permissionCodeField,
  { key: 'props.placeholder', label: 'Placeholder', type: 'text' },
  { key: 'props.helpText', label: 'Help text', type: 'text' },
  { key: 'props.options', label: 'Options JSON', type: 'json' },
  { key: 'props.visibleWhen', label: 'Visible when JSON', type: 'json' },
  { key: 'props.validationRules', label: 'Validation rules JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const pickerFieldFields: PropertySchema<string>[] = [
  {
    key: 'props.pickerDataSource',
    label: 'Picker data source',
    type: 'select',
    options: [
      { label: 'Static options', value: 'static' },
      { label: 'Model', value: 'model' },
      { label: 'Named query', value: 'named-query' },
    ],
  },
  { key: 'props.pickerSource', label: 'Picker source', type: 'text' },
  { key: 'props.pickerQueryCode', label: 'Named query code', type: 'text' },
  { key: 'props.valueField', label: 'Value field', type: 'text' },
  { key: 'props.displayField', label: 'Display field', type: 'text' },
  { key: 'props.searchable', label: 'Searchable', type: 'boolean' },
  { key: 'props.searchPlaceholder', label: 'Search placeholder', type: 'text' },
  { key: 'props.searchField', label: 'Search field', type: 'text' },
  { key: 'props.searchParameter', label: 'Search parameter', type: 'text' },
  { key: 'props.pageSize', label: 'Page size', type: 'number', min: 1 },
  { key: 'props.pickerParameters', label: 'Picker parameters JSON', type: 'json' },
];

const richTextFieldFields: PropertySchema<string>[] = [
  { key: 'props.richTextToolbar', label: 'Rich text toolbar JSON', type: 'json' },
];

const uploadFieldFields: PropertySchema<string>[] = [
  { key: 'props.accept', label: 'Accepted file types', type: 'text' },
  { key: 'props.multiple', label: 'Multiple files', type: 'boolean' },
  { key: 'props.maxFiles', label: 'Max files', type: 'number' },
];

const filterFieldFields: PropertySchema<string>[] = [
  ...fieldFields,
  {
    key: 'props.operator',
    label: 'Operator',
    type: 'select',
    options: [
      { label: 'Equals', value: 'equals' },
      { label: 'Contains', value: 'contains' },
      { label: 'Greater than', value: 'gt' },
      { label: 'Less than', value: 'lt' },
      { label: 'Between', value: 'between' },
    ],
  },
];

const columnFields: PropertySchema<string>[] = [
  { key: 'props.label', label: 'Label', type: 'text' },
  { key: 'field', label: 'Field', type: 'field-select' },
  permissionCodeField,
  { key: 'props.dataType', label: 'Data type', type: 'text' },
  { key: 'props.dictCode', label: 'Dict code', type: 'dict-select' },
  { key: 'layout.width', label: 'Width', type: 'number' },
  {
    key: 'props.align',
    label: 'Align',
    type: 'select',
    options: [
      { label: 'Left', value: 'left' },
      { label: 'Center', value: 'center' },
      { label: 'Right', value: 'right' },
    ],
  },
];

const tableFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'props.rows', label: 'Preview rows JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const subTableFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'dataSource.model', label: 'Model', type: 'model' },
  { key: 'dataSource.parentField', label: 'Parent field', type: 'text' },
  { key: 'dataSource.childField', label: 'Child field', type: 'text' },
  { key: 'props.rows', label: 'Preview rows JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const repeaterFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'props.rows', label: 'Preview rows JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const subformFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'props.rows', label: 'Preview rows JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const helperDataSourceFields: PropertySchema<string>[] = [
  permissionCodeField,
  {
    key: 'dataSource.type',
    label: 'Data source',
    type: 'select',
    options: [
      { label: 'Static preview', value: 'static' },
      { label: 'Query builder', value: 'query-builder' },
      { label: 'Named query', value: 'namedQuery' },
    ],
  },
  {
    key: 'dataSource.executionMode',
    label: 'Data execution',
    type: 'select',
    options: [
      { label: 'Run data source', value: 'live' },
      { label: 'Static preview', value: 'preview' },
    ],
  },
  { key: 'dataSource.query', label: 'Query builder JSON', type: 'json' },
  { key: 'dataSource.queryCode', label: 'Named query code', type: 'namedQuery' },
  { key: 'dataSource.parameters', label: 'Named query params JSON', type: 'json' },
  { key: 'dataSource.page', label: 'Page', type: 'number' },
  { key: 'dataSource.size', label: 'Page size', type: 'number' },
];

const aiFillBannerFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'props.description', label: 'Description', type: 'text' },
  { key: 'props.suggestedFields', label: 'Suggested fields JSON', type: 'json' },
  { key: 'props.feedback', label: 'Apply feedback', type: 'text' },
  { key: 'props.emptyText', label: 'Empty text', type: 'text' },
  ...helperDataSourceFields,
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const bpmPanelFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'props.description', label: 'Description', type: 'text' },
  {
    key: 'props.status',
    label: 'Status',
    type: 'select',
    options: [
      { label: 'Draft', value: 'draft' },
      { label: 'Pending', value: 'pending' },
      { label: 'Approved', value: 'approved' },
      { label: 'Rejected', value: 'rejected' },
      { label: 'Completed', value: 'completed' },
    ],
  },
  { key: 'props.assignee', label: 'Assignee', type: 'text' },
  { key: 'props.dueAt', label: 'Due at', type: 'text' },
  { key: 'props.actions', label: 'Actions JSON', type: 'json' },
  { key: 'props.emptyText', label: 'Empty text', type: 'text' },
  ...helperDataSourceFields,
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const activityTimelineFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'props.items', label: 'Items JSON', type: 'json' },
  { key: 'props.emptyText', label: 'Empty text', type: 'text' },
  ...helperDataSourceFields,
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const fieldHistoryFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'props.entries', label: 'Entries JSON', type: 'json' },
  { key: 'props.emptyText', label: 'Empty text', type: 'text' },
  ...helperDataSourceFields,
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// Workbench blocks — metric-strip & status-banner.
//
// These are rendered on the live /p/ page by the platform meta-rendering
// renderers (framework/meta/rendering/blocks/MetricStripBlockRenderer +
// StatusBannerBlockRenderer), which read their configuration from the BLOCK TOP
// LEVEL (block.dataSource / block.metrics / block.variant / block.statusField /
// block.toneMap / …), NOT from block.props. So every field key below is a bare
// top-level path (e.g. `metrics`, `variant`, `statusField`) rather than
// `props.*`; setByPath then persists it exactly where the platform renderer
// reads it. The shapes here mirror the real authored pages (mfg_andon_workbench
// metric-strip, bom-standardization status-banner) and the renderer source — no
// invented fields, only props that are actually consumed.

const metricStripFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  // dataSource is a string id on workbench blocks (the named data source the
  // page binds), so it is a plain text field rather than the model selector.
  { key: 'dataSource', label: 'Data source', type: 'text' },
  {
    key: 'variant',
    label: 'Variant',
    type: 'select',
    options: [
      { label: 'Cards', value: 'cards' },
      { label: 'Chips', value: 'chips' },
    ],
  },
  // metrics is an array of { key, label, valueField, value, unit, unitField,
  // subText, subTextField, tone, valueMap, onClick, visibleWhen, activeWhen }.
  // Authored as JSON — the renderer iterates this array directly.
  { key: 'metrics', label: 'Metrics JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const statusBannerFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'dataSource', label: 'Data source', type: 'text' },
  // statusField/errorField select which record paths drive the banner.
  { key: 'statusField', label: 'Status field', type: 'text' },
  { key: 'errorField', label: 'Error field', type: 'text' },
  // status -> tone | localized title | localized description template maps.
  { key: 'toneMap', label: 'Tone map JSON', type: 'json' },
  { key: 'titleMap', label: 'Title map JSON', type: 'json' },
  { key: 'descriptionMap', label: 'Description map JSON', type: 'json' },
  // statuses that hide the banner / mark it failed.
  { key: 'hideStatuses', label: 'Hide statuses JSON', type: 'json' },
  { key: 'failedStatuses', label: 'Failed statuses JSON', type: 'json' },
  // summaryFields: array of { key, label, field, linkField?, linkTo? }.
  { key: 'summaryFields', label: 'Summary fields JSON', type: 'json' },
  // poll: { enabledWhenStatuses, reload, intervalMs, refreshPageWhenStatuses }.
  { key: 'poll', label: 'Polling JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// Workbench-family batch 2 inspector schemas. Same contract as metric-strip /
// status-banner: every key below is a BARE top-level path (no props.*) because
// the live platform renderers read them at the block top level. Each schema lists
// only props the renderer actually consumes — no invented fields. Verified
// against the renderer source under framework/meta/rendering/blocks/.

// WorkbenchActionBarBlockRenderer reads block.actions / block.surface /
// block.detailPlacement / block.density / block.align / block.title. Each action
// is { code|id, label, variant, visibleWhen, activeWhen, disabledWhen, onClick }.
const workbenchActionBarFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  {
    key: 'surface',
    label: 'Surface',
    type: 'select',
    options: [
      { label: 'Card', value: 'card' },
      { label: 'Bare', value: 'bare' },
    ],
  },
  {
    key: 'density',
    label: 'Density',
    type: 'select',
    options: [
      { label: 'Default', value: 'default' },
      { label: 'Compact', value: 'compact' },
    ],
  },
  {
    key: 'align',
    label: 'Align',
    type: 'select',
    options: [
      { label: 'Start', value: 'start' },
      { label: 'Center', value: 'center' },
      { label: 'End', value: 'end' },
    ],
  },
  // actions: array of { code, label, variant, visibleWhen, activeWhen,
  // disabledWhen, onClick }. Authored as JSON — the renderer iterates it.
  { key: 'actions', label: 'Actions JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// EvidencePanelBlockRenderer reads block.dataSource (string id) / block.context /
// block.sections / block.title / block.empty. Section: { key, field, label,
// format, items? }. items maps paths inside a JSON section into semantic
// operation cards instead of raw JSON pre blocks.
const evidencePanelFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'dataSource', label: 'Data source', type: 'text' },
  { key: 'context', label: 'Context expression', type: 'text' },
  // sections: array of { key, field, label, format } — the renderer iterates it.
  { key: 'sections', label: 'Sections JSON', type: 'json' },
  { key: 'empty', label: 'Empty state JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// RecordInspectorBlockRenderer reads block.context / block.fields / block.empty
// and renders block.blocks (child blocks) below the field grid. Field: { field,
// path, label, span }.
const recordInspectorFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'context', label: 'Context expression', type: 'text' },
  // fields: array of { field, path, label, span } — the renderer iterates it.
  { key: 'fields', label: 'Fields JSON', type: 'json' },
  { key: 'empty', label: 'Empty state JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// CandidateListBlockRenderer reads block.dataSource (string id) / block.item /
// block.selection / block.actions / block.maxHeight. item: { titleField,
// subtitleField, descriptionField, scoreField, detailFields[], maxHeight }.
const candidateListFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'dataSource', label: 'Data source', type: 'text' },
  // item: { titleField, subtitleField, descriptionField, scoreField,
  // detailFields }. Authored as JSON.
  { key: 'item', label: 'Item config JSON', type: 'json' },
  // selection: { bind } — the runtime state key the selected row is written to.
  { key: 'selection', label: 'Selection JSON', type: 'json' },
  { key: 'actions', label: 'Actions JSON', type: 'json' },
  { key: 'maxHeight', label: 'Max height', type: 'number' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// ArtifactTimelineBlockRenderer reads block.dataSource (string id) / block.item /
// block.title / block.empty. item: { keyField, titleField, subtitleField,
// revisionField, statusField, hashField, fileIdField }.
const artifactTimelineFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'dataSource', label: 'Data source', type: 'text' },
  // item: { keyField, titleField, subtitleField, revisionField, statusField,
  // hashField, fileIdField }. Authored as JSON.
  { key: 'item', label: 'Item config JSON', type: 'json' },
  { key: 'empty', label: 'Empty state JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// ---------------------------------------------------------------------------
// Display / data blocks (non workbench-family). Same bare-path contract as the
// workbench family: every key below is the EXACT path the platform renderer reads
// (verified against each renderer source under framework/meta/rendering/blocks/).
// No invented fields — only props the live renderer actually consumes.
// ---------------------------------------------------------------------------

// StatCardBlockRenderer builds `cfg = { ...block.props, ...block.statCard }`, so
// the metric object lives at block.statCard (bare) with value / unit / trend /
// trendDirection / valueField inside it. block.dataSource is a bare STRING id
// (the named data source whose first row supplies the live value); block.title is
// the card heading. statCard is authored as JSON (a small object editor).
const statCardFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  // dataSource is a string id on display blocks (the named data source the page
  // binds), so it is a plain text field rather than the model selector.
  { key: 'dataSource', label: 'Data source', type: 'text' },
  // statCard: { value, unit, trend, trendDirection: up|down|flat, valueField }.
  // Authored as JSON — the renderer spreads this object over props.
  { key: 'statCard', label: 'Stat card JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// DescriptionBlockRenderer reads `block.content ?? props.content ?? props.text`.
// The BARE `content` path wins, so the inspector exposes it directly (LocalizedText
// or string; HTML is sanitized before render). No other authorable props.
const descriptionFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'content', label: 'Content', type: 'text' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// RecordComments (dispatched by DetailPageContent) derives modelCode + recordPid
// from the SURROUNDING detail page + current record — it reads NO block-level data
// config. So the only authorable surface is the designer title (the canvas label)
// plus the universal AI-lock appended by getFieldsForBlock. Deliberately no data
// fields: surfacing modelCode/recordPid here would be invented (the live renderer
// ignores them).
const recordCommentsFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// EmbeddedListBlockRenderer reads bare top-level block.modelCode (or childModel),
// block.parentField (or foreignKey), block.columns (or table.columns),
// block.title, block.pageSize, block.searchable, block.filterable. The parent
// record id is resolved from the detail route, so this is a DETAIL-only block.
const embeddedListFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'modelCode', label: 'Model code', type: 'text', required: true },
  { key: 'parentField', label: 'Parent field (foreign key)', type: 'text' },
  // columns: array of { field, label, dataType?, dictCode?, width?, align? }.
  // Authored as JSON — the renderer iterates this array directly.
  { key: 'columns', label: 'Columns JSON', type: 'json' },
  { key: 'pageSize', label: 'Page size', type: 'number', min: 1 },
  { key: 'searchable', label: 'Searchable', type: 'boolean' },
  { key: 'filterable', label: 'Filterable', type: 'boolean' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// ReviewDrawerBlockRenderer is the row-level review overlay (the most complex
// workbench block). It reads many top-level keys: context / contextDataSource /
// contextKeyField / titleTemplate / summaryBadges / compare / candidates /
// exportImpact / source / empty / title. Nested objects (compare, candidates,
// exportImpact, source) are authored as JSON.
const reviewDrawerFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'titleTemplate', label: 'Title template', type: 'text' },
  // context expression resolves the selected record (e.g. ${state.selectedRow}).
  { key: 'context', label: 'Context expression', type: 'text' },
  { key: 'contextDataSource', label: 'Context data source', type: 'text' },
  { key: 'contextKeyField', label: 'Context key field', type: 'text' },
  // summaryBadges: array of { key, label, valueField, tone, unit }.
  { key: 'summaryBadges', label: 'Summary badges JSON', type: 'json' },
  // compare: { rawRecord, canonicalRecord, rawFields, canonicalFields, rawTitle,
  // canonicalTitle }.
  { key: 'compare', label: 'Compare panel JSON', type: 'json' },
  // candidates: { dataSource, title, item, decisionFields, actions, selection,
  // selectedFields, reasonField, … }.
  { key: 'candidates', label: 'Candidates JSON', type: 'json' },
  // exportImpact: { dataSource, fields, actions }.
  { key: 'exportImpact', label: 'Export impact JSON', type: 'json' },
  // source: { record, summary, cards, policies, jsonField, … }.
  { key: 'source', label: 'Source evidence JSON', type: 'json' },
  { key: 'empty', label: 'Empty state JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const actionTypeField: PropertySchema<string> = {
  key: 'actionType',
  label: 'Action type',
  type: 'select',
  options: [
    { label: 'Submit', value: 'submit' },
    { label: 'Create', value: 'create' },
    { label: 'Command', value: 'command' },
    { label: 'Workflow', value: 'workflow' },
    { label: 'Navigate', value: 'navigate' },
    { label: 'Modal', value: 'modal' },
    { label: 'Drawer', value: 'drawer' },
  ],
};

const actionBaseFields: PropertySchema<string>[] = [
  { key: 'props.label', label: 'Label', type: 'text' },
  actionTypeField,
  { key: 'props.confirm', label: 'Confirm first', type: 'boolean' },
  permissionCodeField,
  { key: 'props.visibleWhen', label: 'Visible when JSON', type: 'json' },
  { key: 'props.disabledWhen', label: 'Disabled when JSON', type: 'json' },
];

const actionCommonFeedbackFields: PropertySchema<string>[] = [
  { key: 'props.feedback', label: 'Feedback', type: 'text' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const actionExecutionModeField: PropertySchema<string> = {
  key: 'props.executionMode',
  label: 'Execution',
  type: 'select',
  options: [
    { label: 'Preview only', value: 'preview' },
    { label: 'Live API', value: 'live' },
  ],
};

const actionValidateFormField: PropertySchema<string> = {
  key: 'props.validateForm',
  label: 'Validate form',
  type: 'boolean',
  defaultValue: true,
};

const commandActionFields: PropertySchema<string>[] = [
  { key: 'props.command', label: 'Command', type: 'command-select' },
  { key: 'props.payload', label: 'Payload JSON', type: 'json' },
  actionValidateFormField,
  actionExecutionModeField,
  ...actionCommonFeedbackFields,
];

const workflowActionFields: PropertySchema<string>[] = [
  { key: 'props.workflowKey', label: 'Workflow key', type: 'text' },
  { key: 'props.businessKey', label: 'Business key', type: 'text' },
  { key: 'props.payload', label: 'Payload JSON', type: 'json' },
  actionValidateFormField,
  actionExecutionModeField,
  ...actionCommonFeedbackFields,
];

const navigateActionFields: PropertySchema<string>[] = [
  { key: 'props.to', label: 'Route', type: 'text' },
  {
    key: 'props.target',
    label: 'Target',
    type: 'select',
    options: [
      { label: 'Current page', value: 'self' },
      { label: 'New tab', value: 'blank' },
    ],
  },
  { key: 'props.params', label: 'Params JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

const overlayActionFields: PropertySchema<string>[] = [
  { key: 'props.pageKey', label: 'Page key', type: 'text' },
  { key: 'props.title', label: 'Title', type: 'text' },
  { key: 'props.width', label: 'Width', type: 'number' },
  ...actionCommonFeedbackFields,
];

const submitActionFields: PropertySchema<string>[] = [
  actionValidateFormField,
  { key: 'props.successMessage', label: 'Success message', type: 'text' },
  ...actionCommonFeedbackFields,
];

const createActionFields: PropertySchema<string>[] = [
  actionValidateFormField,
  {
    key: 'props.openMode',
    label: 'Open mode',
    type: 'select',
    options: [
      { label: 'Drawer', value: 'drawer' },
      { label: 'Modal', value: 'modal' },
      { label: 'Navigate', value: 'navigate' },
    ],
  },
  ...actionCommonFeedbackFields,
];

const widgetFields: PropertySchema<string>[] = [
  { key: 'props.title', label: 'Title', type: 'text' },
  { key: 'props.subtitle', label: 'Subtitle', type: 'text' },
  {
    key: 'widgetType',
    label: 'Widget type',
    type: 'select',
    options: [
      { label: 'Number card', value: 'number-card' },
      { label: 'Bar chart', value: 'bar-chart' },
      { label: 'Line chart', value: 'line-chart' },
      { label: 'Pie chart', value: 'pie-chart' },
      { label: 'Area chart', value: 'area-chart' },
      // E1 — full SharedChartFactory chart parity (the live WidgetRenderer renders
      // these via getChartComponent; the designer shows a representative preview).
      { label: 'Radar chart', value: 'radar' },
      { label: 'Scatter chart', value: 'scatter' },
      { label: 'Gauge', value: 'gauge' },
      { label: 'Funnel chart', value: 'funnel' },
      { label: 'Heatmap', value: 'heatmap' },
      { label: 'Treemap', value: 'treemap' },
      { label: 'Gantt', value: 'gantt' },
      { label: 'Pareto', value: 'pareto' },
      { label: 'Combo chart', value: 'combo' },
      { label: 'Progress', value: 'progress' },
      { label: 'Table', value: 'table' },
      { label: 'Markdown', value: 'markdown' },
    ],
  },
  {
    key: 'dataSource.type',
    label: 'Data source',
    type: 'select',
    options: [
      { label: 'Static preview', value: 'static' },
      { label: 'Query builder', value: 'query-builder' },
      { label: 'Named query', value: 'namedQuery' },
    ],
  },
  { key: 'dataSource.model', label: 'Model', type: 'model' },
  { key: 'dataSource.metric', label: 'Metric', type: 'text' },
  {
    key: 'dataSource.executionMode',
    label: 'Data execution',
    type: 'select',
    options: [
      { label: 'Run query JSON', value: 'live' },
      { label: 'Static preview', value: 'preview' },
    ],
  },
  { key: 'dataSource.query', label: 'Query builder JSON', type: 'json' },
  { key: 'dataSource.queryCode', label: 'Named query code', type: 'namedQuery' },
  { key: 'dataSource.parameters', label: 'Named query params JSON', type: 'json' },
  { key: 'dataSource.page', label: 'Page', type: 'number' },
  { key: 'dataSource.size', label: 'Page size', type: 'number' },
  { key: 'props.value', label: 'Preview value', type: 'text' },
  {
    key: 'props.format',
    label: 'Format',
    type: 'select',
    options: [
      { label: 'Plain', value: 'plain' },
      { label: 'Number', value: 'number' },
      { label: 'Currency', value: 'currency' },
      { label: 'Percent', value: 'percent' },
    ],
  },
  { key: 'props.emptyText', label: 'Empty text', type: 'text' },
  { key: 'props.errorText', label: 'Error text', type: 'text' },
  { key: 'props.drillDownTo', label: 'Drilldown route', type: 'text' },
  { key: 'props.thresholds', label: 'Thresholds JSON', type: 'json' },
  { key: 'props.series', label: 'Series JSON', type: 'json' },
  { key: 'props.columns', label: 'Columns JSON', type: 'json' },
  { key: 'props.rows', label: 'Rows JSON', type: 'json' },
  { key: 'props.markdown', label: 'Markdown', type: 'text' },
  { key: 'props.refreshInterval', label: 'Refresh seconds', type: 'number', min: 0 },
  { key: 'layout.x', label: 'X', type: 'number' },
  { key: 'layout.y', label: 'Y', type: 'number' },
  { key: 'layout.w', label: 'Width', type: 'number' },
  { key: 'layout.h', label: 'Height', type: 'number' },
];

// ── E2 batch: non-family display / layout / form / list blocks ──────────────
// Same contract as the workbench + display families above: every key below is a
// BARE top-level path (no props.*) when the platform renderer reads it there, and
// `props.*` only when it actually reads props. Each schema was verified against
// the renderer source under framework/meta/rendering/blocks/ — no invented fields.

// ChartBlockRenderer reads bare block.chartType / block.dataSource (string id) /
// block.chartConfig / block.visualization / block.linkage / block.drillDown /
// block.refreshInterval / block.title (props.* are legacy fallbacks). The chart
// itself is rendered by SharedChartFactory (28 registered types); the select lists
// the common ones — any unsupported value surfaces a clear "Unsupported chart type"
// message on the live page (never silent).
const chartFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  {
    key: 'chartType',
    label: 'Chart type',
    type: 'select',
    options: [
      { label: 'Bar', value: 'bar' },
      { label: 'Line', value: 'line' },
      { label: 'Pie', value: 'pie' },
      { label: 'Area', value: 'area' },
      { label: 'Scatter', value: 'scatter' },
      { label: 'Radar', value: 'radar' },
      { label: 'Gauge', value: 'gauge' },
      { label: 'Funnel', value: 'funnel' },
      { label: 'Heatmap', value: 'heatmap' },
      { label: 'Treemap', value: 'treemap' },
      { label: 'Gantt', value: 'gantt' },
      { label: 'Pareto', value: 'pareto' },
      { label: 'Combo', value: 'combo' },
      { label: 'Table', value: 'table' },
    ],
  },
  { key: 'dataSource', label: 'Data source', type: 'text' },
  // chartConfig: { xField, yField, height, … } — the renderer spreads it.
  { key: 'chartConfig', label: 'Chart config JSON', type: 'json' },
  // visualization: { stacked, smooth, … } — unified visualization props.
  { key: 'visualization', label: 'Visualization JSON', type: 'json' },
  { key: 'linkage', label: 'Linkage JSON', type: 'json' },
  { key: 'drillDown', label: 'Drilldown JSON', type: 'json' },
  { key: 'refreshInterval', label: 'Refresh seconds', type: 'number', min: 0 },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// RichTextBlockRenderer reads bare block.content (string or LocalizedText; HTML is
// sanitized before render). No other authorable props.
const richTextFields: PropertySchema<string>[] = [
  { key: 'content', label: 'Content (HTML)', type: 'text' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// DividerBlockRenderer reads bare block.title (optional label divider; no title =
// plain horizontal rule). Only authorable surface.
const dividerFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Label (optional)', type: 'text' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// ToolbarBlockRenderer / FormButtonsBlockRenderer both read bare block.buttons —
// an array of ButtonConfig { code, label|content, icon, variant|primary|danger,
// visibleWhen, disabled|disableWhen|enableWhen, events.onClick|action|commandCode|
// navigateTo|apiAction|handler }. Authored as JSON — the renderer iterates it.
const toolbarFields: PropertySchema<string>[] = [
  { key: 'buttons', label: 'Buttons JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];
const formButtonsFields: PropertySchema<string>[] = [
  { key: 'buttons', label: 'Buttons JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// FiltersBlockRenderer reads bare block.fields (array of filter FieldConfig, each
// { field, … } rendered via FieldRenderer) + block.onSearch / block.onReset
// (handler refs fired on the Search / Reset buttons).
const filtersFields: PropertySchema<string>[] = [
  { key: 'fields', label: 'Filter fields JSON', type: 'json' },
  { key: 'onSearch', label: 'On search handler', type: 'text' },
  { key: 'onReset', label: 'On reset handler', type: 'text' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// FormWizardBlockRenderer reads bare block.steps — array of { key, label,
// description?, blocks[] }. Authored as JSON; child blocks render per step.
const formWizardFields: PropertySchema<string>[] = [
  { key: 'steps', label: 'Steps JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// TraceGraphBlockRenderer reads bare block.dataSource (string id whose flat rows
// are mapped to nodes/edges) + block.mode ('consumption' | 'genealogy'; inferred
// from row fields when omitted). The live canvas renders on /p/; the designer shows
// a config-driven representative preview (avoids the @xyflow zero-height pitfall).
const traceGraphFields: PropertySchema<string>[] = [
  { key: 'dataSource', label: 'Data source', type: 'text' },
  {
    key: 'mode',
    label: 'Mode',
    type: 'select',
    options: [
      { label: 'Consumption (work-order → lot)', value: 'consumption' },
      { label: 'Genealogy (finished SN → component)', value: 'genealogy' },
    ],
  },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// SelectionInfoBlockRenderer reads bare block.title + the bound runtime-state key
// (block.selection.bind || block.bind || 'selectedRows'). The inspector exposes the
// simpler bare `bind` text path the renderer reads directly.
const selectionInfoFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'bind', label: 'Selection state key', type: 'text' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

// GerberViewerBlockRenderer reads bare block.title (or block.label) / block.dataSource
// (string id) / block.inspection (runtime value expr) / block.inspectionUrl /
// block.lineContext / block.lineInspectionField / block.empty ({ title }). The PCB
// board canvas + CPL inspection render on the live /p/ page (it fetches gerber
// artifacts with the auth token); the designer shows a representative preview.
const gerberViewerFields: PropertySchema<string>[] = [
  { key: 'title', label: 'Title', type: 'text' },
  { key: 'dataSource', label: 'Data source', type: 'text' },
  { key: 'inspection', label: 'Inspection expression', type: 'text' },
  { key: 'inspectionUrl', label: 'Inspection URL', type: 'text' },
  { key: 'lineContext', label: 'Line context expression', type: 'text' },
  { key: 'lineInspectionField', label: 'Line inspection field', type: 'text' },
  { key: 'empty', label: 'Empty state JSON', type: 'json' },
  { key: 'layout.span', label: 'Span', type: 'number', min: 1, max: 24 },
];

export function createDefaultInspectorSchemaRegistry(): InspectorSchemaRegistry {
  const registry = new InspectorSchemaRegistry(defaultFields);
  registry.registerAll({
    form: modelContainerFields,
    'form-section': formSectionFields,
    detail: modelContainerFields,
    'detail-section': formSectionFields,
    list: listFields,
    dashboard: dashboardFields,
    columns: columnsContainerFields,
    field: fieldFields,
    'filter-field': filterFieldFields,
    table: tableFields,
    column: columnFields,
    'sub-table': subTableFields,
    repeater: repeaterFields,
    subform: subformFields,
    'ai-fill-banner': aiFillBannerFields,
    'bpm-panel': bpmPanelFields,
    'activity-timeline': activityTimelineFields,
    'field-history': fieldHistoryFields,
    'metric-strip': metricStripFields,
    'status-banner': statusBannerFields,
    'workbench-action-bar': workbenchActionBarFields,
    'review-drawer': reviewDrawerFields,
    'evidence-panel': evidencePanelFields,
    'record-inspector': recordInspectorFields,
    'candidate-list': candidateListFields,
    'artifact-timeline': artifactTimelineFields,
    'stat-card': statCardFields,
    description: descriptionFields,
    'record-comments': recordCommentsFields,
    'embedded-list': embeddedListFields,
    // E2 batch: non-family display / layout / form / list blocks.
    chart: chartFields,
    'rich-text': richTextFields,
    divider: dividerFields,
    toolbar: toolbarFields,
    'form-buttons': formButtonsFields,
    filters: filtersFields,
    'form-wizard': formWizardFields,
    'trace-graph': traceGraphFields,
    'selection-info': selectionInfoFields,
    'gerber-viewer': gerberViewerFields,
    action: [...actionBaseFields, ...actionCommonFeedbackFields],
    widget: widgetFields,
  });
  registry.registerDynamic('field', getFieldInspectorFields);
  registry.registerDynamic('filter-field', getFieldInspectorFields);
  registry.registerDynamic('action', getActionInspectorFields);
  return registry;
}

export const defaultInspectorSchemaRegistry = createDefaultInspectorSchemaRegistry();

function getActionInspectorFields(block: DslBlockV3): PropertySchema<string>[] {
  const actionType = block.actionType || 'command';

  if (actionType === 'submit') return [...actionBaseFields, ...submitActionFields];
  if (actionType === 'create') return [...actionBaseFields, ...createActionFields];
  if (actionType === 'workflow') return [...actionBaseFields, ...workflowActionFields];
  if (actionType === 'navigate') return [...actionBaseFields, ...navigateActionFields];
  if (actionType === 'modal' || actionType === 'drawer') {
    return [...actionBaseFields, ...overlayActionFields];
  }

  return [...actionBaseFields, ...commandActionFields];
}

function getFieldInspectorFields(block: DslBlockV3): PropertySchema<string>[] {
  const component = getComponentName(block.props?.component);
  const insertionIndex = fieldFields.findIndex((field) => field.key === 'props.dataType');
  const componentFields =
    component === 'picker'
      ? pickerFieldFields
      : component === 'upload'
        ? uploadFieldFields
      : component === 'rich-text' || component === 'richtext'
        ? richTextFieldFields
        : [];

  const fields =
    insertionIndex >= 0
      ? [
          ...fieldFields.slice(0, insertionIndex),
          ...componentFields,
          ...fieldFields.slice(insertionIndex),
        ]
      : [...fieldFields, ...componentFields];

  if (block.blockType !== 'filter-field') return fields;

  return [
    ...fields,
    {
      key: 'props.operator',
      label: 'Operator',
      type: 'select',
      options: [
        { label: 'Equals', value: 'equals' },
        { label: 'Contains', value: 'contains' },
        { label: 'Greater than', value: 'gt' },
        { label: 'Less than', value: 'lt' },
        { label: 'Between', value: 'between' },
      ],
    },
  ];
}

function getComponentName(value: unknown): string {
  return typeof value === 'string' ? value.toLowerCase() : '';
}
