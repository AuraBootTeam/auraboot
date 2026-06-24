import { describe, expect, it, vi } from 'vitest';
import { loader as dynamicEditRedirectLoader } from '../dynamic.$tableName.edit';
import { loader as dynamicViewRedirectLoader } from '../dynamic.$tableName.view';
import { loader as customEditLoader } from '../p.c.$pageKey.edit';
import { loader as editLoader } from '../p.$pageKey.edit';
import { loader as viewLoader } from '../p.$pageKey.view';
import { fetchResult } from '~/shared/services/http-client';
import { getTokenFromRequest } from '~/shared/services/session';

vi.mock('~/framework/meta/rendering/pages/DynamicPageRenderer', () => ({
  DynamicPageRenderer: () => null,
}));

vi.mock('~/shared/services/session', () => ({
  getTokenFromRequest: vi.fn(async () => 'test-token'),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(async () => ({
    code: '0',
    data: { modelCode: 'sla_config', kind: 'form' },
  })),
}));

const RECORD_PID = '01KVVQQXZ48RQ1WVWNHYA10W15';

function routeArgs(params: Record<string, string>) {
  return {
    params,
    request: new Request(`http://localhost/p/sla_config/edit/${RECORD_PID}`),
    context: {},
  } as any;
}

describe('dynamic record route params', () => {
  it('loads the standard edit route when the manifest supplies recordId', async () => {
    await expect(
      editLoader(routeArgs({ pageKey: 'sla_config', recordId: RECORD_PID })),
    ).resolves.toMatchObject({
      tableName: 'sla_config',
      recordPid: RECORD_PID,
      token: 'test-token',
    });
    expect(getTokenFromRequest).toHaveBeenCalled();
  });

  it('loads the standard view route when the manifest supplies recordId', async () => {
    await expect(
      viewLoader(routeArgs({ pageKey: 'sla_config', recordId: RECORD_PID })),
    ).resolves.toMatchObject({
      tableName: 'sla_config',
      recordPid: RECORD_PID,
      token: 'test-token',
    });
  });

  it('loads custom edit routes when the manifest supplies recordId', async () => {
    await expect(
      customEditLoader(routeArgs({ pageKey: 'sla_config_custom', recordId: RECORD_PID })),
    ).resolves.toMatchObject({
      pageKey: 'sla_config_custom',
      tableName: 'sla_config',
      recordPid: RECORD_PID,
      token: 'test-token',
    });
    expect(fetchResult).toHaveBeenCalledWith(
      '/api/pages/key/sla_config_custom',
      expect.objectContaining({ method: 'get', token: 'test-token' }),
      expect.any(Request),
    );
  });

  it('redirects legacy dynamic edit routes with recordId preserved', async () => {
    const response = (await dynamicEditRedirectLoader(
      routeArgs({ tableName: 'sla_config', recordId: RECORD_PID }),
    )) as { status: number; url: string };

    expect(response.status).toBe(301);
    expect(response.url).toBe(`/p/sla_config/edit/${RECORD_PID}`);
  });

  it('redirects legacy dynamic view routes with recordId preserved', async () => {
    const response = (await dynamicViewRedirectLoader(
      routeArgs({ tableName: 'sla_config', recordId: RECORD_PID }),
    )) as { status: number; url: string };

    expect(response.status).toBe(301);
    expect(response.url).toBe(`/p/sla_config/view/${RECORD_PID}`);
  });
});
