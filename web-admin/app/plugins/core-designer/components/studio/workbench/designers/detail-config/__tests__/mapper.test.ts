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
    expect(back).toEqual(vm);
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
    };
    const back = blocksToDetailVm(detailVmToBlocks(vm));
    expect(back).toEqual(vm);
  });
});
