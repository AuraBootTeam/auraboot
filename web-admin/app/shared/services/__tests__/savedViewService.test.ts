import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SavedViewService, loadPublicSharedView } from '../savedViewService';
import type { SavedViewWire } from '~/framework/smart/types/savedViewWire';

const request = vi.hoisted(() => vi.fn());
vi.mock('../http-client/internal-api', () => ({ internalRequest: request }));
const view: SavedViewWire = {
  pid: 'owned-view',
  name: 'Owned view',
  modelCode: 'ab_team',
  scope: 'personal',
  viewType: 'table',
  viewConfig: {},
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
  actions: ['view', 'save'],
  effectivePermission: 'manage',
  dirty: false,
};
const query = { modelCode: 'ab_team', pageKey: 'ab_team_list' };
const capability = {
  viewType: 'gallery',
  status: 'blocked',
  missingFields: ['galleryImageField'],
  reasons: [
    { code: 'MISSING_REQUIRED_FIELD', field: 'galleryImageField', message: 'Image field required' },
  ],
};
const share = {
  token: 'owned-token',
  shareUrl: '/api/views/shared/owned-token',
  expiresAt: null,
  passwordProtected: false,
};
const cases: Array<{
  name: string;
  run: (service: SavedViewService) => Promise<unknown>;
  method: string;
  path: string;
  input: object;
  data: unknown;
}> = [
  {
    name: 'getView',
    run: (s) => s.getView('owned-view'),
    method: 'get',
    path: '/api/views/{pid}',
    input: { path: { pid: 'owned-view' } },
    data: view,
  },
  ...(['getAccessibleViews', 'getPersonalViews', 'getGlobalViews', 'getTeamViews'] as const).map(
    (name, index) => ({
      name,
      run: (s: SavedViewService) => s[name](query),
      method: 'get',
      path: [
        '/api/views/accessible',
        '/api/views/personal',
        '/api/views/global',
        '/api/views/team',
      ][index],
      input: { query },
      data: [view],
    }),
  ),
  {
    name: 'getDefaultView',
    run: (s) => s.getDefaultView(query),
    method: 'get',
    path: '/api/views/default',
    input: { query },
    data: view,
  },
  {
    name: 'createView',
    run: (s) => s.createView({ name: 'Owned view', modelCode: 'ab_team' }),
    method: 'post',
    path: '/api/views',
    input: { body: { name: 'Owned view', modelCode: 'ab_team', viewConfig: undefined } },
    data: view,
  },
  {
    name: 'autoSave',
    run: (s) => s.autoSave({ modelCode: 'ab_team' }),
    method: 'post',
    path: '/api/views/auto-save',
    input: { body: { modelCode: 'ab_team', viewConfig: undefined } },
    data: view,
  },
  {
    name: 'updateView',
    run: (s) => s.updateView('owned-view', { name: 'Changed' }),
    method: 'put',
    path: '/api/views/{pid}',
    input: { path: { pid: 'owned-view' }, body: { name: 'Changed', viewConfig: undefined } },
    data: view,
  },
  {
    name: 'deleteView',
    run: (s) => s.deleteView('owned-view'),
    method: 'delete',
    path: '/api/views/{pid}',
    input: { path: { pid: 'owned-view' } },
    data: null,
  },
  {
    name: 'setDefaultView',
    run: (s) => s.setDefaultView('owned-view'),
    method: 'post',
    path: '/api/views/{pid}/set-default',
    input: { path: { pid: 'owned-view' } },
    data: view,
  },
  {
    name: 'duplicateView',
    run: (s) => s.duplicateView('owned-view', 'Copy'),
    method: 'post',
    path: '/api/views/{pid}/duplicate',
    input: { path: { pid: 'owned-view' }, body: { name: 'Copy' } },
    data: view,
  },
  {
    name: 'copyToPersonal',
    run: (s) => s.copyToPersonal('owned-view', { name: 'Personal copy' }),
    method: 'post',
    path: '/api/views/{pid}/copy-to-personal',
    input: { path: { pid: 'owned-view' }, body: { name: 'Personal copy', viewConfig: undefined } },
    data: view,
  },
  {
    name: 'getAuditEvents',
    run: (s) => s.getAuditEvents('owned-view'),
    method: 'get',
    path: '/api/views/{pid}/audit-events',
    input: { path: { pid: 'owned-view' } },
    data: [{ sequenceNo: 1, metadata: null }],
  },
  {
    name: 'checkCapability',
    run: (s) => s.checkCapability({ viewType: 'gallery', modelCode: 'ab_team' }),
    method: 'post',
    path: '/api/views/capability-check',
    input: { body: { viewType: 'gallery', modelCode: 'ab_team', viewConfig: undefined } },
    data: capability,
  },
  {
    name: 'shareView',
    run: (s) => s.shareView('owned-view', { password: 'fixture', expireHours: 2 }),
    method: 'post',
    path: '/api/views/{viewPid}/share',
    input: { path: { viewPid: 'owned-view' }, body: { password: 'fixture', expireHours: 2 } },
    data: share,
  },
  {
    name: 'revokeShare',
    run: (s) => s.revokeShare('owned-view'),
    method: 'delete',
    path: '/api/views/{viewPid}/share',
    input: { path: { viewPid: 'owned-view' } },
    data: true,
  },
  {
    name: 'getShareStatus',
    run: (s) => s.getShareStatus('owned-view'),
    method: 'get',
    path: '/api/views/{viewPid}/share/status',
    input: { path: { viewPid: 'owned-view' } },
    data: { shared: false, token: null },
  },
  {
    name: 'checkNameUnique',
    run: (s) => s.checkNameUnique({ modelCode: 'ab_team', name: 'Owned view' }),
    method: 'get',
    path: '/api/views/check-name',
    input: { query: { modelCode: 'ab_team', name: 'Owned view' } },
    data: false,
  },
  {
    name: 'getChipPins',
    run: (s) => s.getChipPins(query),
    method: 'get',
    path: '/api/views/chip-pins',
    input: { query },
    data: [{ viewPid: 'owned-view', order: 0 }],
  },
  {
    name: 'pinView',
    run: (s) => s.pinView('owned-view'),
    method: 'post',
    path: '/api/views/{viewPid}/pin',
    input: { path: { viewPid: 'owned-view' }, body: { order: undefined } },
    data: null,
  },
  {
    name: 'unpinView',
    run: (s) => s.unpinView('owned-view'),
    method: 'delete',
    path: '/api/views/{viewPid}/pin',
    input: { path: { viewPid: 'owned-view' }, query: undefined },
    data: null,
  },
  {
    name: 'getMyTeams',
    run: (s) => s.getMyTeams(),
    method: 'get',
    path: '/api/views/my-teams',
    input: {},
    data: [{ teamPid: 'team-1', teamName: 'Team 1', role: null }],
  },
  {
    name: 'searchUsers',
    run: (s) => s.searchUsers(' owner ', 10),
    method: 'post',
    path: '/api/tenant/members/search',
    input: { body: { pageNum: 1, pageSize: 10, status: 'active', keyword: 'owner' } },
    data: {
      records: [
        {
          pid: 'member-1',
          user: { pid: 'user-1', username: 'owner', email: null, realName: null, avatar: null },
        },
      ],
    },
  },
];

