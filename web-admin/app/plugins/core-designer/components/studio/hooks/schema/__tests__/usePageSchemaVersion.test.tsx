/**
 * Unit tests for schema version hooks:
 *   usePageSchemaVersion, usePageSchemaVersionList,
 *   usePageSchemaVersionComparison, usePageSchemaAutoSave
 *
 * Mocks: getPageSchemaVersionManager + useAuth
 */

import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// ---------------------------------------------------------------------------
// Mocks — declared before imports that consume them
// ---------------------------------------------------------------------------

const mockVersionManagerInstance = {
  getCurrentDraft: vi.fn(),
  getPublishedVersion: vi.fn(),
  saveDraft: vi.fn(),
  createVersion: vi.fn(),
  updateVersion: vi.fn(),
  publishVersion: vi.fn(),
  rollbackVersion: vi.fn(),
  markSchemaChanged: vi.fn(),
  getVersions: vi.fn(),
  getVersion: vi.fn(),
};

vi.mock(
  '~/plugins/core-designer/components/studio/domain/metadata/PageSchemaVersionManager',
  () => ({
    getPageSchemaVersionManager: () => mockVersionManagerInstance,
  }),
);

vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: { email: 'designer@example.com', name: 'Designer' },
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
  usePageSchemaVersion,
  usePageSchemaVersionList,
  usePageSchemaVersionComparison,
  usePageSchemaAutoSave,
} from '../index';
import type { CanvasSchema } from '~/plugins/core-designer/components/studio/workbench/canvas/types';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeSchema(title = 'Test Page'): CanvasSchema {
  return {
    id: 'page-1',
    kind: 'form',
    title,
    version: '1.0.0',
    components: [],
    layout: { type: 'grid', columns: 12, spacing: 16, padding: 16 },
    metadata: { createdAt: '2024-01-01', updatedAt: '2024-01-01', createdBy: 'designer@example.com' },
  };
}

