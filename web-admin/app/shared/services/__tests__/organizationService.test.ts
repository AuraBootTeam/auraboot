import { beforeEach, describe, expect, it, vi } from 'vitest';
const { transport, primary } = vi.hoisted(() => ({ transport: vi.fn(), primary: vi.fn() }));
vi.mock('../http-client/HttpClient', () => ({ fetchResult: transport }));
vi.mock('~/framework/bootstrap', () => ({ getKernel: () => ({ contributionRegistry: { getPrimaryService: primary } }) }));
import { organizationService, type DepartmentTreeNode } from '../organizationService';

const child = { pid: 'child-pid', name: 'Child', parentPid: 'root-pid', employeeCount: 2, children: [] } satisfies DepartmentTreeNode;
const root = { pid: 'root-pid', name: 'Root', parentPid: null, employeeCount: 0, children: [child] } satisfies DepartmentTreeNode;
const ok = (data: unknown) => ({ code: '0', success: true, data });

describe('generated organization tree boundary', () => {
  beforeEach(() => { transport.mockReset(); primary.mockReset(); });
  it('uses the generated GET operation and preserves nullable roots, leaf arrays and public PIDs', async () => {
    transport.mockImplementation(async (url, options) => {
      if (url !== '/api/org/departments/tree' || options.method !== 'get') throw new Error('Unexpected HTTP operation');
      return ok([root]);
    });
    const result = await organizationService.getDepartmentTree();
    expect(result.data).toEqual([root]);
    expect(result.data?.[0].parentPid).toBeNull();
    expect(result.data?.[0].children[0].pid).toBe('child-pid');
    expect(result.data?.[0].children[0].children).toEqual([]);
  });
  it('accepts an empty tree and preserves a failed envelope without fabricating successful empty data', async () => {
    transport.mockResolvedValueOnce(ok([]));
    expect((await organizationService.getDepartmentTree()).data).toEqual([]);
    transport.mockResolvedValueOnce({ code: '403', success: false, desc: 'forbidden', data: { arbitrary: true } });
    expect(await organizationService.getDepartmentTree()).toMatchObject({ code: '403', success: false, desc: 'forbidden', data: null });
  });
  it.each([null, {}, { records: [root] }, [null], [{ ...root, pid: '' }], [{ ...root, name: null }],
    [{ ...root, parentPid: undefined }], [{ ...root, children: null }], [{ ...root, employeeCount: -1 }],
    [{ ...root, employeeCount: 0.5 }], [root, root], [{ ...root, children: [{ ...child, pid: root.pid }] }]])(
    'rejects malformed or duplicate identity trees %j', async (data) => {
      transport.mockResolvedValue(ok(data));
      await expect(organizationService.getDepartmentTree()).rejects.toThrow('Invalid department tree');
    },
  );
  it('preserves the contribution provider and validates its result through the same boundary', async () => {
    const getDepartmentTree = vi.fn().mockResolvedValue(ok([root]));
    primary.mockReturnValue({ provider: { getDepartmentTree } });
    expect((await organizationService.getDepartmentTree()).data).toEqual([root]);
    expect(transport).not.toHaveBeenCalled();
    getDepartmentTree.mockResolvedValue(ok([{ ...root, children: null }]));
    await expect(organizationService.getDepartmentTree()).rejects.toThrow('Invalid department tree');
  });
});
