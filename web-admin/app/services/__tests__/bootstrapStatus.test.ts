import { afterEach, describe, expect, it, vi } from 'vitest';

describe('bootstrapStatus', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it('uses a same-origin URL when no server-only BFF URL is configured', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0',
        data: { initialized: true, inProgress: false, missingParts: [] },
      }),
    });
    vi.stubEnv('BFF_INTERNAL_URL', '');
    vi.stubGlobal('fetch', fetchMock);

    const { fetchBootstrapStatus } = await import('../bootstrapStatus');
    await expect(fetchBootstrapStatus()).resolves.toMatchObject({ initialized: true });
    expect(fetchMock).toHaveBeenCalledWith('/api/bootstrap/status');
  });
});
