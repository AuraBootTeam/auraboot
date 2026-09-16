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
  translate?: (key: string) => string,
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

  // PF4J/platform wrappers are implementation details, not business feedback. Keep
  // the handler's actionable reason while removing the transport prefix from every
  // DSL command surface (detail, form, workbench and list actions).
  return resolved
    .replace(
      /^(?:plugin (?:extension )?handler execution failed|command handler execution failed)\s*:\s*/i,
      '',
    )
    .trim();
}

