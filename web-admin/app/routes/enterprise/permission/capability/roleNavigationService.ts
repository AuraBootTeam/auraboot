import {
  internalRequest,
  type InternalResponseData,
  type InternalRequestInput,
} from '~/shared/services/http-client/internal-api';
import { ResultHelper, type Result } from '~/utils/type';
import { AuthorizationConflict } from './authorizationDraftService';
export type RoleNavigationSelection = InternalResponseData<
  'get',
  '/api/navigation/roles/{rolePid}'
>;
export type RoleNavigationChoice = RoleNavigationSelection['menus'][number];
export type EntryRoles = InternalResponseData<'get', '/api/navigation/entries/{pid}/roles'>;
export type NavigationPreviewOptions = InternalResponseData<
  'get',
  '/api/navigation/preview/options'
>;
export type NavigationUserPreview = InternalResponseData<'post', '/api/navigation/preview'>;
export type NavigationPreviewRequest = InternalRequestInput<
  'post',
  '/api/navigation/preview'
>['body'];
function unwrap<T>(result: Result<T>): T {
  if (String(result.code) === '41001')
    throw new AuthorizationConflict('Navigation baseline changed');
  if (!ResultHelper.isSuccess(result) || result.data == null)
    throw new Error(result.message || 'Cannot confirm navigation configuration');
  return result.data;
}
export const roleNavigationService = {
  async entryRoles(pid: string) {
    return unwrap(
      await internalRequest('get', '/api/navigation/entries/{pid}/roles', { path: { pid } }),
    );
  },
  async saveEntryRoles(pid: string, expectedRevision: string, rolePids: string[]) {
    return unwrap(
      await internalRequest('put', '/api/navigation/entries/{pid}/roles', {
        path: { pid },
        body: { expectedRevision, rolePids },
      }),
    );
  },
  async options() {
    return unwrap(await internalRequest('get', '/api/navigation/preview/options', {}));
  },
  async preview(body: NavigationPreviewRequest) {
    return unwrap(await internalRequest('post', '/api/navigation/preview', { body }));
  },
  async audit(rolePid: string) {
    return unwrap(
      await internalRequest('get', '/api/navigation/roles/{rolePid}/audit', { path: { rolePid } }),
    );
  },
};
