import {
  internalRequest,
  type InternalRequestInput,
  type InternalResponseData,
} from '~/shared/services/http-client/internal-api';
import { ResultHelper, type Result } from '~/utils/type';
import type { PermissionMatrixDTO } from '../types';

const path = '/api/permission/roles/{rolePid}/draft' as const;
export type AuthorizationSnapshot = InternalResponseData<'get', typeof path>;
export type AuthorizationDraft = InternalRequestInput<'put', typeof path>['body'];
export type AuthorizationPreview = InternalResponseData<
  'post',
  '/api/permission/roles/{rolePid}/draft/preview'
>;
export type ScopeChange = NonNullable<AuthorizationDraft['scopeChanges']>[number];

export class AuthorizationConflict extends Error {}

function unwrap<T>(result: Result<T>): T {
  if (String(result.code) === '41001')
    throw new AuthorizationConflict('Authorization baseline changed');
  if (!ResultHelper.isSuccess(result) || result.data == null) {
    throw new Error(result.message || 'Could not confirm authorization configuration');
  }
  return result.data;
}

/** Validate the older matrix producer's optional schema at the UI boundary. */
export function draftMatrix(matrix: AuthorizationSnapshot['matrix']): PermissionMatrixDTO {
  function required<T>(value: T | undefined | null): T {
    if (value == null) throw new Error('Incomplete authorization matrix');
    return value;
  }
  return {
    modules: required(matrix.modules).map((module) => ({
      moduleCode: required(module.moduleCode),
      moduleName: required(module.moduleName),
      resources: required(module.resources).map((resource) => ({
        resourceCode: required(resource.resourceCode),
        resourceName: required(resource.resourceName),
        actions: required(resource.actions).map((action) => ({
          ...action,
          permissionId: required(action.permissionId),
          permissionPid: required(action.permissionPid),
          code: required(action.code),
          action: required(action.action),
          label: required(action.label),
          granted: required(action.granted),
          supported: required(action.supported),
        })),
      })),
    })),
  };
}

export const authorizationDraftService = {
  async fieldPolicies() {
    return unwrap(await internalRequest('get', '/api/meta/data-permissions', {}));
  },
  async read(rolePid: string) {
    return unwrap(await internalRequest('get', path, { path: { rolePid } }));
  },
  async preview(rolePid: string, body: AuthorizationDraft) {
    return unwrap(
      await internalRequest('post', '/api/permission/roles/{rolePid}/draft/preview', {
        path: { rolePid },
        body,
      }),
    );
  },
  async save(rolePid: string, body: AuthorizationDraft) {
    return unwrap(await internalRequest('put', path, { path: { rolePid }, body }));
  },
};
