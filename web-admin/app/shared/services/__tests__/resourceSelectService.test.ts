/**
 * Tests for resourceSelectService error propagation.
 *
 * Verifies that fetch failures propagate as thrown errors rather than being
 * silently swallowed and returning an empty array (§8 / §10).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

// ---------------------------------------------------------------------------
// Mock fetchResult at the module level so it is replaced before any import
// of the service under test. Use vi.hoisted so the variable is available
// when vi.mock factory runs.
// ---------------------------------------------------------------------------
const { fetchResultMock } = vi.hoisted(() => ({ fetchResultMock: vi.fn() }));
const { workflowProvider } = vi.hoisted(() => ({ workflowProvider: vi.fn() }));

vi.mock('~/framework/bootstrap', () => ({
  getKernel: () => ({ contributionRegistry: { getPrimaryService: workflowProvider } }),
}));

vi.mock('~/shared/services/http-client/HttpClient', () => ({ fetchResult: fetchResultMock }));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: fetchResultMock,
}));

import {
  fetchPageOptions,
  fetchPageNavigationOptions,
  fetchDashboardOptions,
  fetchAutomationOptions,
  fetchCommandOptions,
  fetchModelOptions,
  fetchFieldOptions,
  fetchFieldDictOptions,
  fetchDictOptions,
  fetchSemanticModelOptions,
  fetchWorkflowOptions,
} from '../resourceSelectService';

describe('product-owned workflow discovery', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
    workflowProvider.mockReset();
  });

  it('does not issue product HTTP requests when no workflow provider is installed', async () => {
    expect(await fetchWorkflowOptions()).toEqual([]);
    expect(workflowProvider).toHaveBeenCalledWith('aura.resource.workflow');
    expect(fetchResultMock).not.toHaveBeenCalled();
  });

  it('delegates to the installed product provider without a platform fallback', async () => {
    const options = [{ label: 'Approval', value: 'approval' }];
    const fetchOptions = vi.fn().mockResolvedValue(options);
    workflowProvider.mockReturnValue({ provider: { fetchOptions } });
    expect(await fetchWorkflowOptions()).toEqual(options);
    expect(fetchOptions).toHaveBeenCalledOnce();
    expect(fetchResultMock).not.toHaveBeenCalled();
  });

  it('propagates provider failures without claiming an empty list or retrying', async () => {
    const fetchOptions = vi.fn().mockRejectedValue(new Error('Product unavailable'));
    workflowProvider.mockReturnValue({ provider: { fetchOptions } });
    await expect(fetchWorkflowOptions()).rejects.toThrow('Product unavailable');
    expect(fetchOptions).toHaveBeenCalledOnce();
    expect(fetchResultMock).not.toHaveBeenCalled();
  });
});

describe('resourceSelectService — error propagation', () => {
  it('navigation targets use published default model-list paths, never page PIDs', async () => {
    fetchResultMock.mockResolvedValue({code:'0',data:{total:3,records:[
      {pid:'page-pid',pageKey:'tpm_acceptance_list',name:'交付验收',modelCode:'tpm_acceptance',kind:'list'},
      {pid:'form-pid',modelCode:'tpm_acceptance',kind:'form'},
      {pid:'profile-pid',modelCode:'tpm_acceptance',kind:'list',profile:'special'},
    ]}});
    expect(await fetchPageNavigationOptions()).toEqual([{value:'/p/tpm_acceptance',label:'交付验收',description:'/p/tpm_acceptance'}]);
    expect(fetchResultMock).toHaveBeenCalledWith('/api/pages?kind=list&isPublished=true&pageNum=1&pageSize=100');
  });
  it('navigation discovery rejects denied or incomplete inventories rather than pretending no pages exist', async () => {
    fetchResultMock.mockResolvedValueOnce({code:'403'});
    await expect(fetchPageNavigationOptions()).rejects.toThrow('Unable to load');
    fetchResultMock.mockResolvedValueOnce({code:'0',data:{total:2,records:[]}});
    await expect(fetchPageNavigationOptions()).rejects.toThrow('incomplete');
  });
  beforeEach(() => {
    fetchResultMock.mockReset();
  });

  it('fetchPageOptions: rejects on network error, does NOT return []', async () => {
    fetchResultMock.mockRejectedValue(new Error('Network error'));
    await expect(fetchPageOptions()).rejects.toThrow('Network error');
  });

  it('fetchDashboardOptions: rejects on network error, does NOT return []', async () => {
    fetchResultMock.mockRejectedValue(new Error('Network error'));
    await expect(fetchDashboardOptions()).rejects.toThrow('Network error');
  });

  it('fetchAutomationOptions: rejects on network error, does NOT return []', async () => {
    fetchResultMock.mockRejectedValue(new Error('Network error'));
    await expect(fetchAutomationOptions()).rejects.toThrow('Network error');
  });

  it('fetchCommandOptions: rejects on network error, does NOT return []', async () => {
    fetchResultMock.mockRejectedValue(new Error('Network error'));
    await expect(fetchCommandOptions()).rejects.toThrow('Network error');
  });

  it('fetchModelOptions: rejects on network error, does NOT return []', async () => {
    fetchResultMock.mockRejectedValue(new Error('Network error'));
    await expect(fetchModelOptions()).rejects.toThrow('Network error');
  });

  it('fetchFieldOptions: rejects on network error, does NOT return []', async () => {
    fetchResultMock.mockRejectedValue(new Error('Network error'));
    await expect(fetchFieldOptions('some_model')).rejects.toThrow('Network error');
  });

  it('fetchDictOptions: rejects on network error, does NOT return []', async () => {
    fetchResultMock.mockRejectedValue(new Error('Network error'));
    await expect(fetchDictOptions('some_dict')).rejects.toThrow('Network error');
  });

  it('fetchSemanticModelOptions: rejects on network error, does NOT return []', async () => {
    fetchResultMock.mockRejectedValue(new Error('Network error'));
    await expect(fetchSemanticModelOptions()).rejects.toThrow('Network error');
  });

  it('fetchPageOptions: returns empty array on empty records (not a fetch error)', async () => {
    fetchResultMock.mockResolvedValue({ data: { records: [] } });
    const result = await fetchPageOptions();
    expect(result).toEqual([]);
  });

  it('fetchModelOptions: returns mapped options on success', async () => {
    fetchResultMock.mockResolvedValue({
      data: {
        records: [{ pid: 'p1', code: 'order', displayName: 'Order', description: 'Sales order' }],
      },
    });
    const result = await fetchModelOptions();
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({ label: 'Order', value: 'order' });
  });

  it('fetchFieldOptions: returns [] immediately when modelCode is empty (no fetch)', async () => {
    const result = await fetchFieldOptions('');
    expect(result).toEqual([]);
    expect(fetchResultMock).not.toHaveBeenCalled();
  });

  it('fetchDictOptions: returns [] immediately when dictCode is empty (no fetch)', async () => {
    const result = await fetchDictOptions('');
    expect(result).toEqual([]);
    expect(fetchResultMock).not.toHaveBeenCalled();
  });

  it('fetchFieldOptions: rejects when second fetch (fields) fails', async () => {
    // First call resolves (model lookup), second rejects (fields fetch)
    fetchResultMock
      .mockResolvedValueOnce({ data: { pid: 'model-pid-1' } })
      .mockRejectedValueOnce(new Error('Fields fetch failed'));
    await expect(fetchFieldOptions('order')).rejects.toThrow('Fields fetch failed');
  });
});

describe('model field dictionary resolution', () => {
  beforeEach(() => fetchResultMock.mockReset());
  it('loads the declared dictionary instead of assuming the field code is a dictionary code', async () => {
    fetchResultMock
      .mockResolvedValueOnce({ code: '0', data: { pid: 'task-model' } })
      .mockResolvedValueOnce({
        code: '0',
        data: [{ code: 'task_state', dataType: 'enum', dictCode: 'task_lifecycle' }],
      })
      .mockResolvedValueOnce({
        code: '0',
        data: { items: [{ value: 'in_review', label: '审核中' }] },
      });
    expect(await fetchFieldDictOptions('task', 'task_state')).toEqual([
      { value: 'in_review', label: '审核中', description: undefined },
    ]);
    expect(fetchResultMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/meta/models/code/task',
      '/api/meta/models/task-model/fields',
      '/api/meta/dict/by-code/task_lifecycle/data',
    ]);
  });
  it('rejects an unbound field without falling back to its field code', async () => {
    fetchResultMock
      .mockResolvedValueOnce({ data: { pid: 'task-model' } })
      .mockResolvedValueOnce({ data: [{ code: 'task_state', dataType: 'enum' }] });
    await expect(fetchFieldDictOptions('task', 'task_state')).rejects.toThrow('no dictionary');
    expect(fetchResultMock).toHaveBeenCalledTimes(2);
  });
  it('surfaces a business-error envelope instead of claiming an empty dictionary', async () => {
    fetchResultMock.mockResolvedValueOnce({ code: '1', data: null, context: 'missing dictionary' });
    await expect(fetchDictOptions('missing')).rejects.toThrow('could not be loaded');
  });
});
