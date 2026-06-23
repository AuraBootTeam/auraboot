import { beforeEach, describe, expect, it, vi } from 'vitest';
import { get, post } from '~/shared/services/http-client';
import { SavedViewService } from '../savedViewService';
import type { SavedView, SavedViewCapabilityCheckResponse } from '~/framework/smart/types/savedView';

vi.mock('~/shared/services/http-client', () => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

const mockedGet = vi.mocked(get);
const mockedPost = vi.mocked(post);

function makeView(overrides: Partial<SavedView> = {}): SavedView {
  return {
    pid: 'personal_copy',
    name: 'My Team View',
    modelCode: 'order',
    scope: 'personal',
    viewType: 'table',
    viewConfig: {},
    ...overrides,
  };
}

describe('SavedViewService', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('getMyTeams fetches current saved-view team memberships', async () => {
    const service = new SavedViewService();
    mockedGet.mockResolvedValue({
      code: '0',
      desc: 'ok',
      data: [{ pid: 'team-a', name: 'Team A', role: 'owner' }],
    });

    const result = await service.getMyTeams();

    expect(mockedGet).toHaveBeenCalledWith(
      '/api/views/my-teams',
      undefined,
      undefined,
      undefined,
    );
    expect(result).toEqual([{ pid: 'team-a', name: 'Team A', role: 'owner' }]);
  });

  it('copyToPersonal posts to the dedicated personal-copy endpoint', async () => {
    const service = new SavedViewService();
    const copied = makeView();
    mockedPost.mockResolvedValue({ code: '0', desc: 'ok', data: copied });

    const result = await service.copyToPersonal('team1', {
      name: 'My Team View',
      viewConfig: { filters: [] },
    });

    expect(mockedPost).toHaveBeenCalledWith(
      '/api/views/team1/copy-to-personal',
      { name: 'My Team View', viewConfig: { filters: [] } },
      undefined,
      undefined,
    );
    expect(result).toBe(copied);
  });

  it('getAuditEvents fetches audit trail for a visible saved view', async () => {
    const service = new SavedViewService();
    mockedGet.mockResolvedValue({
      code: '0',
      desc: 'ok',
      data: [{ entityPid: 'team1', operationType: 'UPDATE' }],
    });

    const result = await service.getAuditEvents('team1');

    expect(mockedGet).toHaveBeenCalledWith(
      '/api/views/team1/audit-events',
      undefined,
      undefined,
      undefined,
    );
    expect(result).toEqual([{ entityPid: 'team1', operationType: 'UPDATE' }]);
  });

  it('checkCapability posts view type and config to the capability-check endpoint', async () => {
    const service = new SavedViewService();
    const response: SavedViewCapabilityCheckResponse = {
      viewType: 'gallery',
      status: 'blocked',
      missingFields: ['galleryImageField'],
      reasons: [
        {
          code: 'MISSING_REQUIRED_FIELD',
          field: 'galleryImageField',
          message: 'Missing required gallery viewConfig field: galleryImageField',
        },
      ],
    };
    mockedPost.mockResolvedValue({ code: '0', desc: 'ok', data: response });

    const result = await service.checkCapability({
      viewType: 'gallery',
      viewConfig: { galleryTitleField: 'name' },
    });

    expect(mockedPost).toHaveBeenCalledWith(
      '/api/views/capability-check',
      { viewType: 'gallery', viewConfig: { galleryTitleField: 'name' } },
      undefined,
      undefined,
    );
    expect(result).toBe(response);
  });
});
