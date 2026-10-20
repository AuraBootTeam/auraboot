import { getLocalizedText } from '~/utils/i18n';
import COPY from './diagnosticsCopy.i18n.json';

export function diagnosticText(key: string, locale = 'zh-CN'): string {
  const value = COPY[key as keyof typeof COPY];
  return value ? getLocalizedText(value, locale) : getLocalizedText(COPY.other, locale);
}
