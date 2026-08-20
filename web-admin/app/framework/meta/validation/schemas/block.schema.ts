import { z } from 'zod';
import { localizedTextSchema } from './localized-text.schema';
import { fieldSchema } from './field.schema';
import { columnSchema } from './column.schema';
import { buttonSchema } from './button.schema';
import { dataSourceConfigSchema } from './data-source.schema';

/**
 * Block types aligned with BlockRenderer.tsx switch-case.
 * This is the static fallback list; at runtime, prefer reading from
 * the DSL registry via useDslRegistry().blockTypes.
 */
export const BLOCK_TYPES = [
  'form',
  'form-section',
  'detail-section',
  'form-buttons',
  'form-wizard',
  'table',
  'filters',
  'toolbar',
  'action',
  'description',
  'text',
  'chart',
  'chart-card',
  'tabs',
  'sub-table',
  'monthly-grid',
  'stat-card',
  'selection-info',
  'metric-strip',
  'stage-rail',
  'record-inspector',
  'candidate-list',
  'workbench-action-bar',
  'evidence-panel',
  'gerber-viewer',
  'artifact-timeline',
  'review-drawer',
  'status-banner',
  'code-snippet',
  'conversation-panel',
  'custom',
] as const;

export const blockTypeEnum = z.enum(BLOCK_TYPES);

const blockLayoutConfigSchema = z.object({
  col: z.number().optional(),
  colSpan: z.number().optional(),
  row: z.number().optional(),
  rowSpan: z.number().optional(),
  order: z.number().optional(),
  columns: z.number().optional(),
  colGap: z.number().optional(),
  rowGap: z.number().optional(),
});

const selectionConfigSchema = z
  .object({
    mode: z.enum(['single', 'multiple']),
    bind: z.string(),
    defaultFirst: z.boolean().optional(),
    keyField: z.string().min(1).optional(),
    presentation: z.enum(['table', 'grouped-radio']).optional(),
    exclusiveBy: z.string().min(1).optional(),
    optionLabelField: z.string().min(1).optional(),
    recommendedField: z.string().min(1).optional(),
    safeField: z.string().min(1).optional(),
    detailBind: z.string().optional(),
    idsBind: z.string().optional(),
    idField: z.string().optional(),
  })
  .superRefine((selection, context) => {
    if (selection.exclusiveBy && selection.mode !== 'multiple') {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['exclusiveBy'],
        message: 'exclusiveBy requires selection.mode=multiple',
      });
    }
    if (selection.presentation === 'grouped-radio') {
      if (selection.mode !== 'multiple') {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['presentation'],
          message: 'grouped-radio requires selection.mode=multiple',
        });
      }
      if (!selection.exclusiveBy) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['exclusiveBy'],
          message: 'grouped-radio requires exclusiveBy',
        });
      }
      if (!selection.optionLabelField) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['optionLabelField'],
          message: 'grouped-radio requires optionLabelField',
        });
      }
      if (
        selection.exclusiveBy &&
        selection.optionLabelField &&
        selection.exclusiveBy === selection.optionLabelField
      ) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['optionLabelField'],
          message: 'optionLabelField must differ from exclusiveBy',
        });
      }
      if (selection.defaultFirst) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['defaultFirst'],
          message: 'grouped-radio does not allow an implicit default selection',
        });
      }
      if (Boolean(selection.recommendedField) !== Boolean(selection.safeField)) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: [selection.recommendedField ? 'safeField' : 'recommendedField'],
          message: 'grouped-radio safe defaults require both recommendedField and safeField',
        });
      }
      if (
        selection.recommendedField &&
        selection.safeField &&
        selection.recommendedField === selection.safeField
      ) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['safeField'],
          message: 'safeField must differ from recommendedField',
        });
      }
    } else {
      for (const field of ['optionLabelField', 'recommendedField', 'safeField'] as const) {
        if (!selection[field]) continue;
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: [field],
          message: `${field} requires presentation=grouped-radio`,
        });
      }
    }
  });

const paginationConfigSchema = z.object({
  pageSize: z.number().optional(),
  pageSizeOptions: z.array(z.number()).optional(),
  showTotal: z.boolean().optional(),
  showSizeChanger: z.boolean().optional(),
  showQuickJumper: z.boolean().optional(),
});

const tableConfigSchema = z
  .object({
    rowKey: z.string().min(1).optional(),
    dataSource: z.string().optional(),
    maxHeight: z.union([z.number(), z.string()]).optional(),
    density: z.enum(['default', 'compact']).optional(),
    pagination: paginationConfigSchema.optional(),
    selection: selectionConfigSchema.optional(),
    treeConfig: z
      .object({
        parentField: z.string(),
        maxDepth: z.number().optional(),
        defaultExpanded: z.boolean().optional(),
      })
      .optional(),
    columns: z.array(columnSchema),
  })
  .superRefine((table, context) => {
    if (
      table.selection?.presentation === 'grouped-radio' &&
      !table.rowKey &&
      !table.selection.keyField
    ) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['selection', 'keyField'],
        message: 'grouped-radio requires table.rowKey or selection.keyField for stable identity',
      });
    }
    const optionLabelField = table.selection?.optionLabelField;
    if (optionLabelField && !table.columns.some((column) => column.field === optionLabelField)) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['selection', 'optionLabelField'],
        message: 'optionLabelField must reference a configured table column',
      });
    }
  });

const tabFilterExpressionSchema = z.object({
  field: z.string(),
  operator: z.enum(['EQ', 'NE', 'IN', 'not_in']),
  value: z.any(),
});

const listTabConfigSchema = z
  .object({
    key: z.string(),
    label: localizedTextSchema,
    filter: tabFilterExpressionSchema.nullable().optional(),
  })
  .passthrough();

const detailTabConfigSchema = z
  .object({
    key: z.string(),
    label: localizedTextSchema,
    blocks: z.array(z.lazy(() => blockSchema)),
    filter: tabFilterExpressionSchema.nullable().optional(),
  })
  .passthrough();

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
    dataSource: z.union([z.string(), dataSourceConfigSchema]).optional(),
    tabs: z.union([z.array(listTabConfigSchema), z.array(detailTabConfigSchema)]).optional(),
    subTable: subTableConfigSchema.optional(),
    defaultSort: defaultSortSchema.optional(),
    summary: summaryConfigSchema.optional(),
    gap: z.union([z.string(), z.number()]).optional(),
    component: z.string().optional(),
  })
  .passthrough();

export type BlockSchema = z.infer<typeof blockSchema>;