function makePageVersion(overrides: Partial<any> = {}): any {
  return {
    id: 'pv-1',
    version: '1.0.0',
    status: 'draft',
    type: 'snapshot',
    schema: makeSchema(),
    createdAt: new Date(),
    updatedAt: new Date(),
    createdBy: 'designer@example.com',
    updatedBy: 'designer@example.com',
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// usePageSchemaVersion
// ---------------------------------------------------------------------------

describe('usePageSchemaVersion', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockVersionManagerInstance.getCurrentDraft.mockResolvedValue(makePageVersion());
    mockVersionManagerInstance.getPublishedVersion.mockResolvedValue(null);
    mockVersionManagerInstance.saveDraft.mockResolvedValue(makePageVersion({ id: 'pv-saved' }));
    mockVersionManagerInstance.createVersion.mockResolvedValue(makePageVersion({ id: 'pv-created' }));
    mockVersionManagerInstance.updateVersion.mockResolvedValue(makePageVersion({ id: 'pv-updated' }));
    mockVersionManagerInstance.publishVersion.mockResolvedValue(makePageVersion({ id: 'pv-pub', status: 'published' }));
    mockVersionManagerInstance.rollbackVersion.mockResolvedValue(makePageVersion({ id: 'pv-rolled' }));
    mockVersionManagerInstance.markSchemaChanged.mockImplementation(() => {});
  });

  it('loads current draft on mount', async () => {
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.currentVersion?.id).toBe('pv-1');
    expect(result.current.currentSchema).not.toBeNull();
  });

  it('starts with hasUnsavedChanges=false', async () => {
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.hasUnsavedChanges).toBe(false);
  });

  it('sets hasUnsavedChanges=true after updateSchema', async () => {
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => result.current.updateSchema(makeSchema('Modified')));
    expect(result.current.hasUnsavedChanges).toBe(true);
    expect(result.current.currentSchema?.title).toBe('Modified');
  });

  it('saveDraft clears hasUnsavedChanges', async () => {
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => result.current.updateSchema(makeSchema('Dirty')));
    expect(result.current.hasUnsavedChanges).toBe(true);

    await act(async () => {
      await result.current.saveDraft(makeSchema('Dirty'), 'manual save');
    });
    expect(result.current.hasUnsavedChanges).toBe(false);
    expect(result.current.currentVersion?.id).toBe('pv-saved');
  });

  it('saveDraft calls manager with pageId, schema, and actor', async () => {
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    const schema = makeSchema();
    await act(async () => {
      await result.current.saveDraft(schema, 'test save');
    });
    expect(mockVersionManagerInstance.saveDraft).toHaveBeenCalledWith(
      'page-1',
      schema,
      'designer@example.com',
      'test save',
    );
  });

  it('createVersion creates a new version', async () => {
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    let created: any;
    await act(async () => {
      created = await result.current.createVersion({ version: '2.0.0', type: 'major', schema: makeSchema() });
    });
    expect(created.id).toBe('pv-created');
  });

  it('updateVersion updates existing version', async () => {
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    let updated: any;
    await act(async () => {
      updated = await result.current.updateVersion({ id: 'pv-1', description: 'updated' });
    });
    expect(updated.id).toBe('pv-updated');
  });

  it('publishVersion publishes the current version', async () => {
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    let published: any;
    await act(async () => {
      published = await result.current.publishVersion(undefined, 'release');
    });
    expect(published.status).toBe('published');
    expect(result.current.publishedVersion?.status).toBe('published');
  });

  it('publishVersion throws when no version is available', async () => {
    mockVersionManagerInstance.getCurrentDraft.mockResolvedValue(null);
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {
      await expect(result.current.publishVersion()).rejects.toThrow('没有可发布的版本');
    });
  });

  it('rollbackToVersion calls manager and resets schema', async () => {
    const rollbackSchema = makeSchema('Rolled Back');
    mockVersionManagerInstance.rollbackVersion.mockResolvedValue(
      makePageVersion({ id: 'pv-rolled', schema: rollbackSchema }),
    );
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    let rolled: any;
    await act(async () => {
      rolled = await result.current.rollbackToVersion('pv-old');
    });
    expect(rolled.id).toBe('pv-rolled');
    expect(result.current.currentSchema?.title).toBe('Rolled Back');
    expect(result.current.hasUnsavedChanges).toBe(false);
  });

  it('clearError resets error state', async () => {
    mockVersionManagerInstance.saveDraft.mockRejectedValueOnce(new Error('Save failed'));
    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {
      await result.current.saveDraft(makeSchema()).catch(() => {});
    });
    expect(result.current.error).toBeTruthy();
    act(() => result.current.clearError());
    expect(result.current.error).toBeNull();
  });

  it('saving starts false, becomes true during saveDraft, returns false after', async () => {
    let resolveSave: (v: any) => void;
    const pending = new Promise<any>((res) => { resolveSave = res; });
    mockVersionManagerInstance.saveDraft.mockReturnValueOnce(pending);

    const { result } = renderHook(() => usePageSchemaVersion('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));

    let savePromise: Promise<any>;
    act(() => {
      savePromise = result.current.saveDraft(makeSchema());
    });
    expect(result.current.saving).toBe(true);

    await act(async () => {
      resolveSave!(makePageVersion());
      await savePromise;
    });
    expect(result.current.saving).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// usePageSchemaVersionList
// ---------------------------------------------------------------------------

describe('usePageSchemaVersionList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockVersionManagerInstance.getVersions.mockResolvedValue({
      versions: [makePageVersion(), makePageVersion({ id: 'pv-2' })],
    });
  });

  it('loads list of versions on mount', async () => {
    const { result } = renderHook(() => usePageSchemaVersionList('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.versions).toHaveLength(2);
    expect(result.current.error).toBeNull();
  });

  it('sets error when getVersions rejects', async () => {
    mockVersionManagerInstance.getVersions.mockRejectedValue(new Error('List failed'));
    const { result } = renderHook(() => usePageSchemaVersionList('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.error).toBeTruthy();
    expect(result.current.versions).toEqual([]);
  });

  it('refresh reloads the version list', async () => {
    const { result } = renderHook(() => usePageSchemaVersionList('page-1'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    mockVersionManagerInstance.getVersions.mockClear();
    act(() => result.current.refresh());
    await waitFor(() => expect(mockVersionManagerInstance.getVersions).toHaveBeenCalled());
  });
});

// ---------------------------------------------------------------------------
// usePageSchemaVersionComparison
// ---------------------------------------------------------------------------

describe('usePageSchemaVersionComparison', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockVersionManagerInstance.getVersion
      .mockResolvedValueOnce(makePageVersion({ id: 'va', schema: makeSchema('A') }))
      .mockResolvedValueOnce(makePageVersion({ id: 'vb', schema: makeSchema('B') }));
  });

  it('starts with comparing=false and no comparisonResult', () => {
    const { result } = renderHook(() => usePageSchemaVersionComparison());
    expect(result.current.comparing).toBe(false);
    expect(result.current.comparisonResult).toBeNull();
  });

  it('compareVersions populates comparisonResult with differences', async () => {
    const { result } = renderHook(() => usePageSchemaVersionComparison());
    await act(async () => {
      await result.current.compareVersions('va', 'vb');
    });
    expect(result.current.comparing).toBe(false);
    expect(result.current.comparisonResult).not.toBeNull();
    expect(result.current.comparisonResult?.versionA.id).toBe('va');
    expect(result.current.comparisonResult?.versionB.id).toBe('vb');
    // Schemas differ in title → at least 1 difference
    expect(result.current.comparisonResult?.differences.length).toBeGreaterThan(0);
  });

  it('clearComparison resets comparisonResult', async () => {
    const { result } = renderHook(() => usePageSchemaVersionComparison());
    await act(async () => {
      await result.current.compareVersions('va', 'vb');
    });
    expect(result.current.comparisonResult).not.toBeNull();
    act(() => result.current.clearComparison());
    expect(result.current.comparisonResult).toBeNull();
  });

  it('compareVersions throws when a version is not found', async () => {
    // Reset and set both calls to return null (overrides the beforeEach once-mocks)
    mockVersionManagerInstance.getVersion.mockReset();
    mockVersionManagerInstance.getVersion.mockResolvedValue(null);
    const { result } = renderHook(() => usePageSchemaVersionComparison());
    await act(async () => {
      await expect(result.current.compareVersions('missing-a', 'missing-b')).rejects.toThrow('版本不存在');
    });
  });
});

// ---------------------------------------------------------------------------
// usePageSchemaAutoSave
// ---------------------------------------------------------------------------

describe('usePageSchemaAutoSave', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers();
    mockVersionManagerInstance.saveDraft.mockResolvedValue(makePageVersion());
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('starts with autoSaving=false and lastSaved=null', () => {
    const { result } = renderHook(() =>
      usePageSchemaAutoSave('page-1', makeSchema(), true),
    );
    expect(result.current.autoSaving).toBe(false);
    expect(result.current.lastSaved).toBeNull();
  });

  it('does not call saveDraft when enabled=false', async () => {
    const { result } = renderHook(() =>
      usePageSchemaAutoSave('page-1', makeSchema(), false),
    );
    act(() => vi.advanceTimersByTime(3000));
    expect(mockVersionManagerInstance.saveDraft).not.toHaveBeenCalled();
  });

  it('does not call saveDraft when schema=null', async () => {
    const { result } = renderHook(() =>
      usePageSchemaAutoSave('page-1', null, true),
    );
    act(() => vi.advanceTimersByTime(3000));
    expect(mockVersionManagerInstance.saveDraft).not.toHaveBeenCalled();
  });

  it('performAutoSave can be called directly', async () => {
    const { result } = renderHook(() =>
      usePageSchemaAutoSave('page-1', makeSchema(), true),
    );
    await act(async () => {
      await result.current.performAutoSave();
    });
    expect(mockVersionManagerInstance.saveDraft).toHaveBeenCalled();
    expect(result.current.lastSaved).not.toBeNull();
  });
});
