import type { CanvasBlock } from '~/plugins/core-designer/components/studio/domain/canvas/types';
import { BlockRegistry } from '~/plugins/core-designer/components/studio/registry';

export function getCanvasBlockLabel(block: CanvasBlock): string {
  const title = block.config?.title || (block as CanvasBlock & { title?: unknown }).title;
  if (typeof title === 'string' && title.trim()) return title;
  if (title && typeof title === 'object') {
    const localized = title as Record<string, string | undefined>;
    if (localized['zh-CN']) return localized['zh-CN'];
    if (localized['en-US']) return localized['en-US'];
  }

  if (block.blockType === 'form-section') {
    const fields = (block.config?.fields as unknown[] | undefined) ?? [];
    return fields.length > 0 ? `Form Section · ${fields.length} fields` : 'Form Section';
  }

  return BlockRegistry.get(block.blockType)?.name ?? block.blockType;
}
