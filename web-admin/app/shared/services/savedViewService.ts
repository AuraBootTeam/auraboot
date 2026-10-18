/** SavedView HTTP operations use generated contracts; editor/display projections are explicit. */
import {
  internalRequest,
  type JsonValue,
  type InternalRequestInput,
} from './http-client/internal-api';
import type { Result } from './http-client/types';
import { ResultHelper } from '~/utils/type';
import type {
  SavedView,
  SavedViewAuditEvent,
  SavedViewCapabilityCheckRequest,
  SavedViewCapabilityCheckResponse,
  SavedViewCreateRequest,
  SavedViewCopyToPersonalRequest,
  SavedViewShareRequest,
  SavedViewShareResult,
  SavedViewShareStatus,
  SavedViewTeamOption,
  SavedViewUserOption,
  SavedViewUpdateRequest,
  SavedViewQueryParams,
  SavedViewAutoSaveRequest,
} from '~/framework/smart/types/savedView';
import type {
  PresentFields,
  SavedViewWire,
  SavedViewChipPinWire,
  SavedViewChipPinRequestWire,
  PublicSharedViewWire,
} from '~/framework/smart/types/savedViewWire';
import {
  presentFields,
  savedViewForView,
  savedViewConfigToWire,
  savedViewCapabilityForView,
  savedViewJson,
} from './savedViewContract';

function responseData<T>(result: Result<T>, message: string): T {
  ensureSuccess(result, message);
  if (result.data == null) throw new Error(message);
  return result.data;
}
function ensureSuccess(result: Result<unknown>, message: string): void {
  if (!ResultHelper.isSuccess(result)) throw new Error(result.desc || result.message || message);
}
function views(data: SavedViewWire[]): SavedView[] {
  if (!Array.isArray(data)) throw new Error('Invalid saved view list response');
  return data.map(savedViewForView);
}
export type ChipPin = SavedViewChipPinWire & { viewPid: string; order: number };
export type ChipPinOptions = Pick<
  PresentFields<SavedViewChipPinRequestWire>,
  'order' | 'teamId'
> & { scope?: 'personal' | 'team' };
export type PublicSharedView = PublicSharedViewWire & {
  name: string;
  viewConfig: Record<string, JsonValue>;
};

