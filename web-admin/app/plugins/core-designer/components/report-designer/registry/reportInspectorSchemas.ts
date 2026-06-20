/**
 * Report inspector schemas (B1 Phase 1 PR-1b).
 *
 * PropertySchema field definitions for the 8 report-* block types, registered in
 * a DEDICATED report InspectorSchemaRegistry instance (Layer-0 class reused; not
 * the shared default registry → no pollution of page/form/list inspectors).
 *
 * Field keys mirror exactly where ReportDslCompatibilityAdapter places each prop
 * (`props.*` for report-specific props, `dataSource.ref` for the page-level data
 * source reference) so an adapter-produced block round-trips through the inspector.
 */
import type { PropertySchema } from '~/shared/designer';
import { InspectorSchemaRegistry } from '../../unified-designer/registry/InspectorSchemaRegistry';

const titleField: PropertySchema<string> = { key: 'title', label: 'Title', type: 'text' };
const dataSourceRefField: PropertySchema<string> = {
  key: 'dataSource.ref',
  label: 'Data source',
  type: 'text',
};
const aggregationField: PropertySchema<string> = {
  key: 'props.aggregation',
  label: 'Aggregation',
  type: 'select',
  options: [
    { label: 'Sum', value: 'sum' },
    { label: 'Average', value: 'avg' },
    { label: 'Count', value: 'count' },
    { label: 'Min', value: 'min' },
    { label: 'Max', value: 'max' },
  ],
};
const alignField: PropertySchema<string> = {
  key: 'props.align',
  label: 'Align',
  type: 'select',
  options: [
    { label: 'Left', value: 'left' },
    { label: 'Center', value: 'center' },
    { label: 'Right', value: 'right' },
  ],
};

const reportTableFields: PropertySchema<string>[] = [
  titleField,
  dataSourceRefField,
  { key: 'props.columns', label: 'Columns JSON', type: 'json' },
  { key: 'props.showHeader', label: 'Show header', type: 'boolean' },
  { key: 'props.stripe', label: 'Striped rows', type: 'boolean' },
  { key: 'props.border', label: 'Bordered', type: 'boolean' },
  { key: 'props.summary', label: 'Summary JSON', type: 'json' },
];

const reportGroupedTableFields: PropertySchema<string>[] = [
  titleField,
  dataSourceRefField,
  { key: 'props.groupByField', label: 'Group by field', type: 'text' },
  { key: 'props.columns', label: 'Columns JSON', type: 'json' },
  { key: 'props.showHeader', label: 'Show header', type: 'boolean' },
  { key: 'props.border', label: 'Bordered', type: 'boolean' },
  { key: 'props.groupSubtotal', label: 'Group subtotal JSON', type: 'json' },
  { key: 'props.grandTotal', label: 'Grand total JSON', type: 'json' },
];

const reportStatCardFields: PropertySchema<string>[] = [
  titleField,
  dataSourceRefField,
  { key: 'props.valueField', label: 'Value field', type: 'text' },
  aggregationField,
  { key: 'props.label', label: 'Label', type: 'text' },
  { key: 'props.format', label: 'Format', type: 'text' },
  { key: 'props.color', label: 'Color token', type: 'text' },
];

const reportRichTextFields: PropertySchema<string>[] = [
  titleField,
  { key: 'props.content', label: 'Content (HTML)', type: 'text' },
  alignField,
  { key: 'props.style', label: 'Style JSON', type: 'json' },
];

const reportCrossTabFields: PropertySchema<string>[] = [
  titleField,
  dataSourceRefField,
  { key: 'props.rowField', label: 'Row field', type: 'text' },
  { key: 'props.columnField', label: 'Column field', type: 'text' },
  { key: 'props.valueField', label: 'Value field', type: 'text' },
  aggregationField,
  { key: 'props.format', label: 'Format', type: 'text' },
  { key: 'props.showRowTotal', label: 'Show row total', type: 'boolean' },
  { key: 'props.showColumnTotal', label: 'Show column total', type: 'boolean' },
];

// The chart is canonically represented as a renderer-agnostic ChartSpec (B2a),
// produced by the adapter and edited here as JSON in Phase 1 (a richer
// dimension/measure chart inspector is later, B2b/B2d).
const reportChartFields: PropertySchema<string>[] = [
  titleField,
  dataSourceRefField,
  { key: 'props.chartSpec', label: 'Chart spec (ChartSpec) JSON', type: 'json' },
];

const reportBarcodeFields: PropertySchema<string>[] = [
  titleField,
  dataSourceRefField,
  { key: 'props.field', label: 'Value field', type: 'text' },
  { key: 'props.staticValue', label: 'Static value', type: 'text' },
  {
    key: 'props.format',
    label: 'Barcode format',
    type: 'select',
    options: [
      { label: 'CODE128', value: 'code128' },
      { label: 'CODE39', value: 'code39' },
      { label: 'EAN-13', value: 'ean13' },
      { label: 'EAN-8', value: 'ean8' },
      { label: 'UPC', value: 'upc' },
      { label: 'ITF-14', value: 'itf14' },
    ],
  },
  { key: 'props.width', label: 'Width', type: 'number' },
  { key: 'props.height', label: 'Height', type: 'number' },
  { key: 'props.displayValue', label: 'Display value', type: 'boolean' },
  { key: 'props.fontSize', label: 'Font size', type: 'number' },
];

const reportWatermarkFields: PropertySchema<string>[] = [
  titleField,
  { key: 'props.text', label: 'Text', type: 'text' },
  { key: 'props.rotation', label: 'Rotation (deg)', type: 'number' },
  { key: 'props.opacity', label: 'Opacity', type: 'number', min: 0, max: 1 },
  { key: 'props.fontSize', label: 'Font size', type: 'number' },
  { key: 'props.color', label: 'Color token', type: 'text' },
  { key: 'props.repeat', label: 'Repeat', type: 'boolean' },
];

export const REPORT_INSPECTOR_FIELDS: Record<string, PropertySchema<string>[]> = {
  'report-table': reportTableFields,
  'report-grouped-table': reportGroupedTableFields,
  'report-stat-card': reportStatCardFields,
  'report-rich-text': reportRichTextFields,
  'report-cross-tab': reportCrossTabFields,
  'report-chart': reportChartFields,
  'report-barcode': reportBarcodeFields,
  'report-watermark': reportWatermarkFields,
};

export function createReportInspectorSchemaRegistry(): InspectorSchemaRegistry {
  const registry = new InspectorSchemaRegistry([titleField]);
  registry.registerAll(REPORT_INSPECTOR_FIELDS);
  return registry;
}

export const reportInspectorSchemaRegistry = createReportInspectorSchemaRegistry();
