import { getLocalizedText } from '~/utils/i18n';
import type { ModelPublishGovernance } from '~/shared/services/modelService';
import TEXT from './modelPublishPolicyMessages.i18n.json';

export const getPolicyHeading = (key: 'migrationHeading' | 'historyHeading', locale: string) =>
  getLocalizedText(TEXT[key], locale);

const localizeCode = (code: string, locale: string): string | undefined =>
  Object.hasOwn(TEXT, code) ? getLocalizedText(TEXT[code as keyof typeof TEXT], locale) : undefined;

export function getMigrationPlanMessage(governance: ModelPublishGovernance, locale: string): string {
  const steps = governance.migrationPlanSteps;
  if (!steps?.length) return governance.migrationPlan || '';
  const messages = steps.map((code) => localizeCode(code, locale));
  // A newer server policy must remain visible rather than silently losing a step.
  if (messages.some((message) => message === undefined)) return governance.migrationPlan || steps.join(', ');
  return messages.join(' ');
}

export function getHistoricalPolicyMessage(governance: ModelPublishGovernance, locale: string): string {
  const code = governance.historicalVersionPolicyCode;
  return (code && localizeCode(code, locale)) || governance.historicalVersionPolicy || code || '';
}
