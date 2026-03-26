import { ResourceIndex } from '../../utils/resource-index.js';

export interface DiagnosticMessage {
  code: string;
  severity: 'error' | 'warning' | 'info';
  message: string;
  path?: string;
  suggestion?: string;
}

/**
 * MENU_ROUTE_MATCH: Menu path matches a page pageKey or follows /dynamic/{model} pattern.
 */
export function checkMenuRouteMatch(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const menu of idx.raw.menus) {
    if (menu.type === 0) continue; // Skip directories
    if (!menu.path) continue;

    // Dynamic routes: /dynamic/{slug}
    const dynamicMatch = menu.path.match(/^\/dynamic\/(.+)$/);
    if (dynamicMatch) {
      const slug = dynamicMatch[1];
      // Convert slug (hyphens) to potential pageKey (underscores)
      const possiblePageKey = slug.replace(/-/g, '_');
      // Check if there's a matching page (with any suffix like _list, _form)
      const hasPage = Array.from(idx.pages.keys()).some(pk =>
        pk === possiblePageKey || pk.startsWith(possiblePageKey + '_'),
      );
      // Also check if it matches a model code (auto-generated dynamic pages)
      const possibleModel = possiblePageKey;
      const hasModel = idx.models.has(possibleModel);

      if (!hasPage && !hasModel) {
        messages.push({
          code: 'menu_route_match',
          severity: 'error',
          message: `Menu '${menu.code}' path '${menu.path}' has no matching page or model`,
          path: `menus.json#${menu.code}`,
          suggestion: `Create a page with pageKey '${possiblePageKey}_list' or ensure model '${possibleModel}' exists`,
        });
      }
    }
  }

  return messages;
}

/**
 * MENU_PERM_EXISTS: Menu permissionCode exists in permissions.
 */
export function checkMenuPermExists(idx: ResourceIndex): DiagnosticMessage[] {
  const messages: DiagnosticMessage[] = [];

  for (const menu of idx.raw.menus) {
    if (menu.permissionCode && !idx.permissions.has(menu.permissionCode)) {
      messages.push({
        code: 'menu_perm_exists',
        severity: 'warning',
        message: `Menu '${menu.code}' references undeclared permission '${menu.permissionCode}'`,
        path: `menus.json#${menu.code}`,
        suggestion: `Add '${menu.permissionCode}' to permissions.json`,
      });
    }
  }

  return messages;
}
