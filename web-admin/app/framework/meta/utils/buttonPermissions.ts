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
