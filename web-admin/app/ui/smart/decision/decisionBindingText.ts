import { getLocalizedText } from '~/utils/i18n';
import messages from './decisionBinding.i18n.json';
export type BindingTextKey = keyof typeof messages;
export function bindingText(
  key: BindingTextKey,
  locale: string,
  params: Record<string, string | number> = {},
): string {
  return getLocalizedText(messages[key], locale).replace(/\{(\w+)\}/g, (token, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : token,
  );
}
