import { z } from 'zod';
import { localizedTextSchema } from './localized-text.schema';
import { buttonSchema } from './button.schema';

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
    valueType: z
      .enum(['text', 'date', 'datetime', 'currency', 'tag', 'progress', 'image'])
      .optional(),
    render: z.string().optional(),
    isActionColumn: z.boolean().optional(),
    buttons: z.array(buttonSchema).optional(),
    tagMap: z.record(z.string(), z.object({ label: z.string(), color: z.string() })).optional(),
    dictCode: z.string().optional(),
    currencyCode: z.string().optional(),
  })
  .passthrough();

export type ColumnSchema = z.infer<typeof columnSchema>;
