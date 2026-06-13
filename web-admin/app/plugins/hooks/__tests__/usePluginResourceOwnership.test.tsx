/**
 * Unit tests for usePluginResourceOwnership and useModificationWarning hooks.
 *
 * Mocks: pluginUninstallApi functions and ResultHelper.isSuccess.
 */

import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

// ---- Mock API before import ----
vi.mock('~/plugins/api/pluginUninstallApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('~/plugins/api/pluginUninstallApi')>();
  return {
    ...actual,
    getResourceOwnership: vi.fn(),
    markResourceAsModified: vi.fn(),
    claimResource: vi.fn(),
    getResourceDiff: vi.fn(),
  };
});

vi.mock('~/utils/type', async (importOriginal) => {
  const actual = await importOriginal<typeof import('~/utils/type')>();
  return {
    ...actual,
    ResultHelper: {
      isSuccess: vi.fn((r: { code: string }) => r.code === '0'),
    },
  };
});

import {
  getResourceOwnership,
  markResourceAsModified,
  claimResource,
  getResourceDiff,
  type ResourceOwnershipInfo,
  type ResourceDiff,
} from '~/plugins/api/pluginUninstallApi';
import { ResultHelper } from '~/utils/type';
import {
  usePluginResourceOwnership,
  useModificationWarning,
} from '../usePluginResourceOwnership';

const mockGetOwnership = getResourceOwnership as ReturnType<typeof vi.fn>;
const mockMarkModified = markResourceAsModified as ReturnType<typeof vi.fn>;
const mockClaim = claimResource as ReturnType<typeof vi.fn>;
const mockGetDiff = getResourceDiff as ReturnType<typeof vi.fn>;
const mockIsSuccess = ResultHelper.isSuccess as ReturnType<typeof vi.fn>;

function makeOwnership(partial: Partial<ResourceOwnershipInfo> = {}): ResourceOwnershipInfo {
  return {
    resourceType: 'model',
    resourceCode: 'my_model',
    managed: true,
    ownershipType: 'shared',
    userModified: false,
    canModify: true,
    pluginPid: 'ent-core',
    ...partial,
  };
}

function successResult<T>(data: T) {
  return { code: '0', data, desc: '' };
}

function failResult(desc: string) {
  return { code: '1', data: null, desc };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockIsSuccess.mockImplementation((r: { code: string }) => r.code === '0');
});

