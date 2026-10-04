import { getLocalizedText } from '~/utils/i18n';
import TEXT from './tracePresentation.i18n.json';

export function traceLabel(group: keyof typeof TEXT, key: string, locale = 'zh-CN'): string {
  const labels = TEXT[group] as Record<string, { 'zh-CN': string; en: string }>;
  return labels[key] ? getLocalizedText(labels[key], locale) : key;
}

export function traceFieldValueLabels(key: string, locale = 'zh-CN'): Record<string, string> | undefined {
  if (!['wd_req_type', 'leaveType', 'leave_type', 'reqType'].includes(key)) return undefined;
  return Object.fromEntries(Object.keys(TEXT.leave).map(value => [value, traceLabel('leave', value, locale)]));
}

export function traceSemanticValue(key: string, value: unknown, locale = 'zh-CN'): string | undefined {
  const normalized = typeof value === 'string' ? value.toLowerCase() : String(value);
  if (['matched', 'truth', 'conditionResult'].includes(key)) {
    const prefix = key === 'matched' ? 'matched' : 'truth';
    if (['true', 'yes', 'matched'].includes(normalized)) return traceLabel('semantic', prefix + 'True', locale);
    if (['false', 'no', 'not_matched'].includes(normalized)) return traceLabel('semantic', prefix + 'False', locale);
    if (normalized === 'unknown') return traceLabel('semantic', 'unknown', locale);
  }
  if (typeof value === 'boolean') return traceLabel('semantic', value ? 'yes' : 'no', locale);
  return undefined;
}

export function runtimeAdapterLabel(code: string | undefined, locale = 'zh-CN'): string {
  if (!code) return '-';
  const normalized = code.trim().toUpperCase();
  return Object.hasOwn(TEXT.adapter, normalized)
    ? traceLabel('adapter', normalized, locale)
    : traceLabel('semantic', 'otherEvaluator', locale);
}
