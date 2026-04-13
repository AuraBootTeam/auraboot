import { z } from 'zod';
import { localizedTextSchema } from './localized-text.schema';
import { blockSchema } from './block.schema';
import { dataSourceConfigSchema, pageDataSourceSchema } from './data-source.schema';
import { handlerConfigSchema } from './handler.schema';

/** Kind values — includes Detail + Dashboard missing from old validator */
export const KIND_VALUES = ['page', 'list', 'form', 'detail', 'page_layout', 'dashboard'] as const;

export const kindEnum = z.enum(KIND_VALUES);

const layoutConfigSchema = z.object({
  type: z.enum(['grid', 'stack']).optional(),
  cols: z.number().optional(),
  colGap: z.number().optional(),
  rowGap: z.number().optional(),
  gap: z.number().optional(),
});

const eventConfigSchema = z.object({
  handler: z.string(),
  args: z.record(z.string(), z.any()).optional(),
});

const themeConfigSchema = z.object({
  tokens: z.record(z.string(), z.string()).optional(),
});

export const unifiedSchemaSchema = z
  .object({
    kind: kindEnum,
    version: z.string().regex(/^\d+\.\d+\.\d+$/, 'Version must be semver format (e.g. 1.0.0)'),
    schemaVersion: z.number().int().positive().optional(),
    id: z.string().min(1, 'Schema id is required'),
    title: localizedTextSchema,
    description: localizedTextSchema.optional(),
    profile: z.string().optional(),
    modelCode: z.string().optional(),
    dataSource: pageDataSourceSchema.optional(),
    stateBinding: z.record(z.string(), z.string()).optional(),
    layout: layoutConfigSchema,
    blocks: z.array(blockSchema),
    dataSources: z.record(z.string(), dataSourceConfigSchema).optional(),
    handlers: z.record(z.string(), handlerConfigSchema).optional(),
    events: z.record(z.string(), eventConfigSchema).optional(),
    theme: themeConfigSchema.optional(),
    components: z.record(z.string(), z.string()).optional(),
    state: z.record(z.string(), z.any()).optional(),
    linkageRules: z.array(z.any()).optional(),
  })
  .passthrough();

export type UnifiedSchemaSchema = z.infer<typeof unifiedSchemaSchema>;
