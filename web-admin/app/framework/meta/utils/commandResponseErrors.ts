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
  fallbackMessage?: string,
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
  const conflictMessages: Record<string, { key: string; zh: string; en: string }> = {
    CAS_VERSION_CONFLICT: {
      key: 'common.error.recordVersionConflict',
      zh: '这条记录已被其他操作更新，请刷新查看最新内容后再提交。',
      en: 'This record was updated by someone else. Refresh to review the latest data before saving.',
    },
    REQUEST_INTENT_CONFLICT: {
      key: 'common.error.requestIntentConflict',
      zh: '本次请求与原请求内容不一致，请刷新后重新发起操作。',
      en: 'This request does not match the original request. Refresh and start the operation again.',
    },
    CAS_VERSION_REQUIRED: {
      key: 'common.error.recordVersionRequired',
      zh: '当前表单缺少记录版本，请刷新后重试。',
      en: 'This form is stale and cannot prove the record is unchanged. Refresh and try again.',
    },
  };
  const conflict = conflictMessages[conflictCode];
  if (conflict) {
    const localized = translate?.(conflict.key);
    return localized && localized !== conflict.key ? localized
      : locale?.toLowerCase().startsWith('zh') ? conflict.zh : conflict.en;
  }
  // New rejection contract: `context` carries the bare i18n reason key (for
  // example "annual_balance_not_found") instead of the legacy {messageKey,
  // detail} object. Resolve it through the page locale catalog; when no entry
  // exists, keep processing the reason through the same wrapper removal and
  // safe localization path used by object-shaped contexts.
  const contextKey =
    typeof body.context === 'string' && body.context.trim().length > 0
      ? body.context.trim()
      : undefined;
  if (contextKey) {
    const localized = translate ? translate(contextKey) : undefined;
    if (localized && localized !== contextKey) {
      return localized;
    }
  }
  const resolved =
    firstNonBlankString(
      contextKey,
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
    ) || fallbackMessage || `Command ${commandCode} failed`;

  // Engine configuration errors must remain actionable on every DSL business
  // surface. The original response and server audit retain the technical cause.
  if (/unable to resolve (?:variable|token)/i.test(resolved)) {
    return locale?.toLowerCase().startsWith('zh')
      ? '流程所需的数据不完整，请管理员检查规则输出与流程变量映射后重试。'
      : 'Required workflow data is missing. Ask an administrator to check rule outputs and workflow variable mappings, then retry.';
  }
  if (/transaction rolled back.*rollback-only/i.test(resolved)) {
    return locale?.toLowerCase().startsWith('zh')
      ? '本次操作未能提交，数据已回滚。请管理员检查流程或规则配置后重试。'
      : 'The operation could not be committed and its data changes were rolled back. Ask an administrator to check workflow or rule configuration, then retry.';
  }

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
