import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { preparePageCopyContent } from '../../studio/services/page-manager/pageCopyContent';
import type { PageSchemaDTO } from '../../studio/services/page-manager/api-types';

interface Contract {
  name: string;
  source: PageSchemaDTO;
  expected: Record<string, unknown>;
}

// The command-side test reads this same fixture through the real entity/DTO converter.
const contracts: Contract[] = JSON.parse(readFileSync(resolve(
  process.cwd(), '../platform/src/test/resources/meta/page-copy-format-contract.json',
), 'utf8'));

describe('frontend and command page copy format contract', () => {
  it.each(contracts)('$name', ({ source, expected }) => {
    const before = structuredClone(source);
    expect(preparePageCopyContent(source)).toMatchObject(expected);
    expect(source).toEqual(before);
  });

  it.each([
    ['table', 'rich-text'],
    ['action-bar', 'field'],
    ['tabs', 'table'],
  ])('rejects %s containing misplaced %s without dropping content', (container, childType) => {
    const source = { ...contracts[0].source, blocks: [{ id: 'parent', blockType: container,
      blocks: [{ id: 'misplaced', blockType: childType, field: 'name' }] }] };
    const before = structuredClone(source);
    expect(() => preparePageCopyContent(source)).toThrow(/misplaced/);
    expect(source).toEqual(before);
  });
});
