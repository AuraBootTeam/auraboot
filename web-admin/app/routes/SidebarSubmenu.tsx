import { useState, useEffect } from 'react';
import { useLocation, Link } from 'react-router';
import { ChevronDownIcon } from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { resolveIcon } from '~/utils/icon-resolver';

interface SubmenuItem {
  id?: string;
  path: string;
  name: string;
  nameKey?: string;
  icon?: any;
  submenu?: SubmenuItem[];
}

interface SidebarSubmenuProps {
  submenu: SubmenuItem[];
  name: string;
  icon?: any;
  depth?: number;
}

export function resolveMenuLabel(
  t: (key: string, params?: Record<string, any>, fallback?: string) => string,
  item: Pick<SubmenuItem, 'name' | 'nameKey'>,
): string {
  const fallback = item.name || item.nameKey || '';
  const key = item.nameKey || item.name;
  if (!key) return fallback;
  return t(key, undefined, fallback);
}

function isPathInSubmenu(items: SubmenuItem[], pathname: string): boolean {
  for (const item of items) {
    if (item.path === pathname) return true;
    if (item.submenu && isPathInSubmenu(item.submenu, pathname)) return true;
  }
  return false;
}

export default function SidebarSubmenu({ submenu, name, icon, depth = 0 }: SidebarSubmenuProps) {
  const location = useLocation();
  const { t } = useI18n();
  // Default expanded so child nav links are always visible & clickable.
  // Previously collapsed-by-default created two failure modes:
  //   1. Clicking a sidebar link from a fresh page (e.g. test goes
  //      `/home` → click "Query Builder") raced with the submenu auto-
  //      expand transition, intercepting clicks (QB-07/08).
  //   2. With `display:none` on collapsed children, the link wasn't
  //      findable at all from `/home`.
  // Expanded-by-default sidesteps both: links are always reachable and
  // there's no animation race. Users can still collapse via the chevron.
  const [isExpanded, setIsExpanded] = useState(true);

  useEffect(() => {
    if (isPathInSubmenu(submenu, location.pathname)) {
      setIsExpanded(true);
    }
  }, [location.pathname, submenu]);

  // Use logical property (padding-inline-start) so indentation mirrors correctly in RTL.
  const paddingLeft = depth > 0 ? 'ps-4' : 'ps-6';

  return (
    <div className="space-y-1">
      {/* Parent menu button */}
      <button
        onClick={() => setIsExpanded(!isExpanded)}
        className="group flex w-full items-center rounded-lg px-3 py-2 text-sm font-medium text-gray-700 transition-all duration-200 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-700 dark:hover:text-white"
      >
        <span className="me-3 flex h-4 w-4 flex-shrink-0 items-center justify-center">
          {resolveIcon(typeof icon === 'string' ? icon : null, name, 16)}
        </span>
        <span className="flex-1 truncate text-start">{name}</span>
        <ChevronDownIcon
          className={`ms-2 h-4 w-4 transition-transform duration-200 ${
            isExpanded ? 'rotate-180' : ''
          }`}
        />
      </button>

      {/* Child menu items.
          Previously animated `max-h` + opacity over 200ms which caused
          layout shift on initial page-load when the active path's
          submenu auto-expanded. That layout shift raced with Playwright
          clicks on sibling nav links and produced "intercepted by
          parent submenu button" failures (e.g. QB-07/08). Snapping the
          expand/collapse to instant removes the race window without
          changing the visible end-states. */}
      <div
        className={`overflow-hidden ${
          isExpanded ? 'max-h-none opacity-100' : 'max-h-0 opacity-0'
        }`}
      >
        <div className={`${paddingLeft} space-y-1`}>
          {submenu.map((item, index) => {
            if (item.submenu && item.submenu.length > 0) {
              return (
                <SidebarSubmenu
                  key={item.id || index}
                  submenu={item.submenu}
                  name={resolveMenuLabel(t, item)}
                  icon={item.icon}
                  depth={depth + 1}
                />
              );
            }

            const itemName = resolveMenuLabel(t, item);
            const isActive = location.pathname === item.path;
            return (
              <Link
                key={item.id || index}
                to={item.path}
                className={`group flex items-center rounded-lg px-3 py-2 text-sm transition-all duration-200 ${
                  isActive
                    ? 'bg-blue-100 font-medium text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
                    : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-white'
                }`}
              >
                <span className="me-3 flex h-4 w-4 flex-shrink-0 items-center justify-center">
                  {resolveIcon(typeof item.icon === 'string' ? item.icon : null, itemName, 14)}
                </span>
                <span className="truncate">{itemName}</span>
                {isActive && <span className="ms-auto h-1.5 w-1.5 rounded-full bg-blue-500" />}
              </Link>
            );
          })}
        </div>
      </div>
    </div>
  );
}
