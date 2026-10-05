import { getLocalizedText } from '~/utils/i18n';
import messages from './authoringSurface.i18n.json';

export type AuthoringSurfaceTextKey = keyof typeof messages;

/**
 * Resolve a ContextualAuthoringSurface catalog entry for the active locale.
 *
 * Follows the shared catalog pattern (see app/ui/smart/decision/decisionBindingText.ts):
 * the JSON catalog pairs "zh-CN"/"en" and getLocalizedText supplies the fallback chain
 * (exact locale -> language -> regional variant -> zh-CN -> en), so regional variants
 * such as en-US/en-GB reuse the "en" entry and unknown locales degrade to zh-CN.
 * `{name}` tokens are interpolated from params; unknown tokens are kept verbatim.
 */
export function authoringSurfaceText(
  key: AuthoringSurfaceTextKey,
  locale: string,
  params: Record<string, string | number> = {},
): string {
  return getLocalizedText(messages[key], locale).replace(/\{(\w+)\}/g, (token, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : token,
  );
}
