import { beforeEach, describe, expect, it, vi } from 'vitest';

const { getMock, postMock } = vi.hoisted(() => ({
  getMock: vi.fn(),
  postMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  get: getMock,
  post: postMock,
}));

import { CommandActionService } from '../CommandActionService';

describe('CommandActionService.execute', () => {
  beforeEach(() => {
    getMock.mockReset();
    postMock.mockReset();
  });

  it('preserves backend error code and context for runtime classification', async () => {
    postMock.mockResolvedValue({
      code: '403',
      desc: 'Access forbidden',
      message: 'Access forbidden',
      data: null,
      context: { permission: 'order.export' },
    });

    const service = new CommandActionService();

    await expect(service.execute('order.export', { dryRun: true })).rejects.toMatchObject({
      message: 'Access forbidden',
      code: '403',
      context: { permission: 'order.export' },
    });
    expect(postMock).toHaveBeenCalledWith('/api/meta/commands/execute/order.export', {
      payload: { dryRun: true },
    });
  });

  it('forwards runtime audit context without mixing it into the business payload', async () => {
    postMock.mockResolvedValue({
      code: '0',
      data: { commandCode: 'order.export', data: { ok: true } },
    });

    const service = new CommandActionService();

    await service.execute(
      'order.export',
      { dryRun: true },
      {
        auditContext: {
          source: 'unified-designer-runtime-preview',
          pageId: 'orders',
          blockId: 'export_button',
        },
      },
    );

    expect(postMock).toHaveBeenCalledWith('/api/meta/commands/execute/order.export', {
      payload: { dryRun: true },
      auditContext: {
        source: 'unified-designer-runtime-preview',
        pageId: 'orders',
        blockId: 'export_button',
      },
    });
  });
});
