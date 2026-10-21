import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { resolveErrorLocale, rootT } from '~/error/root-error-view';
import { ResultHelper } from '~/utils/type';

/** Keep transport/exception status until classification; never infer permission
 * from localized text or promote an authorization diagnostic into UI copy. */
export function resolveListReadErrorMessage(
  error: unknown,
  locale: string,
  translate: (key: string) => string,
): string {
  const result = error && typeof error === 'object' ? error as Record<string, unknown> : {};
  const response = result.response && typeof result.response === 'object'
    ? result.response as Record<string, unknown> : {};
  // The HTTP status owns classification even when the business code differs.
  const status = [result.httpStatus, result.status, result.statusCode, response.status, result.code]
    .filter(value => typeof value === 'number' || typeof value === 'string' && /^\d{3}$/.test(value))
    .map(Number).find(value => Number.isInteger(value) && value >= 100 && value <= 599);
  if (status === 403) return rootT('forbidden', resolveErrorLocale(locale));
  if (result.code === 'network_error') {
    return getLocalizedText({
      'zh-CN': '网络连接失败，请检查连接后重试。',
      en: 'Unable to connect. Check your connection and try again.',
    }, locale, translate);
  }
  // Reuse the existing public BusinessException detail contract. System/auth
  // context is diagnostic and cannot become user-facing read feedback.
  const detail = ResultHelper.getErrorDetail({
    code: typeof result.code === 'string' || typeof result.code === 'number' ? result.code : undefined,
    httpStatus: status,
    context: result.context,
  });
  if (detail) return detail;
  const fallback = translate('common.loadDataError');
  const generic = fallback && fallback !== 'common.loadDataError' ? fallback : 'Failed to load data';
  if (status === 401 || status !== undefined && status >= 500) return generic;
  return error instanceof Error && error.message
    ? error.message
    : typeof result.desc === 'string' && result.desc.trim() ? result.desc : generic;
}
