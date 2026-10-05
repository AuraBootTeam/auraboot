import { getLocalizedText } from '~/utils/i18n';
import TEXT from './statusLabels.i18n.json';

export function decisionStatusLabel(status?: string | null, locale = 'zh-CN'): string {
  if (!status) return '-';
  const normalized = status.trim().toUpperCase();
  return Object.hasOwn(TEXT, normalized)
    ? getLocalizedText(TEXT[normalized as keyof typeof TEXT], locale) : status;
}
