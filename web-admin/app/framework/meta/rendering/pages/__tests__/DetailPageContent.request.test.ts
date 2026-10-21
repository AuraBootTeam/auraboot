import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fetchResult } from '~/shared/services/http-client';
import { fetchDetailRecord, isSuccessfulDetailResult } from '../DetailPageContent';
vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
const fetchMock = vi.mocked(fetchResult);
const schema = { extension: { dataSource: { type: 'api', method: 'post',
  endpoint: '/api/meta/commands/execute/example:view', adaptor: 'commandRecord',
  params: { targetRecordPid: '${$page.recordPid}', payload: { context: '${state.routeContext.scope}' } } } } };
beforeEach(() => fetchMock.mockReset());
describe('detail configured command transport', () => {
  it('sends the route pid and evaluated params through the existing fetch transport', async () => {
    fetchMock.mockResolvedValue({ code: '0', data: { phaseReached: 'completed', data: { master: { pid: 'PUBLIC' }, lines: [{ name: 'Material' }] } } } as any);
    const result = await fetchDetailRecord(schema, 'example', 'PUBLIC', 'token', '?routeContext=' + encodeURIComponent(JSON.stringify({ scope: 'owned' })));
    expect(fetchMock).toHaveBeenCalledWith('/api/meta/commands/execute/example:view', {
      method: 'post', token: 'token', params: { targetRecordPid: 'PUBLIC', payload: { context: 'owned' } },
    });
    expect(result.data).toEqual({ master: { pid: 'PUBLIC' }, lines: [{ name: 'Material' }] });
  });
  it.each(['prepared', 'failed', undefined])('rejects incomplete command phase %s rather than exposing nested content', async (phaseReached) => {
    fetchMock.mockResolvedValue({ code: '0', data: { phaseReached, data: { master: { pid: 'PUBLIC' } } } } as any);
    await expect(fetchDetailRecord(schema, 'example', 'PUBLIC')).rejects.toThrow();
  });
  it('keeps ordinary GET endpoint and unwrapped result compatible', async () => {
    const original = { code: '0', data: { pid: 'PUBLIC' } }; fetchMock.mockResolvedValue(original as any);
    expect(await fetchDetailRecord(null, 'example', 'PUBLIC')).toBe(original);
    expect(fetchMock).toHaveBeenCalledWith('/api/dynamic/example/PUBLIC', { method: 'get', token: undefined });
  });
  it('keeps actual authorization failures and never unwraps a forged completed body', async () => {
    const denied = { code: '0', httpStatus: 403, data: { phaseReached: 'completed', data: { pid: 'forged' } } };
    fetchMock.mockResolvedValue(denied as any);
    expect(await fetchDetailRecord(schema, 'example', 'PUBLIC')).toBe(denied);
    expect(isSuccessfulDetailResult(denied)).toBe(false);
  });
  it.each([null, [], 'raw'])('rejects a completed non-object record %s', async (data) => {
    fetchMock.mockResolvedValue({ code: '0', data: { phaseReached: 'completed', data } } as any);
    await expect(fetchDetailRecord(schema, 'example', 'PUBLIC')).rejects.toThrow();
  });
  it('rejects malformed params before any request', async () => {
    await expect(fetchDetailRecord({ extension: { dataSource: { ...schema.extension.dataSource, params: [] } } }, 'example', 'PUBLIC')).rejects.toThrow();
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it('refuses explicit failure even with success code and completed data', async () => {
    const failed = { code: '0', success: false, data: { phaseReached: 'completed', data: { pid: 'forged' } } };
    fetchMock.mockResolvedValue(failed as any);
    expect(await fetchDetailRecord(schema, 'example', 'PUBLIC')).toBe(failed);
    expect(isSuccessfulDetailResult(failed)).toBe(false);
  });

});
