import { z } from 'zod';

export const localizedTextSchema = z.union([
  z.string(),
  z
    .object({
      'zh-CN': z.string().optional(),
      'en-US': z.string().optional(),
      'ja-JP': z.string().optional(),
      'ko-KR': z.string().optional(),
    })
    .passthrough(),
]);

export type LocalizedTextSchema = z.infer<typeof localizedTextSchema>;
