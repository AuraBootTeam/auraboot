import { z } from 'zod';
import { localizedTextSchema } from './localized-text.schema';

const validationRuleSchema = z.object({
  type: z.string(),
  message: localizedTextSchema,
  pattern: z.string().optional(),
  min: z.number().optional(),
  max: z.number().optional(),
});

const eventConfigSchema = z.object({
  handler: z.string(),
  args: z.record(z.string(), z.any()).optional(),
});

const blockLayoutConfigSchema = z.object({
  colSpan: z.number().optional(),
  rowSpan: z.number().optional(),
  columns: z.number().optional(),
  colGap: z.number().optional(),
  rowGap: z.number().optional(),
});

export const fieldSchema = z
  .object({
    field: z.string().min(1),
    label: localizedTextSchema.optional(),
    component: z.string().optional(),
    readOnly: z.boolean().optional(),
    layout: blockLayoutConfigSchema.optional(),
    props: z.record(z.string(), z.any()).optional(),
    validation: z.array(validationRuleSchema).optional(),
    dataSource: z.union([z.string(), z.object({}).passthrough()]).optional(),
    dependOn: z.array(z.string()).optional(),
    optionsWhen: z.string().optional(),
    visibleWhen: z.string().optional(),
    enableWhen: z.string().optional(),
    disableWhen: z.string().optional(),
    readOnlyWhen: z.string().optional(),
    valueWhen: z.string().optional(),
    onChangeSource: z.string().optional(),
    autoFetch: z.boolean().optional(),
    events: z.record(z.string(), eventConfigSchema).optional(),
    span: z.number().optional(),
    dictCode: z.string().optional(),
  })
  .passthrough();

export type FieldSchema = z.infer<typeof fieldSchema>;
