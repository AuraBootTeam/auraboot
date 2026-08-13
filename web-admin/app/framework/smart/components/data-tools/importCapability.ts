import type { ImportConfiguration } from './ImportModal';

/**
 * Import is fail-closed: the page DSL must opt in and, when declared, the
 * current actor must own the model-scoped permission.
 */
export function canUseImport(
  config: ImportConfiguration | undefined,
  hasPermission: (permissionCode: string) => boolean,
): boolean {
  if (!config?.enabled || !config.permissionCode?.trim()) return false;
  return hasPermission(config.permissionCode);
}

/** Resolve a backend field code to the business label shown in import UI surfaces. */
export function resolveImportFieldLabel(
  field: string,
  labels: Readonly<Record<string, string>>,
): string {
  const required = /^\*\s+/.test(field);
  const normalized = field.replace(/^\*\s+/, '');
  const label = labels[normalized]?.trim() || normalized;
  return required ? `* ${label}` : label;
}

/** Replace internal field codes embedded in backend messages before rendering them. */
export function resolveImportMessageFieldCodes(
  message: string,
  labels: Readonly<Record<string, string>>,
): string {
  return Object.entries(labels).reduce(
    (resolved, [code, label]) => resolved.replaceAll(code, label.trim() || code),
    message,
  );
}

/** Map stable backend reference failures to user-facing import guidance. */
export function resolveImportReferenceMessage(message: string): string | null {
  if (message.startsWith('Referenced record does not exist or is not accessible')) {
    return '关联记录不存在或无权访问';
  }
  if (message.startsWith('Reference value is ambiguous')) {
    return '关联值不唯一，请改用唯一业务编码或 PID';
  }
  return null;
}

/** Keep infrastructure details out of the UI and give the user an actionable next step. */
export function resolveImportExecutionMessage(message: string): string | null {
  if (message.startsWith('Import row could not be saved.')) {
    return '该行无法保存，请检查字段值与模板要求后重试';
  }
  return null;
}