// ---------------------------------------------------------------------------
// usePluginResourceOwnership
// ---------------------------------------------------------------------------
describe('usePluginResourceOwnership', () => {
  it('has correct initial state', () => {
    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
    expect(result.current.ownershipInfo).toBeNull();
    expect(result.current.diffs).toEqual([]);
  });

  it('checkOwnership sets ownershipInfo on success', async () => {
    const info = makeOwnership({ ownershipType: 'plugin_owned' });
    mockGetOwnership.mockResolvedValueOnce(successResult(info));

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    await act(async () => {
      await result.current.checkOwnership();
    });

    expect(result.current.ownershipInfo).toEqual(info);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('checkOwnership sets error on failure', async () => {
    mockGetOwnership.mockResolvedValueOnce(failResult('Not found'));

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    await act(async () => {
      await result.current.checkOwnership();
    });

    expect(result.current.error).toBe('Not found');
    expect(result.current.ownershipInfo).toBeNull();
  });

  it('checkOwnership handles thrown error', async () => {
    mockGetOwnership.mockRejectedValueOnce(new Error('Network fail'));

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    await act(async () => {
      await result.current.checkOwnership();
    });

    expect(result.current.error).toBe('Network fail');
  });

  it('checkOwnership calls onOwnershipChange callback', async () => {
    const info = makeOwnership();
    mockGetOwnership.mockResolvedValueOnce(successResult(info));
    const onOwnershipChange = vi.fn();

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test', onOwnershipChange }),
    );

    await act(async () => {
      await result.current.checkOwnership();
    });

    expect(onOwnershipChange).toHaveBeenCalledWith(info);
  });

  it('checkOwnership returns null when resourceType is empty', async () => {
    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: '' as never, resourceCode: 'test' }),
    );

    let ret: ResourceOwnershipInfo | null | undefined;
    await act(async () => {
      ret = await result.current.checkOwnership();
    });

    expect(ret).toBeNull();
    expect(mockGetOwnership).not.toHaveBeenCalled();
  });

  it('markAsModified returns true on success and refreshes ownership', async () => {
    const info = makeOwnership({ userModified: true });
    mockMarkModified.mockResolvedValueOnce(successResult(null));
    mockGetOwnership.mockResolvedValue(successResult(info));

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    let ret: boolean | undefined;
    await act(async () => {
      ret = await result.current.markAsModified();
    });

    expect(ret).toBe(true);
    expect(mockMarkModified).toHaveBeenCalled();
    // refresh was called
    expect(mockGetOwnership).toHaveBeenCalled();
  });

  it('markAsModified returns false on failure', async () => {
    mockMarkModified.mockResolvedValueOnce(failResult('Permission denied'));

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    let ret: boolean | undefined;
    await act(async () => {
      ret = await result.current.markAsModified();
    });

    expect(ret).toBe(false);
    expect(result.current.error).toBe('Permission denied');
  });

  it('claim returns true on success', async () => {
    mockClaim.mockResolvedValueOnce(successResult(null));
    mockGetOwnership.mockResolvedValue(successResult(makeOwnership({ ownershipType: 'user_claimed' })));

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    let ret: boolean | undefined;
    await act(async () => {
      ret = await result.current.claim();
    });

    expect(ret).toBe(true);
  });

  it('loadDiffs returns diffs on success', async () => {
    const diffs: ResourceDiff[] = [
      { field: 'displayName', original: 'Old', current: 'New', description: 'Changed' },
    ];
    mockGetDiff.mockResolvedValueOnce(successResult(diffs));

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    await act(async () => {
      await result.current.loadDiffs();
    });

    expect(result.current.diffs).toEqual(diffs);
  });

  it('loadDiffs returns empty array on failure', async () => {
    mockGetDiff.mockResolvedValueOnce(failResult('Error'));

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    let ret: ResourceDiff[] | undefined;
    await act(async () => {
      ret = await result.current.loadDiffs();
    });

    expect(ret).toEqual([]);
  });

  // ---- Derived state ----

  it('computes isPluginOwned correctly', async () => {
    mockGetOwnership.mockResolvedValueOnce(
      successResult(makeOwnership({ ownershipType: 'plugin_owned' })),
    );

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    await act(async () => {
      await result.current.checkOwnership();
    });

    expect(result.current.isPluginOwned).toBe(true);
    expect(result.current.isShared).toBe(false);
    expect(result.current.isUserClaimed).toBe(false);
  });

  it('computes isShared correctly', async () => {
    mockGetOwnership.mockResolvedValueOnce(
      successResult(makeOwnership({ ownershipType: 'shared' })),
    );

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    await act(async () => {
      await result.current.checkOwnership();
    });

    expect(result.current.isShared).toBe(true);
  });

  it('computes isUserClaimed correctly', async () => {
    mockGetOwnership.mockResolvedValueOnce(
      successResult(makeOwnership({ ownershipType: 'user_claimed' })),
    );

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    await act(async () => {
      await result.current.checkOwnership();
    });

    expect(result.current.isUserClaimed).toBe(true);
  });

  it('defaults canModify to true when ownershipInfo is null', () => {
    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );
    expect(result.current.canModify).toBe(true);
  });

  it('refresh triggers checkOwnership', async () => {
    const info = makeOwnership();
    mockGetOwnership.mockResolvedValue(successResult(info));

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test' }),
    );

    await act(async () => {
      await result.current.refresh();
    });

    expect(mockGetOwnership).toHaveBeenCalledTimes(1);
  });

  it('calls onError callback when checkOwnership fails', async () => {
    mockGetOwnership.mockResolvedValueOnce(failResult('Server error'));
    const onError = vi.fn();

    const { result } = renderHook(() =>
      usePluginResourceOwnership({ resourceType: 'model', resourceCode: 'test', onError }),
    );

    await act(async () => {
      await result.current.checkOwnership();
    });

    expect(onError).toHaveBeenCalledWith('Server error');
  });
});

