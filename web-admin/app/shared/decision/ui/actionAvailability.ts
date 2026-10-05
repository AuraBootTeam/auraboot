import { getLocalizedText } from '~/utils/i18n';
import messages from './actionAvailability.i18n.json';
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
  return (
    dependencies.find((item) => item.required && item.available === false) ??
    dependencies.find(
      (item) => item.available === false || item.availabilityStatus === 'UNAVAILABLE',
    )
  );
}

function statusLabel(dependency: DecisionActionProviderDependency, locale: string): string {
  const reason = dependency.availabilityReason?.trim();
  if (reason?.includes('未配置')) return getLocalizedText(messages.unconfigured, locale);
  if (reason?.includes('不可用')) return getLocalizedText(messages.unavailable, locale);
  if (dependency.availabilityStatus === 'UNAVAILABLE' || dependency.available === false)
    return getLocalizedText(messages.unavailable, locale);
  return getLocalizedText(messages.available, locale);
}

function providerSummary(
  dependency: DecisionActionProviderDependency | undefined,
  locale: string,
): string {
  if (!dependency) return '';
  const label =
    dependency.label?.trim() ||
    dependency.providerType?.trim() ||
    getLocalizedText(messages.externalProvider, locale);
  const codes = Array.isArray(dependency.providerCodes)
    ? dependency.providerCodes.filter(
        (code): code is string => typeof code === 'string' && code.trim().length > 0,
      )
    : [];
  const provider = codes.length > 0 ? `${label} (${codes.join(', ')})` : label;
  return getLocalizedText(messages.dependency, locale)
    .replace('{provider}', () => provider)
    .replace('{status}', () => statusLabel(dependency, locale));
}

function providerReason(
  dependency: DecisionActionProviderDependency | undefined,
  locale: string,
): string {
  if (!dependency) return '';
  const label =
    dependency.label?.trim() ||
    dependency.providerType?.trim() ||
    getLocalizedText(messages.externalProvider, locale);
  const reason = dependency.availabilityReason?.trim();
  return getLocalizedText(reason ? messages.providerReason : messages.providerUnavailable, locale)
    .replace('{label}', () => label)
    .replace('{reason}', () => reason ?? '');
}

export function resolveDecisionActionAvailability(
  action: DecisionAction | undefined,
  consumerType?: string,
  locale: string = 'zh-CN',
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
      ? availabilityReason?.trim() ||
        providerReason(blockingDependency, locale) ||
        getLocalizedText(messages.handlerUnavailable, locale)
      : '',
    providerSummary: unavailable ? providerSummary(blockingDependency, locale) : '',
  };
}
