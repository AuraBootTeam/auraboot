import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useCloudConfigs } from '~/shared/admin/cloud-config-core';

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), del: vi.fn(), success: vi.fn(), error: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ get: http.get, post: http.post, del: http.del }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showSuccessToast: http.success, showErrorToast: http.error }) }));

const draft = { configLevel: 'tenant' as const, serviceType: 'llm' as const, providerCode: 'fixture',
  config: { apiKey: 'fixture-key' }, enabled: false, priority: 1 };
const saved = { ...draft, pid: 'saved-pid', config: '{"apiKey":"****"}' };

beforeEach(() => {
  vi.clearAllMocks();
  http.get.mockReset().mockResolvedValue({ code: '0', data: [] });
  http.post.mockReset().mockResolvedValue({ code: '0' });
});

async function setup() {
  const hook = renderHook(() => useCloudConfigs({ apiBase: '/api/llm-config', initialLevel: 'tenant' }));
  await waitFor(() => expect(hook.result.current.loading).toBe(false));
  act(() => hook.result.current.handleCreate());
  return hook;
}

describe('cloud configuration save readback', () => {
  it('retains the editor after failed readback and retries only GET without duplicating creation', async () => {
    const { result } = await setup();
    http.get.mockRejectedValueOnce(new Error('Readback unavailable'));
    let accepted: boolean | undefined;
    await act(async () => { accepted = await result.current.handleSave(draft); });
    expect(accepted).toBe(false);
    expect(result.current.showEditor).toBe(true);
    expect(result.current.saveReadbackPending).toBe(true);
    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.success).not.toHaveBeenCalled();
    http.get.mockResolvedValueOnce({ code: '0', data: [saved] });
    await act(async () => { accepted = await result.current.retrySaveReadback(); });
    expect(accepted).toBe(true);
    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.get).toHaveBeenLastCalledWith('/api/llm-config', { level: 'tenant' });
    expect(result.current.configs).toEqual([saved]);
    expect(result.current.showEditor).toBe(false);
    expect(result.current.saveReadbackPending).toBe(false);
  });

  it('keeps a business-error readback pending and blocks another POST', async () => {
    const { result } = await setup();
    http.get.mockResolvedValue({ code: '403', message: 'Permission revoked' });
    await act(async () => { expect(await result.current.handleSave(draft)).toBe(false); });
    await act(async () => { expect(await result.current.handleSave(draft)).toBe(false); });
    expect(http.post).toHaveBeenCalledTimes(1);
    expect(result.current.showEditor).toBe(true);
    expect(result.current.saveReadbackPending).toBe(true);
  });

  it('allows an actual POST failure to be corrected and submitted again', async () => {
    const { result } = await setup();
    http.post.mockResolvedValueOnce({ code: '400', message: 'Invalid configuration' });
    await act(async () => { expect(await result.current.handleSave(draft)).toBe(false); });
    expect(result.current.showEditor).toBe(true);
    expect(result.current.saveReadbackPending).toBe(false);
    http.get.mockResolvedValueOnce({ code: '0', data: [saved] });
    await act(async () => { expect(await result.current.handleSave(draft)).toBe(true); });
    expect(http.post).toHaveBeenCalledTimes(2);
    expect(result.current.showEditor).toBe(false);
  });
});
