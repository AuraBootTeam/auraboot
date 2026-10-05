import messages from './componentText.i18n.json';

export type ComponentTextKey = keyof typeof messages;

/** Keep localized registry metadata intact until its display consumer resolves it. */
export function componentText(key: ComponentTextKey): { 'zh-CN': string; 'en-US': string } {
  return messages[key];
}
