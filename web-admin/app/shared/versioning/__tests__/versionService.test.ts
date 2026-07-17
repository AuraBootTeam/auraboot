import { afterEach, describe, expect, it, vi } from 'vitest';
import { bpmnVersionService } from '../versionService';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('bpmnVersionService', () => {
  it('does not expose BPMN process descriptions as version history notes', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: 0,
        message: 'OK',
        data: [
          {
            pid: 'version-1',
            processKey: 'wd_leave_approval',
            processName: '请假审批',
            description: 'OSS workflow demo: leave request approval route',
            status: 'deployed',
            version: 12,
            isCurrent: true,
            designerJson: null,
            createdAt: '2026-07-07T01:00:00Z',
            updatedAt: '2026-07-07T02:00:00Z',
          },
        ],
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const versions = await bpmnVersionService.getHistory('wd_leave_approval');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/bpm/process-definitions/key/wd_leave_approval/versions',
      expect.objectContaining({
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      }),
    );
    expect(versions).toHaveLength(1);
    expect(versions[0]).toMatchObject({
      pid: 'version-1',
      resourceType: 'bpm_process_definition',
      resourceId: 'wd_leave_approval',
      version: '12',
      operation: 'PUBLISH',
    });
    expect(versions[0].description).toBeUndefined();
  });
});
