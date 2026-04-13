import { z } from 'zod';

const flowStepSchema = z
  .object({
    id: z.string().optional(),
    type: z.enum(['if', 'loop', 'action']).optional(),
    action: z.string().optional(),
    target: z.string().optional(),
    args: z.record(z.string(), z.any()).optional(),
    condition: z.string().optional(),
    trueNext: z.string().optional(),
    falseNext: z.string().optional(),
    next: z.string().optional(),
    method: z.string().optional(),
    endpoint: z.string().optional(),
    body: z.any().optional(),
    level: z.enum(['success', 'error', 'warning', 'info']).optional(),
    content: z.string().optional(),
    payload: z.any().optional(),
    channel: z.string().optional(),
  })
  .passthrough();

export const handlerConfigSchema = z
  .object({
    type: z.enum(['flow', 'builtin', 'script']),
    name: z.string().optional(),
    steps: z.array(flowStepSchema).optional(),
    code: z.string().optional(),
  })
  .passthrough();

export type HandlerConfigSchema = z.infer<typeof handlerConfigSchema>;
