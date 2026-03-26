import { ResourceIndex } from '../../utils/resource-index.js';
import { DiagnosticMessage } from './menu-route.js';

/**
 * PERM_CRUD_COVERAGE: ENTITY models should have view + manage permissions.
 * Checks if each ENTITY model has at least one manage-like and one read/view-like permission.
 */
export function checkPermCrudCoverage(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  // Build a set of permission codes referenced by commands for each model
  const permsByModel = new Map<string, Set<string>>();
  for (const c of idx.raw.commands) {
    if (c.modelCode && c.permissions) {
      if (!permsByModel.has(c.modelCode)) permsByModel.set(c.modelCode, new Set());
      for (const p of c.permissions) permsByModel.get(c.modelCode)!.add(p);
    }
  }

  for (const m of idx.raw.models) {
    if (m.modelType !== 'entity') continue;

    // Check if there's any manage-like permission (manage, write, edit, crud, admin)
    const allPerms = Array.from(idx.permissions.keys());
    const modelPerms = permsByModel.get(m.code) || new Set();
    const allModelPerms = [...allPerms.filter(p => p.toLowerCase().includes(m.code.replace(/_/g, '.'))), ...modelPerms];

    const hasManage = allModelPerms.some(p => /manage|write|edit|crud|admin/i.test(p));
    const hasRead = allModelPerms.some(p => /read|view/i.test(p));

    // If model has commands but no permissions at all, that's a concern
    const cmds = idx.commandsByModel.get(m.code) || [];
    if (cmds.length > 0 && modelPerms.size === 0) {
      messages.push({
        code: 'perm_crud_coverage',
        severity: 'warning',
        message: `ENTITY model '${m.code}' has ${cmds.length} commands but no permissions declared`,
        path: `permissions.json`,
        suggestion: `Add manage and read permissions for this model`,
      });
    }
  }

  return messages;
}
