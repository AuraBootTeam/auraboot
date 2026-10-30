/** UI gates compose required permissions; command handlers still authorize every mutation. */
export function hasButtonPermissions(
  button: { permissionCode?: string; permissionCodes?: string[] },
  hasPermission: (code: string) => boolean,
): boolean {
  if (button.permissionCode && !hasPermission(button.permissionCode)) return false;
  if (button.permissionCodes === undefined) return true;
  return Array.isArray(button.permissionCodes) && button.permissionCodes.every(
    (code) => typeof code === 'string' && code.length > 0 && hasPermission(code),
  );
}

/** Shared enable/disable expressions retain their documented precedence on detail toolbars. */
export function isButtonDisabled(button: { disabled?: boolean; disableWhen?: string; enableWhen?: string }, evaluate: (expression: string) => boolean, busy = false): boolean {
  if (busy || button.disabled) return true;
  if (button.enableWhen) return !evaluate(button.enableWhen);
  return Boolean(button.disableWhen && evaluate(button.disableWhen));
}
