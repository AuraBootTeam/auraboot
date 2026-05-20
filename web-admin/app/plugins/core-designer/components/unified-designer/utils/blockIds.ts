import type { DslBlockV3 } from '../types';

export function toStableBlockId(...parts: Array<string | number | undefined | null>): string {
  return parts
    .filter((part) => part !== undefined && part !== null && String(part).trim() !== '')
    .map((part) =>
      String(part)
        .trim()
        .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '_')
        .replace(/^_+|_+$/g, ''),
    )
    .filter(Boolean)
    .join('_');
}

export function collectBlockIds(blocks: DslBlockV3[]): Set<string> {
  const ids = new Set<string>();

  const visit = (items: DslBlockV3[]) => {
    items.forEach((block) => {
      if (block.id) ids.add(block.id);
      if (block.blocks?.length) visit(block.blocks);
    });
  };

  visit(blocks);
  return ids;
}

export function createUniqueBlockId(baseId: string, existingIds: Set<string>): string {
  if (!existingIds.has(baseId)) return baseId;

  let index = 2;
  let nextId = `${baseId}_${index}`;
  while (existingIds.has(nextId)) {
    index += 1;
    nextId = `${baseId}_${index}`;
  }
  return nextId;
}
