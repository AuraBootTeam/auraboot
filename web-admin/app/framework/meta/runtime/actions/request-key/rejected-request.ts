import type { RequestKeySession } from './session';

/** An opt-in correction path for explicit, pre-effect business rejections only. */
export function captureRejectedRequestRelease(
  policy: unknown,
  stateManager: any,
  scopeId: string | undefined,
  session: RequestKeySession | undefined,
  method: string,
  request: any,
): (result: any) => void {
  if (policy === undefined) return () => {};
  const value = policy as Record<string, any>;
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).sort().join(',') !== 'field,messageKeys,responseCode' ||
    typeof value.field !== 'string' ||
    !/^[A-Za-z_][A-Za-z0-9_]*$/.test(value.field) ||
    ['__proto__', 'prototype', 'constructor'].includes(value.field) ||
    typeof value.responseCode !== 'string' ||
    !/^[1-9][0-9]{0,7}$/.test(value.responseCode) ||
    !Array.isArray(value.messageKeys) ||
    !value.messageKeys.length ||
    value.messageKeys.length > 16 ||
    new Set(value.messageKeys).size !== value.messageKeys.length ||
    value.messageKeys.some(
      (key: unknown) =>
        typeof key !== 'string' || !/^\$i18n:[a-zA-Z][a-zA-Z0-9_.]{0,199}$/.test(key),
    ) ||
    !stateManager ||
    !scopeId ||
    !stateManager.getScope(scopeId) ||
    !session ||
    method.toLowerCase() !== 'post'
  )
    throw new Error('Invalid rejected-request release policy');
  const field = value.field,
    responseCode = value.responseCode;
  const messageKeys = new Set<string>(value.messageKeys);
  const original = stateManager.getContext(scopeId).state[field];
  if (
    typeof original !== 'string' ||
    session.peek(field, original) !== original ||
    request?.clientRequestId !== original ||
    request?.payload?.requestKey !== original
  )
    throw new Error('Rejected-request policy requires the original persisted request identity');
  return (result) => {
    // A localized string, 2xx failure, network exception or 5xx is not a confirmed rejection.
    if (
      result?.httpStatus !== 400 ||
      String(result?.code) !== responseCode ||
      !messageKeys.has(result?.context?.messageKey)
    )
      return;
    if (
      !stateManager.getScope(scopeId) ||
      stateManager.getContext(scopeId).state[field] !== original ||
      session.peek(field, original) !== original
    )
      throw new Error('Request identity changed before rejection recovery');
    session.clear(field, original);
    stateManager.updateState(scopeId, field, null);
  };
}