// ---------------------------------------------------------------------------
// useModificationWarning
// ---------------------------------------------------------------------------
describe('useModificationWarning', () => {
  it('shouldShowWarning starts false', () => {
    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test' }),
    );
    expect(result.current.shouldShowWarning).toBe(false);
  });

  it('handleBeforeModify returns "continue" when resource is not managed', async () => {
    mockGetOwnership.mockResolvedValueOnce(
      successResult(makeOwnership({ managed: false })),
    );

    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test' }),
    );

    let ret: string | undefined;
    await act(async () => {
      ret = await result.current.handleBeforeModify();
    });

    expect(ret).toBe('continue');
  });

  it('handleBeforeModify returns "blocked" for plugin_owned', async () => {
    mockGetOwnership.mockResolvedValueOnce(
      successResult(makeOwnership({ managed: true, ownershipType: 'plugin_owned' })),
    );

    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test' }),
    );

    let ret: string | undefined;
    await act(async () => {
      ret = await result.current.handleBeforeModify();
    });

    expect(ret).toBe('blocked');
  });

  it('handleBeforeModify returns "continue" for user_claimed', async () => {
    mockGetOwnership.mockResolvedValueOnce(
      successResult(makeOwnership({ managed: true, ownershipType: 'user_claimed' })),
    );

    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test' }),
    );

    let ret: string | undefined;
    await act(async () => {
      ret = await result.current.handleBeforeModify();
    });

    expect(ret).toBe('continue');
  });

  it('handleBeforeModify returns "show-warning" for first modification of shared resource', async () => {
    mockGetOwnership.mockResolvedValueOnce(
      successResult(makeOwnership({ managed: true, ownershipType: 'shared', userModified: false })),
    );

    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test' }),
    );

    let ret: string | undefined;
    await act(async () => {
      ret = await result.current.handleBeforeModify();
    });

    expect(ret).toBe('show-warning');
    expect(result.current.shouldShowWarning).toBe(true);
  });

  it('handleBeforeModify returns "continue" for already-modified shared resource', async () => {
    mockGetOwnership.mockResolvedValueOnce(
      successResult(makeOwnership({ managed: true, ownershipType: 'shared', userModified: true })),
    );

    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test' }),
    );

    let ret: string | undefined;
    await act(async () => {
      ret = await result.current.handleBeforeModify();
    });

    expect(ret).toBe('continue');
  });

  it('handleContinue calls markAsModified and dismisses warning', async () => {
    // Set up: first call to checkOwnership (for handleBeforeModify), second after markAsModified
    mockGetOwnership
      .mockResolvedValueOnce(
        successResult(makeOwnership({ managed: true, ownershipType: 'shared', userModified: false })),
      )
      .mockResolvedValue(successResult(makeOwnership({ userModified: true })));
    mockMarkModified.mockResolvedValueOnce(successResult(null));

    const onContinue = vi.fn();
    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test', onContinue }),
    );

    await act(async () => {
      await result.current.handleBeforeModify();
    });
    expect(result.current.shouldShowWarning).toBe(true);

    await act(async () => {
      await result.current.handleContinue();
    });

    expect(result.current.shouldShowWarning).toBe(false);
    expect(onContinue).toHaveBeenCalledTimes(1);
  });

  it('handleCancel dismisses warning and calls onCancel', async () => {
    mockGetOwnership.mockResolvedValueOnce(
      successResult(makeOwnership({ managed: true, ownershipType: 'shared', userModified: false })),
    );

    const onCancel = vi.fn();
    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test', onCancel }),
    );

    await act(async () => {
      await result.current.handleBeforeModify();
    });
    expect(result.current.shouldShowWarning).toBe(true);

    act(() => {
      result.current.handleCancel();
    });

    expect(result.current.shouldShowWarning).toBe(false);
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('handleDetach calls claim and dismisses warning', async () => {
    mockGetOwnership
      .mockResolvedValueOnce(
        successResult(makeOwnership({ managed: true, ownershipType: 'shared', userModified: false })),
      )
      .mockResolvedValue(successResult(makeOwnership({ ownershipType: 'user_claimed' })));
    mockClaim.mockResolvedValueOnce(successResult(null));

    const onDetach = vi.fn();
    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test', onDetach }),
    );

    await act(async () => {
      await result.current.handleBeforeModify();
    });

    await act(async () => {
      await result.current.handleDetach();
    });

    expect(result.current.shouldShowWarning).toBe(false);
    expect(onDetach).toHaveBeenCalledTimes(1);
  });

  it('dismissWarning hides warning without side effects', async () => {
    mockGetOwnership.mockResolvedValueOnce(
      successResult(makeOwnership({ managed: true, ownershipType: 'shared', userModified: false })),
    );

    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test' }),
    );

    await act(async () => {
      await result.current.handleBeforeModify();
    });
    expect(result.current.shouldShowWarning).toBe(true);

    act(() => {
      result.current.dismissWarning();
    });

    expect(result.current.shouldShowWarning).toBe(false);
  });

  it('handleBeforeModify returns "continue" when checkOwnership returns null (error path)', async () => {
    mockGetOwnership.mockRejectedValueOnce(new Error('Network'));

    const { result } = renderHook(() =>
      useModificationWarning({ resourceType: 'model', resourceCode: 'test' }),
    );

    let ret: string | undefined;
    await act(async () => {
      ret = await result.current.handleBeforeModify();
    });

    expect(ret).toBe('continue');
  });
});
