import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { AuthoringRecoveryPolicy } from './authoringLocalRecovery';

export const AUTHORING_RECOVERY_POLICY_PREFERENCE = 'authoring.recovery.policy';
export const DEFAULT_AUTHORING_RECOVERY_POLICY: AuthoringRecoveryPolicy = 'PERSISTENT';

export async function loadAuthoringRecoveryPolicy(): Promise<AuthoringRecoveryPolicy> {
  const result = await get<{ value: unknown }>(
    `/api/tenant-preferences/${AUTHORING_RECOVERY_POLICY_PREFERENCE}`,
  );
  if (!ResultHelper.isSuccess(result)) {
    throw new Error('无法读取企业恢复策略');
  }
  const value = result.data?.value;
  if (value == null) return DEFAULT_AUTHORING_RECOVERY_POLICY;
  if (value === 'PERSISTENT' || value === 'SESSION_ONLY' || value === 'DISABLED') return value;
  throw new Error('企业恢复策略配置无效');
}

export function describeAuthoringRecoveryFailure(policy: AuthoringRecoveryPolicy): string {
  return policy === 'DISABLED'
    ? '企业安全策略已禁止浏览器保存恢复副本；刷新、关闭页面或退出配置模式会丢失未保存变更。'
    : '浏览器无法建立本地恢复副本；请勿刷新或关闭页面，并尽快重试保存。';
}
