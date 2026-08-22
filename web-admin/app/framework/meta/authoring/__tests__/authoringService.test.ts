import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AuthoringServiceError,
  isAuthoringPermissionDeniedError,
  loadAuthoringPermissionSnapshot,
  loadAuthoringSession,
} from '../authoringService';
import { fetchResult } from '~/shared/services/http-client';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

describe('authoringService permission reconciliation', () => {
  beforeEach(() => {
    vi.mocked(fetchResult).mockReset();
  });

  it('loads a fresh effective permission snapshot from the authoritative auth endpoint', async () => {
    vi.mocked(fetchResult).mockResolvedValueOnce({
      code: '0',
      desc: 'OK',
      data: {
        permissions: {
          permissionCodes: ['meta.designer.read', 'meta.designer.update'],
          permissions: [{ code: 'meta.designer.admin' }],
        },
      },
    });

    await expect(loadAuthoringPermissionSnapshot()).resolves.toEqual({
      canReadDesigner: true,
      canManageDesigner: true,
      canAdministerDesigner: true,
    });
    expect(fetchResult).toHaveBeenCalledWith('/api/auth/me');
  });

  it('preserves the backend code when an authoring request is denied', async () => {
    vi.mocked(fetchResult).mockResolvedValueOnce({
      code: '403',
      desc: 'Forbidden',
      message: 'Permission denied',
      data: null,
    });

    const failure = await loadAuthoringSession('session-1').catch((error) => error);

    expect(failure).toBeInstanceOf(AuthoringServiceError);
    expect(failure).toMatchObject({ code: '403', message: 'Permission denied' });
    expect(isAuthoringPermissionDeniedError(failure)).toBe(true);
  });

  it('does not classify transport failures as permission changes', () => {
    expect(isAuthoringPermissionDeniedError(new Error('Failed to fetch'))).toBe(false);
  });
});
