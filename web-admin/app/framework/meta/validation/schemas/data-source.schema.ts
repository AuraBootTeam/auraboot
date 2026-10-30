import { validatePaginationRequest } from '../../schemas/paginationRequest';
import { z } from 'zod';

export const paginationRequestSchema = z
  .object({
    pageNumPath: z.array(z.string()).min(1).max(8),
    pageSizePath: z.array(z.string()).min(1).max(8),
  })
  .strict()
  .superRefine((value, context) => {
    try {
      validatePaginationRequest(value);
    } catch (error) {
      context.addIssue({ code: 'custom', message: String(error) });
    }
  });

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
    paginationRequest: paginationRequestSchema.optional(),
    adaptor: z.string().optional(),
    dataPath: z.string().optional(),
    rowIdentityField: z.string().optional(),
    rowProjection: z
      .record(
        z.string(),
        z.union([
          z.string(),
          z.array(z.string()),
          z
            .object({
              paths: z.union([z.string(), z.array(z.string())]),
              optional: z.boolean().optional(),
              omitWhen: z.array(z.enum(['undefined', 'null', 'empty'])).optional(),
            })
            .strict(),
        ]),
      )
      .optional(),
    valueField: z.string().optional(),
    labelField: z.string().optional(),
    data: z.array(z.any()).optional(),
    dependOn: z.array(z.string()).optional(),
    queryCode: z.string().optional(),
    searchField: z.string().optional(),
    maxItems: z.number().optional(),
  })
  .passthrough()
  .superRefine((config, context) => {
    if ((!config.type || config.type === 'api') && config.body !== undefined)
      context.addIssue({
        code: 'custom',
        message: 'Data source body is unsupported; use explicit params',
      });
    if (config.paginationRequest && config.type && config.type !== 'api')
      context.addIssue({ code: 'custom', message: 'paginationRequest requires an API source' });
  });

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
