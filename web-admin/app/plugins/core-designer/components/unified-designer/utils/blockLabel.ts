import { getLocalizedText, type TranslateFunction, type LocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import { createDefaultBlockRegistryV3 } from '../registry/BlockRegistry';
import type { DslBlockV3 } from '../types';

export function asBlockText(value: unknown): string | LocalizedText | undefined {
  if (typeof value === 'string') return value;
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const localized: LocalizedText = {};
  for (const [locale, text] of Object.entries(value)) {
    if (typeof text === 'string') localized[locale] = text;
  }
  return Object.keys(localized).length ? localized : undefined;
}

export function getBlockTypeLabel(blockType: string, locale = 'en-US', t?: TranslateFunction): string {
  const definition = createDefaultBlockRegistryV3().get(blockType);
  return definition?.label ? getLocalizedText(definition.label, locale, t) : blockType;
}

export function getBlockLabel(block: DslBlockV3, locale = 'en-US', t?: TranslateFunction): string {
  const title = block.title ?? block.props?.label ?? block.props?.title;
  const text = asBlockText(title);
  if (text) return getLocalizedText(text, locale, t);
  return block.field || block.widgetType || block.actionType
    || getBlockTypeLabel(block.blockType, locale, t);
}

