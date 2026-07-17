import type {
  DecisionAction,
  DecisionActionConsumerAvailability,
  DecisionActionProviderDependency,
} from '~/shared/decision/api/decisionApi';

export interface DecisionActionAvailabilityView {
  unavailable: boolean;
  reason: string;
  providerSummary: string;
}

function consumerAvailability(
  action: DecisionAction | undefined,
  consumerType?: string,
): DecisionActionConsumerAvailability | undefined {
  const normalized = consumerType?.trim().toUpperCase();
  if (!normalized) return undefined;
  return action?.consumerAvailability?.find(
    (item) => item.consumerType?.toUpperCase() === normalized,
  );
}

function providerDependencies(
  action: DecisionAction | undefined,
  consumer?: DecisionActionConsumerAvailability,
): DecisionActionProviderDependency[] {
  const consumerDependencies = consumer?.providerDependencies;
  if (Array.isArray(consumerDependencies) && consumerDependencies.length > 0) {
    return consumerDependencies;
  }
  return Array.isArray(action?.providerDependencies) ? action.providerDependencies : [];
}

function blockingProviderDependency(
  dependencies: DecisionActionProviderDependency[],
): DecisionActionProviderDependency | undefined {
  return dependencies.find((item) => item.required && item.available === false)
    ?? dependencies.find((item) => item.available === false || item.availabilityStatus === 'UNAVAILABLE');
}

function statusLabel(dependency: DecisionActionProviderDependency): string {
  const reason = dependency.availabilityReason?.trim();
  if (reason?.includes('未配置')) return '未配置';
  if (reason?.includes('不可用')) return '不可用';
  if (dependency.availabilityStatus === 'UNAVAILABLE' || dependency.available === false) return '不可用';
  return '可用';
}

function providerSummary(dependency: DecisionActionProviderDependency | undefined): string {
  if (!dependency) return '';
  const label = dependency.label?.trim() || dependency.providerType?.trim() || '外部 provider';
  const codes = Array.isArray(dependency.providerCodes)
    ? dependency.providerCodes.filter((code): code is string => typeof code === 'string' && code.trim().length > 0)
    : [];
  const provider = codes.length > 0 ? `${label} (${codes.join(', ')})` : label;
  return `依赖：${provider} · ${statusLabel(dependency)}`;
}

function providerReason(dependency: DecisionActionProviderDependency | undefined): string {
  if (!dependency) return '';
  const label = dependency.label?.trim() || dependency.providerType?.trim() || '外部 provider';
  const reason = dependency.availabilityReason?.trim();
  return reason ? `${label}不可用: ${reason}` : `${label}不可用`;
}

export function resolveDecisionActionAvailability(
  action: DecisionAction | undefined,
  consumerType?: string,
): DecisionActionAvailabilityView {
  const consumer = consumerAvailability(action, consumerType);
  const availabilityStatus = consumer?.availabilityStatus ?? action?.availabilityStatus;
  const handlerAvailable = consumer?.handlerAvailable ?? action?.handlerAvailable;
  const availabilityReason = consumer?.availabilityReason ?? action?.availabilityReason;
  const unavailable = availabilityStatus === 'UNAVAILABLE' || handlerAvailable === false;
  const blockingDependency = blockingProviderDependency(providerDependencies(action, consumer));
  return {
    unavailable,
    reason: unavailable
      ? availabilityReason?.trim() || providerReason(blockingDependency) || '动作处理器当前不可用'
      : '',
    providerSummary: unavailable
      ? providerSummary(blockingDependency)
      : '',
  };
}
