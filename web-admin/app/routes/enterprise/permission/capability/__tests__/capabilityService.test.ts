import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ErrorCodes } from '~/shared/services/http-client/types';

vi.mock('~/shared/services/http-client', () => ({
  get: vi.fn(),
  put: vi.fn(),
  post: vi.fn(),
}));

import { get, put, post } from '~/shared/services/http-client';
import { capabilityService } from '../capabilityService';

const OK = ErrorCodes.SUCCESS;

describe('capabilityService', () => {
  beforeEach(() => vi.clearAllMocks());

  it('getForRole calls GET with the rolePid query and unwraps data', async () => {
    (get as ReturnType<typeof vi.fn>).mockResolvedValue({
      code: OK,
      data: [{ group: 'g', capabilities: [] }],
    });
    const groups = await capabilityService.getForRole('role-pid-5');
    expect(get).toHaveBeenCalledWith(
      '/api/permission/capabilities?rolePid=role-pid-5',
      undefined,
      undefined,
      undefined,
    );
    expect(groups).toEqual([{ group: 'g', capabilities: [] }]);
  });

  it('applySelection PUTs the selected codes as the request body', async () => {
    (put as ReturnType<typeof vi.fn>).mockResolvedValue({ code: OK, data: [] });
    await capabilityService.applySelection('role-pid-5', ['crm.cap.account']);
    expect(put).toHaveBeenCalledWith(
      '/api/permission/capabilities?rolePid=role-pid-5',
      ['crm.cap.account'],
      undefined,
      undefined,
    );
  });

  it('throws with the server message on a non-success result', async () => {
    (get as ReturnType<typeof vi.fn>).mockResolvedValue({ code: '500', desc: 'boom', data: null });
    await expect(capabilityService.getForRole('role-pid-5')).rejects.toThrow('boom');
  });
  it('sends explicit partial revocations separately from selected capabilities in preview and apply', async () => {
    vi.mocked(post).mockResolvedValue({ code: OK, desc: '', data: {} });
    vi.mocked(put).mockResolvedValue({ code: OK, desc: '', data: [] });
    await capabilityService.previewSelection('role/pid', ['cap.view'], ['cap.edit', 'cap.export']);
    expect(post).toHaveBeenCalledWith(
      '/api/permission/capabilities/preview?rolePid=role%2Fpid&revokePartial=cap.edit&revokePartial=cap.export',
      ['cap.view'],
    );
    await capabilityService.applySelection('role/pid', ['cap.view'], undefined, ['cap.edit']);
    expect(put).toHaveBeenCalledWith(
      '/api/permission/capabilities?rolePid=role%2Fpid&revokePartial=cap.edit',
      ['cap.view'],
      undefined,
      undefined,
    );
  });
});
