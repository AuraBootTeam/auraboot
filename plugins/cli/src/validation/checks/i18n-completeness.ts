import { ResourceIndex } from '../../utils/resource-index.js';
import { DiagnosticMessage } from './menu-route.js';

/**
 * I18N_MODEL_LABEL: Models have i18n label.
 */
export function checkI18nModelLabel(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const m of idx.raw.models) {
    const key = `model.${m.code}._meta.label`;
    if (!idx.i18nKeys.has(key)) {
      messages.push({
        code: 'i18n_model_label',
        severity: 'warning',
        message: `Model '${m.code}' missing i18n label key '${key}'`,
        path: `i18n.json`,
        suggestion: `Run 'aura dsl sync-i18n' to generate missing keys`,
      });
    }
  }

  return messages;
}

/**
 * I18N_FIELD_LABEL: Bound fields have i18n label.
 */
export function checkI18nFieldLabel(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const b of idx.raw.bindings) {
    const key = `model.${b.modelCode}.${b.fieldCode}.label`;
    if (!idx.i18nKeys.has(key)) {
      messages.push({
        code: 'i18n_field_label',
        severity: 'warning',
        message: `Field '${b.fieldCode}' in model '${b.modelCode}' missing i18n label`,
        path: `i18n.json`,
        suggestion: `Run 'aura dsl sync-i18n' to generate missing keys`,
      });
    }
  }

  return messages;
}

/**
 * I18N_BILINGUAL: i18n entries have both zh-CN and en-US.
 */
export function checkI18nBilingual(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const entry of idx.raw.i18n) {
    const hasZh = !!entry['zh-CN'];
    const hasEn = !!entry['en-US'];
    if (hasZh && !hasEn) {
      messages.push({
        code: 'i18n_bilingual',
        severity: 'info',
        message: `i18n key '${entry.key}' has zh-CN but missing en-US`,
        path: `i18n.json#${entry.key}`,
      });
    } else if (!hasZh && hasEn) {
      messages.push({
        code: 'i18n_bilingual',
        severity: 'info',
        message: `i18n key '${entry.key}' has en-US but missing zh-CN`,
        path: `i18n.json#${entry.key}`,
      });
    }
  }

  return messages;
}
