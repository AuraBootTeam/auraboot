import { describe, it, expect } from 'vitest';
import {
  viewModelToBlocks,
  blocksToViewModel,
  emptyListViewModel,
  type ListViewModel,
} from '../mapper';
import type { DslBlock } from '~/plugins/core-designer/components/studio/domain/dsl/types';

describe('list mapper', () => {
  it('emptyListViewModel round-trips', () => {
    const vm = emptyListViewModel();
    const back = blocksToViewModel(viewModelToBlocks(vm));
    expect(back).toEqual(vm);
  });

  it('rich VM round-trips identically', () => {
    const vm: ListViewModel = {
      columns: [
        { field: 'name', width: 200 },
        { field: 'status', align: 'center', renderer: 'badge' },
        { field: 'createdAt' },
      ],
      filters: [
        { field: 'status', operator: 'eq', defaultValue: 'active' },
        { field: 'name' },
      ],
      toolbar: {
        presets: ['create', 'export'],
        customButtons: [
          { label: 'Custom', command: 'custom:action', requiresSelection: true },
        ],
      },
      behavior: {
        defaultSortField: 'createdAt',
        defaultSortOrder: 'desc',
        pageSize: 50,
        multiSelect: true,
        rowClickAction: 'drawer',
        emptyStateText: 'No records',
      },
    };
    const back = blocksToViewModel(viewModelToBlocks(vm));
    expect(back).toEqual(vm);
  });

  it('tolerates missing blocks (returns empty defaults)', () => {
    const vm = blocksToViewModel(undefined);
    expect(vm).toEqual(emptyListViewModel());
  });

  it('tolerates table block without props', () => {
    const blocks: DslBlock[] = [{ id: 't', blockType: 'table' }];
    const vm = blocksToViewModel(blocks);
    expect(vm.behavior.pageSize).toBe(20);
    expect(vm.behavior.multiSelect).toBe(false);
    expect(vm.behavior.defaultSortOrder).toBe('desc');
  });

  it('serializes column without extras as shorthand string', () => {
    const vm = emptyListViewModel();
    vm.columns = [{ field: 'name' }];
    const blocks = viewModelToBlocks(vm);
    const table = blocks.find((b) => b.blockType === 'table')!;
    expect(table.columns).toEqual(['name']);
  });

  it('serializes filter without extras as shorthand string', () => {
    const vm = emptyListViewModel();
    vm.filters = [{ field: 'name' }];
    const blocks = viewModelToBlocks(vm);
    const filtersBlock = blocks.find((b) => b.blockType === 'filters')!;
    expect(filtersBlock.fields).toEqual(['name']);
  });

  it('emits filters + toolbar + table in deterministic order', () => {
    const blocks = viewModelToBlocks(emptyListViewModel());
    expect(blocks.map((b) => b.blockType)).toEqual(['filters', 'toolbar', 'table']);
    expect(blocks.map((b) => b.id)).toEqual([
      'filters_generated',
      'toolbar_generated',
      'table_generated',
    ]);
  });
});