beforeEach(() => request.mockReset());
describe('SavedView generated operation consumption', () => {
  it.each(cases)('$name binds the actual operation and request slots', async (row) => {
    request.mockResolvedValue({ code: '0', desc: 'OK', data: row.data });
    await row.run(new SavedViewService());
    expect(request).toHaveBeenCalledExactlyOnceWith(row.method, row.path, {
      ...row.input,
      request: undefined,
    });
  });
  it('forwards the caller request without adding alternate authentication', async () => {
    const caller = new Request('http://runtime.invalid/views');
    request.mockResolvedValue({ code: '0', desc: 'OK', data: view });
    await new SavedViewService().getView('owned-view', caller);
    expect(request).toHaveBeenCalledWith('get', '/api/views/{pid}', {
      path: { pid: 'owned-view' },
      request: caller,
    });
  });
  it('pins and unpins a team with its public team identity and preserves order zero', async () => {
    request.mockResolvedValue({ code: '0', desc: 'OK', data: null });
    const service = new SavedViewService();
    await service.pinView('owned-view', { scope: 'team', teamId: 'team-1', order: 0 });
    expect(request).toHaveBeenLastCalledWith('post', '/api/views/{viewPid}/pin', {
      path: { viewPid: 'owned-view' },
      body: { scope: 'team', teamId: 'team-1', order: 0 },
      request: undefined,
    });
    await service.unpinView('owned-view', { scope: 'team', teamId: 'team-1' });
    expect(request).toHaveBeenLastCalledWith('delete', '/api/views/{viewPid}/pin', {
      path: { viewPid: 'owned-view' },
      query: { scope: 'team', teamId: 'team-1' },
      request: undefined,
    });
  });
  it('returns null only for a successful missing default and preserves real failures', async () => {
    const service = new SavedViewService();
    request.mockResolvedValueOnce({ code: '0', desc: 'OK', data: null });
    expect(await service.getDefaultView(query)).toBeNull();
    request.mockResolvedValueOnce({
      code: '503',
      desc: 'not found upstream connection',
      data: null,
    });
    await expect(service.getDefaultView(query)).rejects.toThrow('not found upstream connection');
  });
  it.each(['share', 'revoke', 'status'])(
    'keeps %s failures from appearing successful',
    async (action) => {
      request.mockResolvedValue({ code: '403', desc: 'Denied', data: null });
      const service = new SavedViewService();
      await expect(
        action === 'share'
          ? service.shareView('owned-view')
          : action === 'revoke'
            ? service.revokeShare('owned-view')
            : service.getShareStatus('owned-view'),
      ).rejects.toThrow('Denied');
    },
  );
  it('does not pretend legacy team aliases or absent collaborator pagination are valid wire shapes', async () => {
    request.mockResolvedValueOnce({
      code: '0',
      desc: 'OK',
      data: [{ pid: 'team-1', name: 'Legacy' }],
    });
    await expect(new SavedViewService().getMyTeams()).rejects.toThrow('identity');
    request.mockResolvedValueOnce({ code: '0', desc: 'OK', data: { content: [] } });
    await expect(new SavedViewService().searchUsers('owner')).rejects.toThrow('pagination');
  });
  it('uses nested member identity, handles no-email users and omits memberships with no user', async () => {
    request.mockResolvedValue({
      code: '0',
      desc: 'OK',
      data: {
        records: [
          {
            pid: 'membership',
            user: { pid: 'user-1', username: 'owner', email: null, realName: null, avatar: null },
          },
          { pid: 'orphan-membership', user: null },
        ],
      },
    });
    expect(await new SavedViewService().searchUsers('owner')).toEqual([
      { pid: 'user-1', displayName: 'owner', email: undefined, avatarUrl: undefined },
    ]);
  });
  it('rejects an assignable collaborator that lacks its user PID', async () => {
    request.mockResolvedValue({
      code: '0',
      desc: 'OK',
      data: { records: [{ user: { username: 'owner' } }] },
    });
    await expect(new SavedViewService().searchUsers('owner')).rejects.toThrow('identity');
  });
  it('preserves JSON-null audit metadata rather than changing it to an empty object', async () => {
    request.mockResolvedValue({ code: '0', desc: 'OK', data: [{ sequenceNo: 1, metadata: null }] });
    expect((await new SavedViewService().getAuditEvents('owned-view'))[0].metadata).toBeNull();
  });
  it('keeps the anonymous token, password, abort signal and validation context in the actual operation', async () => {
    const signal = new AbortController().signal;
    const denied = {
      code: '35000',
      desc: 'Bad parameter',
      httpStatus: 422,
      context: { error: 'Invalid password' },
      data: null,
    };
    request.mockResolvedValue(denied);
    expect(await loadPublicSharedView('owned-token', 'fixture', signal)).toEqual(denied);
    expect(request).toHaveBeenCalledWith('get', '/api/views/shared/{shareToken}', {
      path: { shareToken: 'owned-token' },
      query: { password: 'fixture' },
      options: { skipAutoToken: true, signal },
    });
  });
  it('loads the producer configuration response without fabricating a record table', async () => {
    request.mockResolvedValue({
      code: '0',
      desc: 'OK',
      data: {
        name: 'Public view',
        modelCode: 'ab_team',
        viewType: 'table',
        viewConfig: { filters: [{ value: null }] },
      },
    });
    const result = await loadPublicSharedView('owned-token');
    expect(result.data?.viewConfig.filters).toEqual([{ value: null }]);
    expect(result.data).not.toHaveProperty('records');
    expect(result.data).not.toHaveProperty('columns');
  });
});
