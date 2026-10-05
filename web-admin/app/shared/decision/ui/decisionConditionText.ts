import { useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import { operatorLabel, type Operator } from '../ast/conditionAst';
import messages from './decisionCondition.i18n.json';

export type DecisionConditionTextKey = keyof typeof messages;
export function decisionConditionText(
  key: DecisionConditionTextKey,
  locale: string,
  params: Record<string, string | number> = {},
): string {
  return getLocalizedText(messages[key], locale).replace(/\{(\w+)\}/g, (token, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : token,
  );
}
export function decisionConditionOperatorLabel(operator: Operator, locale: string): string {
  const key = ('operator' + operator) as DecisionConditionTextKey;
  return messages[key] ? decisionConditionText(key, locale) : operatorLabel(operator);
}
export function useDecisionConditionText() {
  const { locale } = useI18n();
  const text = useCallback(
    (key: DecisionConditionTextKey, params?: Record<string, string | number>) =>
      decisionConditionText(key, locale, params),
    [locale],
  );
  return { locale, text };
}
