import { z } from 'zod';

export const dataSourceConfigSchema = z
  .object({
    id: z.string().optional(),
    type: z.enum(['api', 'static', 'namedQuery']).optional(),
    endpoint: z.string().optional(),
    method: z.enum(['get', 'post', 'put', 'delete']).optional(),
    params: z.union([z.string(), z.record(z.string(), z.any())]).optional(),
    body: z.union([z.string(), z.record(z.string(), z.any())]).optional(),
    autoFetch: z.boolean().optional(),
    pagination: z.boolean().optional(),
    adaptor: z.string().optional(),
    valueField: z.string().optional(),
    labelField: z.string().optional(),
    data: z.array(z.any()).optional(),
    dependOn: z.array(z.string()).optional(),
    queryCode: z.string().optional(),
    searchField: z.string().optional(),
    maxItems: z.number().optional(),
  })
  .passthrough();

export const pageDataSourceSchema = z
  .object({
    type: z.enum(['table', 'namedQuery', 'api']),
    queryCode: z.string().optional(),
    version: z.number().nullable().optional(),
    endpoint: z.string().optional(),
    method: z.enum(['get', 'post']).optional(),
    pagination: z.boolean().optional(),
  })
  .passthrough();

export type DataSourceConfigSchema = z.infer<typeof dataSourceConfigSchema>;
