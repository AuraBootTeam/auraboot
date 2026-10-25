import { z } from 'zod';
import type { ReportDsl } from '../types';

export const REPORT_TEMPLATE_FORMAT = 'auraboot.report-template';
export const REPORT_TEMPLATE_MAX_BYTES = 2 * 1024 * 1024;

const nonempty = z.string().trim().min(1);
const finite = z.number().finite();
const aggregation = z.enum(['sum', 'avg', 'count', 'min', 'max']);
const align = z.enum(['left', 'center', 'right']);
const column = z
  .object({
    field: nonempty,
    label: z.string().optional(),
    width: finite.optional(),
    align: align.optional(),
    format: z.string().optional(),
  })
  .strict();
const summary = z
  .object({
    enabled: z.boolean(),
    label: z.string().optional(),
    columns: z.array(
      z.object({ field: nonempty, aggregation, format: z.string().optional() }).strict(),
    ),
  })
  .strict();
const base = { id: nonempty, title: z.string().optional() };
const table = {
  ...base,
  dataSource: nonempty,
  columns: z.array(column),
  showHeader: z.boolean().optional(),
  border: z.boolean().optional(),
};
const block = z.discriminatedUnion('blockType', [
  z
    .object({
      ...table,
      blockType: z.literal('table'),
      stripe: z.boolean().optional(),
      summary: summary.optional(),
    })
    .strict(),
  z
    .object({
      ...table,
      blockType: z.literal('grouped-table'),
      groupByField: nonempty,
      groupSubtotal: summary.optional(),
      grandTotal: summary.optional(),
    })
    .strict(),
  z
    .object({
      ...base,
      blockType: z.literal('stat-card'),
      dataSource: nonempty,
      valueField: nonempty,
      aggregation,
      label: z.string(),
      format: z.string().optional(),
      color: z.string().optional(),
    })
    .strict(),
  z
    .object({
      id: nonempty,
      title: z.string().optional(),
      blockType: z.literal('rich-text'),
      content: z.string(),
      align: align.optional(),
      style: z
        .object({
          fontSize: finite.optional(),
          fontWeight: z.enum(['normal', 'bold']).optional(),
          color: z.string().optional(),
        })
        .strict()
        .optional(),
    })
    .strict(),
  z
    .object({
      ...base,
      blockType: z.literal('cross-tab'),
      dataSource: nonempty,
      rowField: nonempty,
      rowLabel: z.string().max(120).optional(),
      columnField: nonempty,
      columnLabel: z.string().max(120).optional(),
      valueField: nonempty,
      aggregation,
      format: z.string().optional(),
      totalLabel: z.string().max(120).optional(),
      showRowTotal: z.boolean().optional(),
      showColumnTotal: z.boolean().optional(),
    })
    .strict(),
  z
    .object({
      ...base,
      blockType: z.literal('chart'),
      dataSource: nonempty,
      chartType: z.enum(['bar', 'horizontal-bar', 'pie']),
      categoryField: nonempty,
      valueField: nonempty,
      aggregation: aggregation.optional(),
      width: finite.optional(),
      height: finite.optional(),
      colors: z.array(z.string()).optional(),
    })
    .strict(),
  z
    .object({
      ...base,
      blockType: z.literal('barcode'),
      dataSource: nonempty.optional(),
      field: z.string().optional(),
      staticValue: z.string().optional(),
      format: z.enum(['code128', 'code39', 'ean13', 'ean8', 'upc', 'itf14']),
      width: finite.optional(),
      height: finite.optional(),
      displayValue: z.boolean().optional(),
      fontSize: finite.optional(),
    })
    .strict(),
  z
    .object({
      id: nonempty,
      blockType: z.literal('watermark'),
      text: z.string(),
      rotation: finite.optional(),
      opacity: finite.min(0).max(1).optional(),
      fontSize: finite.optional(),
      color: z.string().optional(),
      repeat: z.boolean().optional(),
    })
    .strict(),
]);
const band = z
  .object({
    height: finite.nonnegative(),
    elements: z.array(
      z
        .object({
          type: z.enum(['text', 'image', 'page-number', 'date']),
          content: z.string().optional(),
          align: align.optional(),
          style: z
            .object({
              fontSize: finite.optional(),
              fontWeight: z.enum(['normal', 'bold']).optional(),
              color: z.string().optional(),
              fontFamily: z.string().optional(),
            })
            .strict()
            .optional(),
        })
        .strict(),
    ),
  })
  .strict();
