import { z } from 'zod';
import { localizedTextSchema } from './localized-text.schema';
import { fieldSchema } from './field.schema';
import { columnSchema } from './column.schema';
import { buttonSchema } from './button.schema';

/**
 * Block types aligned with BlockRenderer.tsx switch-case.
 * This is the static fallback list; at runtime, prefer reading from
 * the DSL registry via useDslRegistry().blockTypes.
 */
export const BLOCK_TYPES = [
  'form',
  'form-section',
  'form-buttons',
  'form-wizard',
  'table',
  'filters',
  'toolbar',
  'action',
  'description',
  'chart',
  'tabs',
  'sub-table',
  'monthly-grid',
  'stat-card',
  'custom',
] as const;

export const blockTypeEnum = z.enum(BLOCK_TYPES);

const blockLayoutConfigSchema = z.object({
  colSpan: z.number().optional(),
  rowSpan: z.number().optional(),
  columns: z.number().optional(),
  colGap: z.number().optional(),
  rowGap: z.number().optional(),
});

const selectionConfigSchema = z.object({
  mode: z.enum(['single', 'multiple']),
  bind: z.string(),
});

const paginationConfigSchema = z.object({
  pageSize: z.number().optional(),
  pageSizeOptions: z.array(z.number()).optional(),
  showTotal: z.boolean().optional(),
  showSizeChanger: z.boolean().optional(),
  showQuickJumper: z.boolean().optional(),
});

const tableConfigSchema = z.object({
  rowKey: z.string(),
  dataSource: z.string(),
  pagination: paginationConfigSchema.optional(),
  selection: selectionConfigSchema.optional(),
  columns: z.array(columnSchema),
});

const tabFilterExpressionSchema = z.object({
  field: z.string(),
  operator: z.enum(['EQ', 'NE', 'IN', 'not_in']),
  value: z.any(),
});

const listTabConfigSchema = z.object({
  key: z.string(),
  label: localizedTextSchema,
  filter: tabFilterExpressionSchema.nullable(),
});

const detailTabConfigSchema = z.object({
  key: z.string(),
  label: localizedTextSchema,
  blocks: z.array(z.lazy(() => blockSchema)),
});

const defaultSortSchema = z.object({
  field: z.string(),
  order: z.enum(['asc', 'desc']),
});

const summaryFieldSchema = z.object({
  field: z.string(),
  aggregation: z.enum(['sum', 'avg', 'count', 'min', 'max']),
  label: localizedTextSchema.optional(),
});

const summaryConfigSchema = z.object({
  position: z.enum(['top', 'bottom']).optional(),
  fields: z.array(summaryFieldSchema),
});

const subTableConfigSchema = z.object({
  childModel: z.string(),
  parentField: z.string(),
  readOnly: z.boolean().optional(),
  columns: z.array(columnSchema),
  actions: z.array(buttonSchema).optional(),
  summary: summaryConfigSchema.optional(),
  resolveVia: z
    .object({
      model: z.string(),
      parentField: z.string(),
      filterField: z.string(),
      filterValue: z.string(),
    })
    .optional(),
  addCommandCode: z.string().optional(),
});

export const blockSchema: z.ZodType<any> = z
  .object({
    id: z.string(),
    blockType: z.string(), // validated separately for better messages
    title: localizedTextSchema.optional(),
    layout: blockLayoutConfigSchema.optional(),
    visibleWhen: z.string().optional(),
    className: z.string().optional(),
    fields: z.array(fieldSchema).optional(),
    buttons: z.array(buttonSchema).optional(),
    table: tableConfigSchema.optional(),
    columns: z.union([z.number(), z.array(columnSchema)]).optional(),
    rowActions: z.array(buttonSchema).optional(),
    dataSource: z.string().optional(),
    tabs: z.union([z.array(listTabConfigSchema), z.array(detailTabConfigSchema)]).optional(),
    subTable: subTableConfigSchema.optional(),
    defaultSort: defaultSortSchema.optional(),
    summary: summaryConfigSchema.optional(),
    gap: z.union([z.string(), z.number()]).optional(),
    component: z.string().optional(),
  })
  .passthrough();

export type BlockSchema = z.infer<typeof blockSchema>;
