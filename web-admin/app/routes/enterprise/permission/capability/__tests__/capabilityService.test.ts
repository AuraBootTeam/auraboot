import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ErrorCodes } from '~/shared/services/http-client/types';

vi.mock('~/shared/services/http-client', () => ({
  get: vi.fn(),
  put: vi.fn(),
}));

import { get, put } from '~/shared/services/http-client';
import { capabilityService } from '../capabilityService';

const OK = ErrorCodes.SUCCESS;

describe('capabilityService', () => {
  beforeEach(() => vi.clearAllMocks());

  it('getForRole calls GET with the roleId query and unwraps data', async () => {
    (get as ReturnType<typeof vi.fn>).mockResolvedValue({ code: OK, data: [{ group: 'g', capabilities: [] }] });
    const groups = await capabilityService.getForRole('5');
    expect(get).toHaveBeenCalledWith('/api/permission/capabilities?roleId=5', undefined, undefined, undefined);
    expect(groups).toEqual([{ group: 'g', capabilities: [] }]);
  });

  it('applySelection PUTs the selected codes as the request body', async () => {
    (put as ReturnType<typeof vi.fn>).mockResolvedValue({ code: OK, data: [] });
    await capabilityService.applySelection('5', ['crm.cap.account']);
    expect(put).toHaveBeenCalledWith(
      '/api/permission/capabilities?roleId=5',
      ['crm.cap.account'],
      undefined,
      undefined,
    );
  });

  it('throws with the server message on a non-success result', async () => {
    (get as ReturnType<typeof vi.fn>).mockResolvedValue({ code: '500', desc: 'boom', data: null });
    await expect(capabilityService.getForRole('5')).rejects.toThrow('boom');
  });
});
