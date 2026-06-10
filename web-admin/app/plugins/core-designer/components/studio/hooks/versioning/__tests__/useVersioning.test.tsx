/**
 * Unit tests for versioning hooks:
 *   useVersionList, useVersion, useVersionOperations,
 *   useVersionSync, useVersionLock, useVersionStats
 *
 * Both getVersionManager and useAuth are mocked.
 */

import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

// ---------------------------------------------------------------------------
// Mocks — must be declared BEFORE any hook imports that use them
// ---------------------------------------------------------------------------

const mockVersionManager = {
  getVersions: vi.fn(),
  getVersion: vi.fn(),
  createVersion: vi.fn(),
  updateVersion: vi.fn(),
  deleteVersion: vi.fn(),
  publishVersion: vi.fn(),
  unpublishVersion: vi.fn(),
  rollbackVersion: vi.fn(),
  duplicateVersion: vi.fn(),
  archiveVersion: vi.fn(),
  restoreVersion: vi.fn(),
  getCurrentVersion: vi.fn(),
  getPublishedVersion: vi.fn(),
  getSyncStatus: vi.fn(),
  updateSyncStatus: vi.fn(),
  lockVersion: vi.fn(),
  unlockVersion: vi.fn(),
  addEventListener: vi.fn(),
  removeEventListener: vi.fn(),
};

vi.mock(
  '~/plugins/core-designer/components/studio/services/managers',
  () => ({
    getVersionManager: () => mockVersionManager,
  }),
);

vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: { email: 'test@example.com', name: 'Test User' },
    isAuthenticated: true,
    hasPermission: () => true,
    hasRole: () => false,
    hasAnyPermission: () => true,
    hasAllPermissions: () => true,
  }),
}));

// ---------------------------------------------------------------------------
// Imports after mocks
// ---------------------------------------------------------------------------