const source = z
  .object({
    type: z.enum(['model', 'namedQuery', 'api', 'static', 'aggregate']),
    modelCode: nonempty.optional(),
    queryCode: nonempty.optional(),
    params: z.record(z.string(), z.union([z.string(), finite, z.boolean(), z.null()])).optional(),
    url: nonempty.optional(),
    aggregateQuery: z.record(z.string(), z.unknown()).optional(),
    data: z.array(z.record(z.string(), z.unknown())).optional(),
    filters: z
      .array(
        z
          .object({
            field: nonempty,
            operator: nonempty,
            value: z.union([z.string(), finite]).optional(),
            values: z.array(z.string()).optional(),
          })
          .strict(),
      )
      .optional(),
    sortBy: z
      .array(z.object({ field: nonempty, order: z.enum(['asc', 'desc']) }).strict())
      .optional(),
  })
  .strict()
  .superRefine((value, ctx) => {
    const required = {
      model: 'modelCode',
      namedQuery: 'queryCode',
      api: 'url',
      static: 'data',
      aggregate: 'aggregateQuery',
    } as const;
    if (value[required[value.type]] === undefined)
      ctx.addIssue({ code: 'custom', message: 'Missing data-source configuration' });
  });
const report = z
  .object({
    $schema: z.literal('auraboot://schemas/report/v1'),
    version: z.literal('1.0.0'),
    title: nonempty,
    description: z.string().optional(),
    page: z
      .object({
        size: z.enum(['A4', 'A3', 'letter', 'legal']),
        orientation: z.enum(['portrait', 'landscape']),
        margin: z
          .object({
            top: finite.nonnegative(),
            right: finite.nonnegative(),
            bottom: finite.nonnegative(),
            left: finite.nonnegative(),
          })
          .strict(),
      })
      .strict(),
    dataSources: z.record(z.string(), source),
    header: band.optional(),
    footer: band.optional(),
    body: z.array(block),
    parameters: z
      .array(
        z
          .object({
            name: nonempty,
            type: z.enum(['text', 'number', 'date', 'date-range', 'select']),
            label: z.string(),
            required: z.boolean().optional(),
            defaultValue: z.string().optional(),
            options: z
              .array(z.object({ label: z.string(), value: z.string() }).strict())
              .optional(),
            bindTo: z
              .object({ dataSource: nonempty, field: nonempty, operator: nonempty })
              .strict()
              .optional(),
          })
          .strict(),
      )
      .optional(),
  })
  .strict()
  .superRefine((value, ctx) => {
    const ids = new Set<string>();
    for (const item of value.body) {
      if (ids.has(item.id)) ctx.addIssue({ code: 'custom', message: 'Duplicate block id' });
      ids.add(item.id);
      if (
        'dataSource' in item &&
        item.dataSource &&
        !Object.hasOwn(value.dataSources, item.dataSource)
      )
        ctx.addIssue({ code: 'custom', message: 'Unknown block data source' });
    }
    for (const parameter of value.parameters ?? []) {
      if (parameter.bindTo && !Object.hasOwn(value.dataSources, parameter.bindTo.dataSource))
        ctx.addIssue({ code: 'custom', message: 'Unknown parameter data source' });
    }
  });
const template = z
  .object({ format: z.literal(REPORT_TEMPLATE_FORMAT), version: z.literal(1), dsl: report })
  .strict();

/** Portable definitions only; persistent identity and result rows are never inferred. */
export function serializeReportTemplate(dsl: ReportDsl): string {
  const raw = JSON.stringify(template.parse({ format: REPORT_TEMPLATE_FORMAT, version: 1, dsl }), null, 2);
  if (new TextEncoder().encode(raw).byteLength > REPORT_TEMPLATE_MAX_BYTES)
    throw new Error('Report template is too large');
  return raw;
}

export function parseReportTemplate(raw: string): ReportDsl {
  if (new TextEncoder().encode(raw).byteLength > REPORT_TEMPLATE_MAX_BYTES)
    throw new Error('Report template is too large');
  return template.parse(JSON.parse(raw)).dsl as ReportDsl;
}
