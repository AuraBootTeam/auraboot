import { describe, it, expect } from 'vitest';
import {
  blocksToDetailVm,
  detailVmToBlocks,
  emptyDetailViewModel,
  type DetailViewModel,
} from '../mapper';

describe('detail mapper', () => {
  it('emptyDetailViewModel round-trips (blocks may differ since no toolbar output)', () => {
    const vm = emptyDetailViewModel();
    const back = blocksToDetailVm(detailVmToBlocks(vm));
    expect(back).toMatchObject(vm);
    expect(back.originalToolbarBlock).toBeUndefined();
  });

  it('rich VM round-trips', () => {
    const vm: DetailViewModel = {
      sections: [
        { id: 'basic', title: '基本信息', columns: 2, fields: ['name', 'status'] },
        {
          id: 'audit',
          title: '系统信息',
          columns: 2,
          fields: ['createdAt'],
          collapsible: true,
          defaultCollapsed: true,
        },
      ],
      actions: {
        presets: ['edit', 'delete'],
        customButtons: [{ label: '复制', command: 'plug:copy' }],
      },
      passthroughBlocks: [],
    };
    const back = blocksToDetailVm(detailVmToBlocks(vm));
    expect(back).toMatchObject(vm);
    expect(back.originalToolbarBlock).toMatchObject({
      id: 'actions_top',
      blockType: 'toolbar',
    });
  });

  it('preserves unsupported passthrough blocks and raw toolbar when unchanged', () => {
    const blocks = [
      {
        id: 'actions_top',
        blockType: 'toolbar',
        buttons: [
          { label: 'edit', action: { type: 'navigate', to: 'wd_leave_request_form' } },
          { label: 'execute', command: 'wd:submit_leave_request', primary: true },
        ],
      },
      {
        id: 'wd_leave_request_tabs',
        blockType: 'tabs',
        tabs: [{ key: 'workflow_diagram', label: { 'zh-CN': '流程图' } }],
      },
    ] as any;

    const vm = blocksToDetailVm(blocks);
    const roundTrip = detailVmToBlocks(vm);

    expect(roundTrip).toEqual(blocks);
  });

  it('detects legacy edit button as preset and avoids [object Object] labels', () => {
    const blocks = [
      {
        id: 'actions_top',
        blockType: 'toolbar',
        buttons: [
          { label: 'edit', action: { type: 'navigate', to: 'wd_leave_request_form' } },
          {
            label: { 'zh-CN': '提交审批', 'en-US': 'Submit' },
            action: { type: 'command', command: 'wd:submit_leave_request' },
            icon: { name: 'play-circle' },
          },
        ],
      },
    ] as any;

    const vm = blocksToDetailVm(blocks);

    expect(vm.actions.presets).toEqual(['edit']);
    expect(vm.actions.customButtons).toHaveLength(1);
    expect(vm.actions.customButtons[0]).toMatchObject({
      label: '提交审批',
      command: 'wd:submit_leave_request',
      icon: 'play-circle',
    });
  });

  it('serializes custom command button to runtime action structure', () => {
    const vm: DetailViewModel = {
      sections: [],
      actions: {
        presets: ['delete'],
        customButtons: [{ label: '提交', command: 'wd:submit_leave_request' }],
      },
      passthroughBlocks: [],
    };

    const blocks = detailVmToBlocks(vm);
    const toolbar = blocks.find((block) => block.blockType === 'toolbar') as any;

    expect(toolbar.buttons[0]).toMatchObject({ code: 'delete' });
    expect(toolbar.buttons[1]).toMatchObject({
      label: '提交',
      command: 'wd:submit_leave_request',
      commandCode: 'wd:submit_leave_request',
      action: { type: 'command', command: 'wd:submit_leave_request' },
    });
  });
});
