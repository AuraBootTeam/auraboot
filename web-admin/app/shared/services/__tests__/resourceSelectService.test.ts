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

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: fetchResultMock,
}));

import {
  fetchPageOptions,
  fetchDashboardOptions,
  fetchProcessOptions,
  fetchAutomationOptions,
  fetchCommandOptions,
  fetchModelOptions,
  fetchFieldOptions,
  fetchDictOptions,
  fetchSemanticModelOptions,
} from '../resourceSelectService';

describe('resourceSelectService — error propagation', () => {
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

  it('fetchProcessOptions: rejects on network error, does NOT return []', async () => {
    fetchResultMock.mockRejectedValue(new Error('Network error'));
    await expect(fetchProcessOptions()).rejects.toThrow('Network error');
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
        records: [
          { pid: 'p1', code: 'order', displayName: 'Order', description: 'Sales order' },
        ],
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
