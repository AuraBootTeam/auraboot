import { RESOURCES as aurabot } from '~/plugins/core-aurabot/resources';
import { RESOURCES as colleagues } from '~/plugins/core-ai-colleagues/resources';

const agentPaths = [...aurabot, ...colleagues].map(resource => resource.path);

/** Resource declarations own AI-only routes; shared designers retain their ordinary routes. */
export function requiresAgentPath(path: string): boolean {
  const pathname = path.split(/[?#]/)[0].replace(/\/$/, '') || '/';
  return pathname === '/meta/ai-modeling' || agentPaths.some(pattern => {
    const parts = pattern.split('/');
    const actual = pathname.split('/');
    return parts.length === actual.length && parts.every((part, i) => part.startsWith(':') || part === actual[i]);
  });
}

export interface AssemblyMenu { path?: string; submenu?: AssemblyMenu[] | null; children?: AssemblyMenu[] | null }

export function filterAgentMenus<T extends AssemblyMenu>(menus: readonly T[], installed: boolean): T[] {
  if (installed) return [...menus];
  return menus.filter(menu => !menu.path || !requiresAgentPath(menu.path)).flatMap(menu => {
    const submenu = menu.submenu ? filterAgentMenus(menu.submenu, installed) : undefined;
    const children = menu.children ? filterAgentMenus(menu.children, installed) : undefined;
    if ((menu.submenu?.length && !submenu?.length) || (menu.children?.length && !children?.length)) return [];
    return [{ ...menu, ...(submenu ? { submenu } : {}), ...(children ? { children } : {}) }];
  });
}