export class SavedViewService {
  async getView(pid: string, request?: Request): Promise<SavedView> {
    return savedViewForView(
      responseData(
        await internalRequest('get', '/api/views/{pid}', { path: { pid }, request }),
        'Failed to fetch view',
      ),
    );
  }
  async getAccessibleViews(query: SavedViewQueryParams, request?: Request): Promise<SavedView[]> {
    return views(
      responseData(
        await internalRequest('get', '/api/views/accessible', { query, request }),
        'Failed to fetch accessible views',
      ),
    );
  }
  async getPersonalViews(query: SavedViewQueryParams, request?: Request): Promise<SavedView[]> {
    return views(
      responseData(
        await internalRequest('get', '/api/views/personal', { query, request }),
        'Failed to fetch personal views',
      ),
    );
  }
  async getGlobalViews(query: SavedViewQueryParams, request?: Request): Promise<SavedView[]> {
    return views(
      responseData(
        await internalRequest('get', '/api/views/global', { query, request }),
        'Failed to fetch global views',
      ),
    );
  }
  async getTeamViews(query: SavedViewQueryParams, request?: Request): Promise<SavedView[]> {
    return views(
      responseData(
        await internalRequest('get', '/api/views/team', { query, request }),
        'Failed to fetch team views',
      ),
    );
  }
  async getChipPins(query: SavedViewQueryParams, request?: Request): Promise<ChipPin[]> {
    const data = responseData(
      await internalRequest('get', '/api/views/chip-pins', { query, request }),
      'Failed to fetch quick-filter pins',
    );
    if (!Array.isArray(data)) throw new Error('Invalid saved view pin response');
    return data.map((item) => {
      if (
        !item.viewPid ||
        typeof item.viewPid !== 'string' ||
        typeof item.order !== 'number' ||
        !Number.isFinite(item.order)
      ) {
        throw new Error('Invalid saved view pin identity or order');
      }
      return { ...item, viewPid: item.viewPid, order: item.order };
    });
  }
  async pinView(viewPid: string, options: ChipPinOptions = {}, request?: Request): Promise<void> {
    const body = {
      order: options.order,
      ...(options.scope === 'team' ? { scope: 'team', teamId: options.teamId } : {}),
    };
    ensureSuccess(
      await internalRequest('post', '/api/views/{viewPid}/pin', {
        path: { viewPid },
        body,
        request,
      }),
      'Failed to pin view',
    );
  }
  async unpinView(
    viewPid: string,
    options: Pick<ChipPinOptions, 'scope' | 'teamId'> = {},
    request?: Request,
  ): Promise<void> {
    const query = options.scope === 'team' ? { scope: 'team', teamId: options.teamId } : undefined;
    ensureSuccess(
      await internalRequest('delete', '/api/views/{viewPid}/pin', {
        path: { viewPid },
        query,
        request,
      }),
      'Failed to unpin view',
    );
  }
  async getMyTeams(request?: Request): Promise<SavedViewTeamOption[]> {
    const data = responseData(
      await internalRequest('get', '/api/views/my-teams', { request }),
      'Failed to fetch saved view teams',
    );
    if (!Array.isArray(data)) throw new Error('Invalid saved view team response');
    return data.map((team) => {
      if (typeof team.teamPid !== 'string' || !team.teamPid)
        throw new Error('Invalid saved view team identity');
      return {
        pid: team.teamPid,
        name: team.teamName || team.teamCode || 'Team',
        role: team.role ?? undefined,
      };
    });
  }
  async searchUsers(keyword: string, size = 10, request?: Request): Promise<SavedViewUserOption[]> {
    const data = responseData(
      await internalRequest('post', '/api/tenant/members/search', {
        body: {
          pageNum: 1,
          pageSize: size,
          status: 'active',
          ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
        },
        request,
      }),
      'Failed to search users',
    );
    if (!Array.isArray(data.records)) throw new Error('Invalid collaborator search pagination');
    return data.records.flatMap((record) => {
      const user = record.user;
      if (user == null) return [];
      if (typeof user.pid !== 'string' || !user.pid)
        throw new Error('Invalid collaborator identity');
      return [
        {
          pid: user.pid,
          displayName: user.realName || user.username || user.email || undefined,
          email: user.email ?? undefined,
          avatarUrl: user.avatar ?? undefined,
        },
      ];
    });
  }
  async getDefaultView(query: SavedViewQueryParams, request?: Request): Promise<SavedView | null> {
    const result = await internalRequest('get', '/api/views/default', { query, request });
    ensureSuccess(result, 'Failed to fetch default view');
    return result.data == null ? null : savedViewForView(result.data);
  }
  async createView(data: SavedViewCreateRequest, request?: Request): Promise<SavedView> {
    const body = { ...data, viewConfig: savedViewConfigToWire(data.viewConfig) };
    return savedViewForView(
      responseData(
        await internalRequest('post', '/api/views', { body, request }),
        'Failed to create view',
      ),
    );
  }
  async autoSave(data: SavedViewAutoSaveRequest, request?: Request): Promise<SavedView> {
    const body = { ...data, viewConfig: savedViewConfigToWire(data.viewConfig) };
    return savedViewForView(
      responseData(
        await internalRequest('post', '/api/views/auto-save', { body, request }),
        'Failed to auto-save view',
      ),
    );
  }
  async updateView(
    pid: string,
    data: SavedViewUpdateRequest,
    request?: Request,
  ): Promise<SavedView> {
    const body = { ...data, viewConfig: savedViewConfigToWire(data.viewConfig) };
    return savedViewForView(
      responseData(
        await internalRequest('put', '/api/views/{pid}', { path: { pid }, body, request }),
        'Failed to update view',
      ),
    );
  }
  async deleteView(pid: string, request?: Request): Promise<void> {
    ensureSuccess(
      await internalRequest('delete', '/api/views/{pid}', { path: { pid }, request }),
      'Failed to delete view',
    );
  }
  async setDefaultView(pid: string, request?: Request): Promise<SavedView> {
    return savedViewForView(
      responseData(
        await internalRequest('post', '/api/views/{pid}/set-default', { path: { pid }, request }),
        'Failed to set default view',
      ),
    );
  }
  async duplicateView(pid: string, newName: string, request?: Request): Promise<SavedView> {
    return savedViewForView(
      responseData(
        await internalRequest('post', '/api/views/{pid}/duplicate', {
          path: { pid },
          body: { name: newName },
          request,
        }),
        'Failed to duplicate view',
      ),
    );
  }
  async copyToPersonal(
    pid: string,
    data: SavedViewCopyToPersonalRequest = {},
    request?: Request,
  ): Promise<SavedView> {
    const body = { ...data, viewConfig: savedViewConfigToWire(data.viewConfig) };
    return savedViewForView(
      responseData(
        await internalRequest('post', '/api/views/{pid}/copy-to-personal', {
          path: { pid },
          body,
          request,
        }),
        'Failed to copy view',
      ),
    );
  }
  async getAuditEvents(pid: string, request?: Request): Promise<SavedViewAuditEvent[]> {
    const data = responseData(
      await internalRequest('get', '/api/views/{pid}/audit-events', { path: { pid }, request }),
      'Failed to fetch view audit events',
    );
    if (!Array.isArray(data)) throw new Error('Invalid saved view audit response');
    return data.map((item) => ({ ...presentFields(item), metadata: item.metadata }));
  }
  async checkCapability(
    data: SavedViewCapabilityCheckRequest,
    request?: Request,
  ): Promise<SavedViewCapabilityCheckResponse> {
    const body = { ...data, viewConfig: savedViewConfigToWire(data.viewConfig) };
    return savedViewCapabilityForView(
      responseData(
        await internalRequest('post', '/api/views/capability-check', { body, request }),
        'Failed to check view capability',
      ),
    );
  }
  async shareView(
    viewPid: string,
    body: SavedViewShareRequest = {},
    request?: Request,
  ): Promise<SavedViewShareResult> {
    const data = responseData(
      await internalRequest('post', '/api/views/{viewPid}/share', {
        path: { viewPid },
        body,
        request,
      }),
      'Failed to create share link',
    );
    if (
      typeof data.token !== 'string' ||
      !data.token ||
      typeof data.shareUrl !== 'string' ||
      !data.shareUrl
    )
      throw new Error('Invalid saved view share link');
    return data;
  }
  async revokeShare(viewPid: string, request?: Request): Promise<void> {
    ensureSuccess(
      await internalRequest('delete', '/api/views/{viewPid}/share', { path: { viewPid }, request }),
      'Failed to revoke share link',
    );
  }
  async getShareStatus(viewPid: string, request?: Request): Promise<SavedViewShareStatus> {
    const data = responseData(
      await internalRequest('get', '/api/views/{viewPid}/share/status', {
        path: { viewPid },
        request,
      }),
      'Failed to fetch share status',
    );
    if (typeof data.shared !== 'boolean') throw new Error('Invalid saved view share status');
    return data;
  }
  async checkNameUnique(
    query: NonNullable<InternalRequestInput<'get', '/api/views/check-name'>['query']>,
    request?: Request,
  ): Promise<boolean> {
    const data = responseData(
      await internalRequest('get', '/api/views/check-name', { query, request }),
      'Failed to check view name',
    );
    if (typeof data !== 'boolean') throw new Error('Invalid saved view name check');
    return data;
  }
}

/** The public endpoint shares configuration, not business records. */
export async function loadPublicSharedView(
  shareToken: string,
  password?: string,
  signal?: AbortSignal,
): Promise<Result<PublicSharedView>> {
  const result = await internalRequest('get', '/api/views/shared/{shareToken}', {
    path: { shareToken },
    query: password ? { password } : undefined,
    options: { skipAutoToken: true, signal },
  });
  if (!ResultHelper.isSuccess(result) || result.data == null) return { ...result, data: null };
  const data = result.data;
  if (typeof data.name !== 'string' || !data.name || !data.viewConfig)
    throw new Error('Invalid public view configuration');
  const config = savedViewJson(data.viewConfig);
  if (!config || typeof config !== 'object' || Array.isArray(config))
    throw new Error('Invalid public view configuration');
  return { ...result, data: { ...data, name: data.name, viewConfig: config } };
}

export const savedViewService = new SavedViewService();