import {
  useVersionList,
  useVersion,
  useVersionOperations,
  useVersionSync,
  useVersionLock,
  useVersionStats,
} from '../index';
import { VersionStatus, VersionType, SyncStatus } from '~/plugins/core-designer/components/studio/domain/metadata/types';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeVersion(overrides: Partial<any> = {}): any {
  return {
    id: 'v1',
    version: '1.0.0',
    status: VersionStatus.draft,
    type: VersionType.SNAPSHOT,
    schema: { id: 'page-1', title: 'Test', version: '1', kind: 'form', components: [], layout: { type: 'grid', columns: 12, spacing: 16, padding: 16 }, metadata: { createdAt: '', updatedAt: '', createdBy: '' } },
    createdAt: new Date(),
    updatedAt: new Date(),
    createdBy: 'test@example.com',
    updatedBy: 'test@example.com',
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// useVersionList
// ---------------------------------------------------------------------------

describe('useVersionList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockVersionManager.getVersions.mockResolvedValue({ versions: [makeVersion()], total: 1 });
    mockVersionManager.addEventListener.mockImplementation(() => {});
    mockVersionManager.removeEventListener.mockImplementation(() => {});
  });

  it('starts with loading=true and empty versions', () => {
    const { result } = renderHook(() => useVersionList('page-1'));
    expect(result.current.loading).toBe(true);
    expect(result.current.versions).toEqual([]);
  });

  it('resolves versions after load completes', async () => {
    const { result } = renderHook(() => useVersionList('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.versions).toHaveLength(1);
    expect(result.current.total).toBe(1);
  });

  it('calls getVersions with correct pageId', async () => {
    const { result } = renderHook(() => useVersionList('page-42'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(mockVersionManager.getVersions).toHaveBeenCalledWith('page-42', expect.any(Object));
  });

  it('exposes totalPages derived from total/size', async () => {
    mockVersionManager.getVersions.mockResolvedValue({ versions: [], total: 45 });
    const { result } = renderHook(() => useVersionList('page-1', { size: 20 }));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.totalPages).toBe(3); // ceil(45/20)
  });

  it('sets error when getVersions rejects', async () => {
    mockVersionManager.getVersions.mockRejectedValue(new Error('Network error'));
    const { result } = renderHook(() => useVersionList('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeTruthy();
  });

  it('nextPage increments page', () => {
    const { result } = renderHook(() => useVersionList('page-1'));
    act(() => result.current.nextPage());
    expect(result.current.page).toBe(2);
  });

  it('prevPage decrements page but not below 1', () => {
    const { result } = renderHook(() => useVersionList('page-1'));
    act(() => result.current.prevPage());
    expect(result.current.page).toBe(1);
  });

  it('goToPage sets page to given value', () => {
    const { result } = renderHook(() => useVersionList('page-1'));
    act(() => result.current.goToPage(5));
    expect(result.current.page).toBe(5);
  });

  it('goToPage clamps to minimum 1', () => {
    const { result } = renderHook(() => useVersionList('page-1'));
    act(() => result.current.goToPage(-3));
    expect(result.current.page).toBe(1);
  });
});

// ---------------------------------------------------------------------------
// useVersion
// ---------------------------------------------------------------------------

describe('useVersion', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockVersionManager.getVersion.mockResolvedValue(makeVersion());
    mockVersionManager.addEventListener.mockImplementation(() => {});
    mockVersionManager.removeEventListener.mockImplementation(() => {});
  });

  it('loads version data for a given versionId', async () => {
    const { result } = renderHook(() => useVersion('v1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.version?.id).toBe('v1');
    expect(result.current.error).toBeNull();
  });

  it('sets version=null when versionId is null', () => {
    const { result } = renderHook(() => useVersion(null));
    expect(result.current.version).toBeNull();
    expect(mockVersionManager.getVersion).not.toHaveBeenCalled();
  });

  it('sets error and version=null when getVersion rejects', async () => {
    mockVersionManager.getVersion.mockRejectedValue(new Error('Not found'));
    const { result } = renderHook(() => useVersion('bad-id'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.version).toBeNull();
    expect(result.current.error).toBeTruthy();
  });

  it('refresh re-invokes getVersion', async () => {
    const { result } = renderHook(() => useVersion('v1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    mockVersionManager.getVersion.mockClear();
    act(() => result.current.refresh());
    await waitFor(() => expect(mockVersionManager.getVersion).toHaveBeenCalled());
  });
});

// ---------------------------------------------------------------------------
// useVersionOperations
// ---------------------------------------------------------------------------

describe('useVersionOperations', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockVersionManager.createVersion.mockResolvedValue(makeVersion({ id: 'v-new' }));
    mockVersionManager.updateVersion.mockResolvedValue(makeVersion({ id: 'v-updated' }));
    mockVersionManager.deleteVersion.mockResolvedValue(undefined);
    mockVersionManager.publishVersion.mockResolvedValue(makeVersion({ id: 'v-pub', status: VersionStatus.published }));
    mockVersionManager.unpublishVersion.mockResolvedValue(makeVersion({ id: 'v-unpub' }));
    mockVersionManager.rollbackVersion.mockResolvedValue(makeVersion({ id: 'v-rollback' }));
    mockVersionManager.duplicateVersion.mockResolvedValue(makeVersion({ id: 'v-dup' }));
    mockVersionManager.archiveVersion.mockResolvedValue(makeVersion({ id: 'v-arch', status: VersionStatus.archived }));
    mockVersionManager.restoreVersion.mockResolvedValue(makeVersion({ id: 'v-rest' }));
    mockVersionManager.addEventListener.mockImplementation(() => {});
    mockVersionManager.removeEventListener.mockImplementation(() => {});
  });

  it('starts with loading=false and no error', () => {
    const { result } = renderHook(() => useVersionOperations('page-1'));
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('createVersion calls manager and returns new version', async () => {
    const { result } = renderHook(() => useVersionOperations('page-1'));
    let created: any;
    await act(async () => {
      created = await result.current.createVersion({
        version: '1.0.0',
        type: VersionType.SNAPSHOT,
        schema: makeVersion().schema,
      });
    });
    expect(created.id).toBe('v-new');
    expect(mockVersionManager.createVersion).toHaveBeenCalledWith(
      'page-1',
      expect.any(Object),
      'test@example.com',
    );
  });

  it('sets error and loading=false when createVersion throws', async () => {
    mockVersionManager.createVersion.mockRejectedValue(new Error('Create failed'));
    const { result } = renderHook(() => useVersionOperations('page-1'));
    await act(async () => {
      await expect(
        result.current.createVersion({ version: '1.0.0', type: VersionType.SNAPSHOT, schema: makeVersion().schema }),
      ).rejects.toThrow('Create failed');
    });
    expect(result.current.error).toBe('Create failed');
    expect(result.current.loading).toBe(false);
  });

  it('updateVersion calls manager with actor', async () => {
    const { result } = renderHook(() => useVersionOperations('page-1'));
    let updated: any;
    await act(async () => {
      updated = await result.current.updateVersion({ id: 'v1', description: 'desc' });
    });
    expect(updated.id).toBe('v-updated');
    expect(mockVersionManager.updateVersion).toHaveBeenCalledWith(
      { id: 'v1', description: 'desc' },
      'test@example.com',
    );
  });

  it('deleteVersion calls manager and resolves', async () => {
    const { result } = renderHook(() => useVersionOperations('page-1'));
    await act(async () => {
      await result.current.deleteVersion('v1');
    });
    expect(mockVersionManager.deleteVersion).toHaveBeenCalledWith('page-1', 'v1', 'test@example.com');
  });

  it('publishVersion calls manager with pageId', async () => {
    const { result } = renderHook(() => useVersionOperations('page-1'));
    let published: any;
    await act(async () => {
      published = await result.current.publishVersion({ versionId: 'v1' });
    });
    expect(published.status).toBe(VersionStatus.published);
    expect(mockVersionManager.publishVersion).toHaveBeenCalledWith(
      'page-1',
      'v1',
      { versionId: 'v1' },
      'test@example.com',
    );
  });

  it('rollbackVersion calls manager', async () => {
    const { result } = renderHook(() => useVersionOperations('page-1'));
    let rolled: any;
    await act(async () => {
      rolled = await result.current.rollbackVersion({ targetVersionId: 'v-old', type: VersionType.SNAPSHOT });
    });
    expect(rolled.id).toBe('v-rollback');
  });

  it('clearError resets error state', async () => {
    mockVersionManager.createVersion.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useVersionOperations('page-1'));
    await act(async () => {
      await result.current.createVersion({ version: '1', type: VersionType.SNAPSHOT, schema: makeVersion().schema }).catch(() => {});
    });
    expect(result.current.error).toBeTruthy();
    act(() => result.current.clearError());
    expect(result.current.error).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// useVersionSync
// ---------------------------------------------------------------------------

describe('useVersionSync', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockVersionManager.getSyncStatus.mockReturnValue(null);
    mockVersionManager.updateSyncStatus.mockImplementation(() => {});
  });

  it('starts with syncStatus=null and syncing=false', () => {
    const { result } = renderHook(() => useVersionSync('v1'));
    expect(result.current.syncStatus).toBeNull();
    expect(result.current.syncing).toBe(false);
  });

  it('startSync sets syncing=true and calls updateSyncStatus(SYNCING)', () => {
    const { result } = renderHook(() => useVersionSync('v1'));
    act(() => result.current.startSync());
    expect(result.current.syncing).toBe(true);
    expect(mockVersionManager.updateSyncStatus).toHaveBeenCalledWith('v1', SyncStatus.SYNCING, undefined);
  });

  it('completeSync(true) sets syncing=false and calls SYNCED', () => {
    const { result } = renderHook(() => useVersionSync('v1'));
    act(() => {
      result.current.startSync();
      result.current.completeSync(true);
    });
    expect(result.current.syncing).toBe(false);
    expect(mockVersionManager.updateSyncStatus).toHaveBeenLastCalledWith('v1', SyncStatus.SYNCED, undefined);
  });

  it('completeSync(false) sets syncing=false and calls FAILED', () => {
    const { result } = renderHook(() => useVersionSync('v1'));
    act(() => {
      result.current.startSync();
      result.current.completeSync(false, 'Connection refused');
    });
    expect(result.current.syncing).toBe(false);
    expect(mockVersionManager.updateSyncStatus).toHaveBeenLastCalledWith('v1', SyncStatus.failed, 'Connection refused');
  });

  it('does nothing when versionId is null', () => {
    const { result } = renderHook(() => useVersionSync(null));
    act(() => result.current.startSync());
    expect(mockVersionManager.updateSyncStatus).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// useVersionLock
// ---------------------------------------------------------------------------

describe('useVersionLock', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockVersionManager.lockVersion.mockResolvedValue(undefined);
    mockVersionManager.unlockVersion.mockResolvedValue(undefined);
  });

  it('starts with locked=false and no lockInfo', () => {
    const { result } = renderHook(() => useVersionLock('v1'));
    expect(result.current.locked).toBe(false);
    expect(result.current.lockInfo).toBeNull();
  });

  it('lockVersion sets locked=true and lockInfo', async () => {
    const { result } = renderHook(() => useVersionLock('v1'));
    await act(async () => {
      await result.current.lockVersion('For review');
    });
    expect(result.current.locked).toBe(true);
    expect(result.current.lockInfo?.lockedBy).toBe('test@example.com');
    expect(result.current.lockInfo?.reason).toBe('For review');
  });

  it('unlockVersion resets locked and lockInfo', async () => {
    const { result } = renderHook(() => useVersionLock('v1'));
    await act(async () => {
      await result.current.lockVersion('reason');
      await result.current.unlockVersion();
    });
    expect(result.current.locked).toBe(false);
    expect(result.current.lockInfo).toBeNull();
  });

  it('lockVersion does nothing when versionId is null', async () => {
    const { result } = renderHook(() => useVersionLock(null));
    await act(async () => {
      await result.current.lockVersion();
    });
    expect(mockVersionManager.lockVersion).not.toHaveBeenCalled();
    expect(result.current.locked).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// useVersionStats
// ---------------------------------------------------------------------------

describe('useVersionStats', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockVersionManager.getVersions
      .mockResolvedValueOnce({ versions: [], total: 3 }) // draft
      .mockResolvedValueOnce({ versions: [], total: 1 }) // published
      .mockResolvedValueOnce({ versions: [], total: 2 }); // archived
    mockVersionManager.addEventListener.mockImplementation(() => {});
    mockVersionManager.removeEventListener.mockImplementation(() => {});
  });

  it('loads stats aggregated from 3 status queries', async () => {
    const { result } = renderHook(() => useVersionStats('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.stats.draft).toBe(3);
    expect(result.current.stats.published).toBe(1);
    expect(result.current.stats.archived).toBe(2);
    expect(result.current.stats.total).toBe(6);
  });
});
