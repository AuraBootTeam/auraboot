import { beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from '~/shared/services/http-client';
import {
  DEFAULT_AUTHORING_RECOVERY_POLICY,
  describeAuthoringRecoveryFailure,
  loadAuthoringRecoveryPolicy,
} from '../authoringRecoveryPolicy';

vi.mock('~/shared/services/http-client', () => ({ get: vi.fn() }));

describe('authoring recovery policy', () => {
  beforeEach(() => vi.mocked(get).mockReset());

  it('defaults an unset tenant preference to persistent recovery', async () => {
    vi.mocked(get).mockResolvedValue({ code: '0', desc: 'OK', data: { value: null } });
    await expect(loadAuthoringRecoveryPolicy()).resolves.toBe(DEFAULT_AUTHORING_RECOVERY_POLICY);
  });

  it.each(['PERSISTENT', 'SESSION_ONLY', 'DISABLED'] as const)(
    'accepts the supported %s policy',
    async (policy) => {
      vi.mocked(get).mockResolvedValue({ code: '0', desc: 'OK', data: { value: policy } });
      await expect(loadAuthoringRecoveryPolicy()).resolves.toBe(policy);
    },
  );

  it('fails closed when the tenant policy is unreadable or invalid', async () => {
    vi.mocked(get).mockResolvedValueOnce({ code: '1', desc: 'error', data: null });
    await expect(loadAuthoringRecoveryPolicy()).rejects.toThrow('无法读取企业恢复策略');

    vi.mocked(get).mockResolvedValueOnce({
      code: '0',
      desc: 'OK',
      data: { value: 'FOREVER' },
    });
    await expect(loadAuthoringRecoveryPolicy()).rejects.toThrow('企业恢复策略配置无效');
  });

  it('distinguishes policy refusal from browser storage failure', () => {
    expect(describeAuthoringRecoveryFailure('DISABLED')).toContain('企业安全策略');
    expect(describeAuthoringRecoveryFailure('PERSISTENT')).toContain('浏览器无法');
  });
});
