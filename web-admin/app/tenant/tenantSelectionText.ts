import { getLocalizedText } from '~/utils/i18n';
import TEXT from './tenantSelectionText.i18n.json';

export type TenantSelectionMessageKey = keyof typeof TEXT;

/** Local defaults remain usable before the authenticated translation catalog loads. */
export function tenantSelectionText(
  key: TenantSelectionMessageKey,
  locale = 'zh-CN',
  params: Record<string, unknown> = {},
): string {
  let text = getLocalizedText(TEXT[key], locale);
  for (const [name, value] of Object.entries(params)) {
    text = text.split(`{${name}}`).join(String(value));
  }
  return text;
}
