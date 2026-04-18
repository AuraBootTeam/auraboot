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

  // ---------------------------------------------------------------------------
  // Preservation of rich toolbar button fields across VM round-trip.
  // Regression for bug where viewModelToBlocks emitted `{preset: 'create'}` and
  // dropped action/variant from real plugin pages (e.g. bpm_process_management).
  // ---------------------------------------------------------------------------

  it('preserves preset button action/variant across blocks → VM → blocks roundtrip', () => {
    const originalButton = {
      code: 'create',
      label: 'create',
      variant: 'primary',
      action: { type: 'navigate', to: '/bpmn-designer' },
    };
    const originalBlocks: DslBlock[] = [
      {
        id: 'toolbar',
        blockType: 'toolbar',
        buttons: [originalButton],
      } as unknown as DslBlock,
      { id: 'table', blockType: 'table' },
    ];

    const vm = blocksToViewModel(originalBlocks);
    expect(vm.toolbar.presets).toEqual(['create']);
    expect(vm.toolbar.presetRaw?.create).toEqual(originalButton);

    const roundTripped = viewModelToBlocks(vm);
    const toolbar = roundTripped.find((b) => b.blockType === 'toolbar')!;
    expect((toolbar.buttons as unknown as unknown[])[0]).toEqual(originalButton);
  });

  it('preserves unknown custom button fields across roundtrip', () => {
    const originalButton = {
      code: 'refresh',
      label: 'Refresh',
      command: 'refresh:table',
      variant: 'default',
      action: { type: 'command', command: 'refresh:table' },
      confirm: 'Are you sure?',
    };
    const originalBlocks: DslBlock[] = [
      {
        id: 'toolbar',
        blockType: 'toolbar',
        buttons: [originalButton],
      } as unknown as DslBlock,
      { id: 'table', blockType: 'table' },
    ];

    const vm = blocksToViewModel(originalBlocks);
    expect(vm.toolbar.presets).toEqual([]);
    expect(vm.toolbar.customButtons).toHaveLength(1);
    expect(vm.toolbar.customButtons[0].label).toBe('Refresh');
    expect(vm.toolbar.customButtons[0].command).toBe('refresh:table');
    expect(vm.toolbar.customButtons[0].raw).toEqual(originalButton);

    const roundTripped = viewModelToBlocks(vm);
    const toolbar = roundTripped.find((b) => b.blockType === 'toolbar')!;
    expect((toolbar.buttons as unknown as unknown[])[0]).toEqual(originalButton);
  });

  it('propagates label/command edits while preserving unknown custom button fields', () => {
    const originalBlocks: DslBlock[] = [
      {
        id: 'toolbar',
        blockType: 'toolbar',
        buttons: [
          {
            code: 'refresh',
            label: 'Refresh',
            command: 'refresh:table',
            variant: 'default',
            action: { type: 'command', command: 'refresh:table' },
          },
        ],
      } as unknown as DslBlock,
      { id: 'table', blockType: 'table' },
    ];

    const vm = blocksToViewModel(originalBlocks);
    // Simulate the user editing label via ToolbarTab.
    vm.toolbar.customButtons[0].label = 'Reload';

    const roundTripped = viewModelToBlocks(vm);
    const btn = (roundTripped.find((b) => b.blockType === 'toolbar')!
      .buttons as unknown as Array<Record<string, unknown>>)[0];
    expect(btn.label).toBe('Reload');
    expect(btn.code).toBe('refresh');
    expect(btn.variant).toBe('default');
    expect(btn.action).toEqual({ type: 'command', command: 'refresh:table' });
  });

  it('detects preset by code field when `preset` key is absent', () => {
    const blocks: DslBlock[] = [
      {
        id: 'toolbar',
        blockType: 'toolbar',
        buttons: [{ code: 'create' }, { code: 'export' }],
      } as unknown as DslBlock,
      { id: 'table', blockType: 'table' },
    ];
    const vm = blocksToViewModel(blocks);
    expect(vm.toolbar.presets).toEqual(['create', 'export']);
    // Trivial `{code: presetKey}` should not populate presetRaw, so VM stays
    // identical to a VM created from scratch.
    expect(vm.toolbar.presetRaw).toBeUndefined();
  });

  it('multiple occurrences of same preset are deduplicated', () => {
    const blocks: DslBlock[] = [
      {
        id: 'toolbar',
        blockType: 'toolbar',
        buttons: [
          { code: 'create', variant: 'primary' },
          { code: 'create', variant: 'default' },
        ],
      } as unknown as DslBlock,
      { id: 'table', blockType: 'table' },
    ];
    const vm = blocksToViewModel(blocks);
    expect(vm.toolbar.presets).toEqual(['create']);
    // First occurrence wins.
    expect(vm.toolbar.presetRaw?.create?.variant).toBe('primary');
  });
});
