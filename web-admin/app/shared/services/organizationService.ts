/** Department trees use generated wire fields; contribution providers share the same boundary. */
import { internalRequest, type InternalResponseData } from './http-client/internal-api';
import { ResultHelper, type Result } from '~/utils/type';
import { getKernel } from '~/framework/bootstrap';

export type DepartmentTree = InternalResponseData<'get', '/api/org/departments/tree'>;
export type DepartmentTreeNode = DepartmentTree[number];

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function assertDepartmentTree(value: unknown): asserts value is DepartmentTree {
  if (!Array.isArray(value)) throw new Error('Invalid department tree response');
  const pending: unknown[] = [...value], identities = new Set<string>();
  while (pending.length) {
    const node = pending.pop();
    if (!record(node) || typeof node.pid !== 'string' || !node.pid || identities.has(node.pid))
      throw new Error('Invalid department tree identity');
    identities.add(node.pid);
    if (typeof node.name !== 'string'
        || !(node.parentPid === null || typeof node.parentPid === 'string')
        || typeof node.employeeCount !== 'number' || !Number.isInteger(node.employeeCount)
        || node.employeeCount < 0 || !Array.isArray(node.children))
      throw new Error('Invalid department tree node');
    pending.push(...node.children);
  }
}

export const organizationService = {
  async getDepartmentTree(): Promise<Result<DepartmentTree>> {
    const provider = getKernel().contributionRegistry.getPrimaryService('organization')?.provider as
      | { getDepartmentTree?: () => Promise<Result<DepartmentTree>> }
      | undefined;
    const result = provider?.getDepartmentTree
      ? await provider.getDepartmentTree()
      : await internalRequest('get', '/api/org/departments/tree', {});
    if (!ResultHelper.isSuccess(result)) return { ...result, data: null };
    assertDepartmentTree(result.data);
    return result;
  },
};
