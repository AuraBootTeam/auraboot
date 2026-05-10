import { z } from 'zod';
import { localizedTextSchema } from './localized-text.schema';

export const apiActionSchema = z.object({
  endpoint: z.string(),
  method: z.enum(['post', 'put', 'delete', 'patch']).optional(),
  successMessage: localizedTextSchema.optional(),
});

const actionDefSchema = z
  .object({
    type: z.string().min(1),
  })
  .passthrough();

export const buttonSchema = z
  .object({
    code: z.string().min(1),
    action: z.union([z.string(), actionDefSchema]).optional(),
    content: localizedTextSchema.optional(),
    label: localizedTextSchema.optional(),
    primary: z.boolean().optional(),
    danger: z.boolean().optional(),
    variant: z.enum(['default', 'primary', 'danger']).optional(),
    icon: z.string().optional(),
    visibleWhen: z.string().optional(),
    enableWhen: z.string().optional(),
    disableWhen: z.string().optional(),
    disabled: z.boolean().optional(),
    handler: z.string().optional(),
    events: z
      .record(
        z.string(),
        z.object({
          handler: z.string(),
          args: z.record(z.string(), z.any()).optional(),
        }),
      )
      .optional(),
    commandCode: z.string().optional(),
    navigateTo: z.string().optional(),
    confirmMessageKey: z.string().optional(),
    permissionCode: z.string().optional(),
    apiAction: apiActionSchema.optional(),
  })
  .passthrough();

export type ButtonSchema = z.infer<typeof buttonSchema>;
