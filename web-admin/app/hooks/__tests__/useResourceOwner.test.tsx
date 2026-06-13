import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('~/shared/services/http-client/HttpClient', () => ({
  get: vi.fn(),
  post: vi.fn(),
}));

import { useResourceOwner, useBatchResourceOwners } from '../useResourceOwner';
import { get, post } from '~/shared/services/http-client/HttpClient';

const mockGet = get as ReturnType<typeof vi.fn>;
const mockPost = post as ReturnType<typeof vi.fn>;

const okOwner = {
  managed: true,
  pluginId: 'plugin-crm',
  pluginName: 'CRM Plugin',
  pluginVersion: '1.0.0',
  ownershipType: 'plugin',
  userModified: false,
  userModifiedAt: null,
  importedAt: '2024-01-01T00:00:00Z',
  protectionLevel: 2,
};

describe('useResourceOwner', () => {
  beforeEach(() => vi.clearAllMocks());

  it('starts with owner=null and loading=false when no args', () => {
    const { result } = renderHook(() => useResourceOwner('', ''));
    expect(result.current.owner).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('does not fetch when resourceType or resourceCode is empty', () => {
    renderHook(() => useResourceOwner('', 'someCode'));
    expect(mockGet).not.toHaveBeenCalled();

    renderHook(() => useResourceOwner('MODEL', ''));
    expect(mockGet).not.toHaveBeenCalled();
  });

  it('fetches owner info when resourceType and resourceCode are provided', async () => {
    mockGet.mockResolvedValue({ success: true, data: okOwner });

    const { result } = renderHook(() => useResourceOwner('MODEL', 'order'));

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(mockGet).toHaveBeenCalledWith('/api/plugins/resources/owner', {
      resourceType: 'MODEL',
      resourceCode: 'order',
    });
    expect(result.current.owner).toEqual(okOwner);
  });

  it('leaves owner null when API returns success=false', async () => {
    mockGet.mockResolvedValue({ success: false, data: null });

    const { result } = renderHook(() => useResourceOwner('MODEL', 'unknown'));

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(result.current.owner).toBeNull();
  });

  it('leaves owner null when API success is false and data is undefined', async () => {
    mockGet.mockResolvedValue({ success: false, data: undefined });

    const { result } = renderHook(() => useResourceOwner('MODEL', 'other'));

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(result.current.owner).toBeNull();
  });
});

describe('useBatchResourceOwners', () => {
  beforeEach(() => vi.clearAllMocks());

  it('starts with empty owners map', () => {
    const { result } = renderHook(() => useBatchResourceOwners(null));
    expect(result.current.owners).toEqual({});
    expect(result.current.loading).toBe(false);
  });

  it('does not fetch when resources is null or empty', () => {
    renderHook(() => useBatchResourceOwners(null));
    renderHook(() => useBatchResourceOwners([]));
    expect(mockPost).not.toHaveBeenCalled();
  });

  it('fetches batch ownership when resources provided', async () => {
    const ownersMap = {
      'MODEL:order': okOwner,
      'COMMAND:order:create': { ...okOwner, pluginId: 'crm' },
    };
    mockPost.mockResolvedValue({ success: true, data: ownersMap });

    const resources = [
      { type: 'MODEL', code: 'order' },
      { type: 'COMMAND', code: 'order:create' },
    ];

    const { result } = renderHook(() => useBatchResourceOwners(resources));

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(mockPost).toHaveBeenCalledWith('/api/plugins/resources/owners', { resources });
    expect(result.current.owners).toEqual(ownersMap);
  });

  it('deduplicates calls for the same resource set', async () => {
    mockPost.mockResolvedValue({ success: true, data: {} });

    const resources = [{ type: 'MODEL', code: 'order' }];
    const { result, rerender } = renderHook(
      ({ res }: { res: Array<{ type: string; code: string }> }) => useBatchResourceOwners(res),
      { initialProps: { res: resources } },
    );

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(mockPost).toHaveBeenCalledTimes(1);

    // Same array reference → same key → no re-fetch
    rerender({ res: [{ type: 'MODEL', code: 'order' }] });
    await waitFor(() => expect(result.current.loading).toBe(false));

    // Should still be only 1 call (deduplication)
    expect(mockPost).toHaveBeenCalledTimes(1);
  });
});
