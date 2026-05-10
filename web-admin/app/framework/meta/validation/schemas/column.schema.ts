import { z } from 'zod';
import { localizedTextSchema } from './localized-text.schema';
import { buttonSchema } from './button.schema';

export const columnValueTypes = [
  'text',
  'boolean',
  'date',
  'datetime',
  'time',
  'currency',
  'tag',
  'progress',
  'image',
  'user_identity',
  'reference',
  'button',
  'url',
  'email',
  'color',
  'link',
] as const;

export const columnSchema = z
  .object({
    field: z.string().min(1),
    label: localizedTextSchema.optional(),
    align: z.enum(['left', 'center', 'right']).optional(),
    width: z.union([z.number(), z.string()]).optional(),
    ellipsis: z.boolean().optional(),
    fixed: z.enum(['left', 'right']).optional(),
    format: z.string().optional(),
    readOnly: z.boolean().optional(),
    sortable: z.boolean().optional(),
    sorter: z.string().optional(),
    sorterKey: z.string().optional(),
    filters: z.array(z.object({ text: z.string(), value: z.any() })).optional(),
    filterMultiple: z.boolean().optional(),
    valueType: z.enum(columnValueTypes).optional(),
    cellRenderer: z.string().min(1).optional(),
    render: z.union([z.string(), z.record(z.string(), z.any())]).optional(),
    isActionColumn: z.boolean().optional(),
    buttons: z.array(buttonSchema).optional(),
    tagMap: z
      .record(z.string(), z.object({ label: localizedTextSchema, color: z.string() }))
      .optional(),
    dictCode: z.string().optional(),
    currencyCode: z.string().optional(),
  })
  .passthrough();

export type ColumnSchema = z.infer<typeof columnSchema>;
