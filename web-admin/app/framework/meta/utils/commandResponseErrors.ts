function firstNonBlankString(...values: unknown[]): string | undefined {
  for (const value of values) {
    if (typeof value !== 'string') continue;
    const trimmed = value.trim();
    if (trimmed.length > 0) return trimmed;
  }
  return undefined;
}


/**
 * Pull the user-facing reason out of a failed command response. The backend puts it in
 * `context.detail` (localized); `message` / `desc` are the generic envelope text
 * ("Business error"), so they must stay last. Shared with the DSL form page so both
 * command execution paths surface the same reason.
 */
export function resolveCommandErrorMessage(
  result: unknown,
  commandCode: string,
  translate?: (key: string, params?: Record<string, string>) => string,
  locale?: string,
): string {
  const body = (result || {}) as Record<string, any>;
  const stableI18nKey =
    String(body.code || '').toUpperCase() === 'BACKEND_REQUEST_TIMEOUT'
      ? 'common.error.backendRequestTimeout'
      : undefined;
  if (stableI18nKey && translate) {
    const localized = translate(stableI18nKey);
    if (localized && localized !== stableI18nKey) {
      return localized;
    }
  }
  const conflictCode = String(body.context?.errorCode || '').toUpperCase();
  if (conflictCode === 'CAS_VERSION_CONFLICT') {
    return 'This record was updated by someone else. Refresh to review the latest data before saving.';
  }
  if (conflictCode === 'REQUEST_INTENT_CONFLICT') {
    return 'This request does not match the original request. Refresh and start the operation again.';
  }
  if (conflictCode === 'CAS_VERSION_REQUIRED') {
    return 'This form is stale and cannot prove the record is unchanged. Refresh and try again.';
  }
  // New rejection contract: `context` carries the bare i18n reason key (for
  // example "annual_balance_not_found") instead of the legacy {messageKey,
  // detail} object. Resolve it through the page locale catalog; when no entry
  // exists the key itself is still more actionable than the generic envelope
  // message ("Business error") that would otherwise surface.
  const contextKey =
    typeof body.context === 'string' && body.context.trim().length > 0
      ? body.context.trim()
      : undefined;
  if (contextKey) {
    const localized = translate ? translate(contextKey) : undefined;
    if (localized && localized !== contextKey) {
      return localized;
    }
    return contextKey;
  }
  const resolved =
    firstNonBlankString(
      body.context?.detail,
      body.context?.error,
      body.context?.exception,
      body.data?.context?.detail,
      body.data?.context?.error,
      body.data?.detail,
      body.data?.error,
      body.data?.message,
      body.message,
      body.desc,
    ) || `Command ${commandCode} failed`;

  if (/Record not found: .* in model:/i.test(resolved)) {
    return locale?.toLowerCase().startsWith('zh')
      ? '关联记录不存在或不可访问，请重新选择后再提交。'
      : 'The referenced record is unavailable. Select an accessible record and try again.';
  }

  const shortage = resolved.match(/Insufficient stock for product \[.*?\] (?:at the line location|in source warehouse|at the storage location): required ([0-9.-]+), available ([0-9.-]+)/i);
  if (shortage) {
    const key = 'common.error.insufficientStock';
    const params = { required: shortage[1], available: shortage[2] };
    const localized = translate?.(key, params);
    if (localized && localized !== key) return localized;
    return locale?.toLowerCase().startsWith('zh')
      ? `所选库位库存不足：需要 ${params.required}，可用 ${params.available}。请调整数量或选择其他库位。`
      : `Insufficient stock at the selected location: required ${params.required}, available ${params.available}. Adjust the quantity or select another location.`;
  }

  // PF4J/platform wrappers are implementation details, not business feedback. Keep
  // the handler's actionable reason while removing the transport prefix from every
  // DSL command surface (detail, form, workbench and list actions).
  const reason = resolved
    .replace(
      /^(?:plugin (?:extension )?handler execution failed|command handler execution failed)\s*:\s*/i,
      '',
    )
    .trim();

  if (/^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$/i.test(reason)) {
    const localized = translate?.(reason);
    if (localized && localized !== reason) {
      return localized;
    }
    return locale?.toLowerCase().startsWith('zh')
      ? '操作未完成，请检查输入后重试。'
      : 'The operation could not be completed. Check your input and try again.';
  }
  return reason;
}
