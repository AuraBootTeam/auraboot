import { beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { action } from '../api.xy.teacher-code';

vi.mock('~/shared/services/session', () => ({
  getTokenFromRequest: () => Promise.resolve('signed-in-token'),
}));

function backendResult(data: unknown) {
  return new Response(JSON.stringify({ code: 200, data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

function request(operation: string) {
  return new Request('http://localhost/_action/xy/teacher-code', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: operation }),
  });
}

describe('school teacher code BFF contract', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it('mounts outside the production Spring Boot proxy namespace', () => {
    const routesSource = readFileSync(join(process.cwd(), 'app/routes.ts'), 'utf8');
    expect(routesSource).toContain(
      "route('/_action/xy/teacher-code', './routes/api.xy.teacher-code.tsx')",
    );
    expect(routesSource).not.toContain("route('/api/xy/teacher-code'");
  });

  it('hard-codes the xy_teacher role and never accepts a browser role list', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(backendResult('TEACH-NEW'))
      .mockResolvedValueOnce(backendResult({ code: 'TEACH-NEW', expiredAt: '2026-10-21' }));
    vi.stubGlobal('fetch', fetchMock);

    const response = await action({ request: request('generate'), params: {}, context: {} } as unknown as Parameters<typeof action>[0]);
    expect(response.status).toBe(200);
    expect(fetchMock.mock.calls[0][0]).toContain(
      '/api/tenant/invite-code/generate?expiryDays=30&roleCodes=xy_teacher',
    );
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'POST' });
  });

  it('revokes the old school teacher code after a reset', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(backendResult({ code: 'TEACH-OLD' }))
      .mockResolvedValueOnce(backendResult('TEACH-NEW'))
      .mockResolvedValueOnce(backendResult(true))
      .mockResolvedValueOnce(backendResult({ code: 'TEACH-NEW' }));
    vi.stubGlobal('fetch', fetchMock);

    const response = await action({ request: request('reset'), params: {}, context: {} } as unknown as Parameters<typeof action>[0]);
    expect(response.status).toBe(200);
    expect(fetchMock.mock.calls[2][0]).toContain('/api/tenant/invite-code/revoke?code=TEACH-OLD');
  });

  it('rejects unsupported operations before calling the backend', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const response = await action({ request: request('admin'), params: {}, context: {} } as unknown as Parameters<typeof action>[0]);
    expect(response.status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
