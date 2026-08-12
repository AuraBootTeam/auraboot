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
