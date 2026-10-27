import { describe, expect, it } from 'vitest';
import {
  collectListMiscBlocks,
  findListFilterTabsBlock,
  isContentTabsBlock,
  resolveInitialListTabKey,
} from '../../pages/list/listSchemaPolicy';

describe('List content tabs policy', () => {
  const content = {
    id: 'content',
    blockType: 'tabs',
    tabs: [
      { key: 'resources', blocks: [{ id: 'cards', blockType: 'card-grid', dataSource: 'cards' }] },
      {
        key: 'anchorages',
        blocks: [{ id: 'anchorages', blockType: 'table', dataSource: 'anchorages' }],
      },
      {
        key: 'maintenance',
        blocks: [{ id: 'maintenance', blockType: 'table', dataSource: 'maintenance' }],
      },
    ],
  };
  const filters = { id: 'filters', blockType: 'tabs', tabs: [{ key: 'pending', filter: null }] };
  it('renders content tabs in both ordinary and custom-only lists, while filter tabs stay specialized', () => {
    const schema = { blocks: [content, filters] };
    expect(collectListMiscBlocks(schema, true)).toEqual([content]);
    expect(collectListMiscBlocks(schema, false)).toEqual([content]);
    expect(findListFilterTabsBlock(schema.blocks)).toBe(filters);
  });
  it('keeps content selection out of the native filter default', () => {
    expect(resolveInitialListTabKey([content])).toBe('all');
    expect(resolveInitialListTabKey([content, filters])).toBe('pending');
    expect(
      resolveInitialListTabKey([{ ...filters, tabs: [{ key: 'pending' }, { key: 'all' }] }]),
    ).toBe('all');
  });
  it('rejects mixed content/filter definitions', () => {
    expect(() =>
      isContentTabsBlock({
        id: 'mixed',
        blockType: 'tabs',
        tabs: [
          { key: 'content', blocks: [] },
          { key: 'filter', filter: { status: 'new' } },
        ],
      }),
    ).toThrow('mixes content and filter tabs');
    expect(() =>
      isContentTabsBlock({
        id: 'mixed',
        blockType: 'tabs',
        tabs: [{ key: 'content', blocks: [], filter: { status: 'new' } }],
      }),
    ).toThrow('mixes content and filter tabs');
  });
});
