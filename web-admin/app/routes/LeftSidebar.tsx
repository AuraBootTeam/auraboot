import { useRef, useEffect, useState, useCallback } from 'react';
import { NavLink, useLocation, useRevalidator } from 'react-router';
import { useRootLoaderData } from '~/root';
import { useI18n } from '~/contexts/I18nContext';
import SidebarSubmenu from '~/routes/SidebarSubmenu';
import { XMarkIcon, ChevronRightIcon } from '@heroicons/react/24/outline';
import { PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { resolveIcon } from '~/utils/icon-resolver';
import { useDirection } from '~/hooks/useDirection';

const COLLAPSED_KEY = 'sidebar-collapsed';

interface LeftSidebarProps {
  sidebarOpen: boolean;
  setSidebarOpen: (open: boolean) => void;
}

export default function LeftSidebar({ sidebarOpen, setSidebarOpen }: LeftSidebarProps) {
  const location = useLocation();
  const rootData = useRootLoaderData();
  const menus = rootData?.menus ?? [];
  const { t } = useI18n();
  const { isRTL } = useDirection();
  const navRef = useRef<HTMLElement>(null);
  const revalidator = useRevalidator();

  const [collapsed, setCollapsed] = useState(() => {
    if (typeof window !== 'undefined') {
      return localStorage.getItem(COLLAPSED_KEY) === 'true';
    }
    return false;
  });

  const toggleCollapsed = useCallback(() => {
    setCollapsed((prev) => {
      const next = !prev;
      localStorage.setItem(COLLAPSED_KEY, String(next));
      return next;
    });
  }, []);

  // Listen for menu:refresh events
  useEffect(() => {
    const handler = () => revalidator.revalidate();
    window.addEventListener('menu:refresh', handler);
    return () => window.removeEventListener('menu:refresh', handler);
  }, [revalidator]);

  // Auto-scroll to active menu item when path changes
  useEffect(() => {
    if (collapsed) return;
    const timer = setTimeout(() => {
      if (navRef.current) {
        const activeItem = navRef.current.querySelector('.bg-blue-100');
        if (activeItem) {
          activeItem.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [location.pathname, collapsed]);

  const widthClass = collapsed ? 'w-[68px]' : 'w-64';

  // In RTL mode the sidebar is anchored to the right edge; the hidden-state
  // translation must be inverted so it slides out to the right instead of left.
  const hiddenTranslate = isRTL ? 'translate-x-full' : '-translate-x-full';
  const sidebarClasses = `
    fixed inset-y-0 ltr:left-0 rtl:right-0 z-50 ${widthClass} bg-white dark:bg-gray-800 ltr:border-r rtl:border-l border-gray-200 dark:border-gray-700 transform transition-all duration-300 ease-in-out
    flex flex-col overflow-hidden
    lg:translate-x-0 lg:static lg:inset-0
    ${sidebarOpen ? 'translate-x-0' : hiddenTranslate}
  `;

  return (
    <div className={`${sidebarClasses} print-hide`} data-print="hide">
      {/* Top bar: toggle + mobile close */}
      <div
        className={`flex items-center ${collapsed ? 'justify-center' : 'justify-end'} h-8 flex-shrink-0 px-2`}
      >
        {/* Mobile close */}
        {!collapsed && (
          <button
            onClick={() => setSidebarOpen(false)}
            className="me-auto rounded-md p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-500 lg:hidden dark:hover:bg-gray-700"
          >
            <XMarkIcon className="h-5 w-5" />
          </button>
        )}
        {/* Collapse/Expand toggle */}
        <button
          onClick={toggleCollapsed}
          className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700 dark:hover:text-gray-300"
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {collapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
        </button>
      </div>

      {/* Navigation */}
      <nav
        ref={navRef}
        className={`min-h-0 flex-1 ${collapsed ? 'px-2' : 'px-4'} scrollbar-thin scrollbar-thumb-gray-300 scrollbar-track-transparent hover:scrollbar-thumb-gray-400 dark:scrollbar-thumb-gray-600 dark:hover:scrollbar-thumb-gray-500 space-y-1 overflow-y-auto py-1`}
      >
        {menus && menus.length > 0
          ? menus.map((menu: any, index: number) => (
              <div key={menu.id || index}>
                {collapsed ? (
                  <CollapsedMenuItem
                    menu={menu}
                    location={location}
                    t={t}
                    setSidebarOpen={setSidebarOpen}
                  />
                ) : menu.submenu && menu.submenu.length > 0 ? (
                  <SidebarSubmenu
                    submenu={menu.submenu}
                    name={t(menu.nameKey) || menu.name}
                    icon={menu.icon}
                  />
                ) : (
                  <NavLink
                    to={menu.path}
                    className={({ isActive }) =>
                      `group flex items-center rounded-lg px-3 py-2 text-sm font-medium transition-all duration-200 ${
                        isActive
                          ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
                          : 'text-gray-700 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-700 dark:hover:text-white'
                      } `
                    }
                    onClick={() => setSidebarOpen(false)}
                  >
                    <span className="me-3 flex-shrink-0">
                      {resolveIcon(menu.icon, t(menu.nameKey) || menu.name, 18)}
                    </span>
                    <span className="truncate">{t(menu.nameKey) || menu.name}</span>
                    {location.pathname === menu.path && (
                      <span className="ms-auto h-2 w-2 rounded-full bg-blue-500" />
                    )}
                  </NavLink>
                )}
              </div>
            ))
          : !collapsed && (
              <div className="py-8 text-center">
                <p className="text-sm text-gray-500 dark:text-gray-400">{t('sidebar.noMenus')}</p>
              </div>
            )}
      </nav>
    </div>
  );
}

// --- Sidebar width constant (must match w-[68px]) ---
const SIDEBAR_COLLAPSED_W = 68;

// --- Collapsed menu item with floating submenu ---

function CollapsedMenuItem({
  menu,
  location,
  t,
  setSidebarOpen,
}: {
  menu: any;
  location: ReturnType<typeof useLocation>;
  t: (key: string) => string;
  setSidebarOpen: (open: boolean) => void;
}) {
  const [open, setOpen] = useState(false);
  const [popoverStyle, setPopoverStyle] = useState<React.CSSProperties>({});
  const wrapperRef = useRef<HTMLDivElement>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const { isRTL } = useDirection();

  const menuName = t(menu.nameKey) || menu.name;
  const hasSubmenu = menu.submenu && menu.submenu.length > 0;
  const isActive = hasSubmenu
    ? isPathInSubmenu(menu.submenu, location.pathname)
    : location.pathname === menu.path;

  const show = useCallback(() => {
    clearTimeout(closeTimer.current);
    if (wrapperRef.current) {
      const rect = wrapperRef.current.getBoundingClientRect();
      const maxTop = window.innerHeight - 320;
      // In RTL the sidebar is on the right; the popover opens to the left.
      const horizontalPos = isRTL
        ? { right: SIDEBAR_COLLAPSED_W }
        : { left: SIDEBAR_COLLAPSED_W };
      setPopoverStyle({
        top: Math.max(4, Math.min(rect.top, maxTop)),
        ...horizontalPos,
      });
    }
    setOpen(true);
  }, [isRTL]);

  const scheduleClose = useCallback(() => {
    closeTimer.current = setTimeout(() => setOpen(false), 200);
  }, []);

  const cancelClose = useCallback(() => {
    clearTimeout(closeTimer.current);
  }, []);

  useEffect(() => () => clearTimeout(closeTimer.current), []);

  // Close on route change
  useEffect(() => {
    setOpen(false);
  }, [location.pathname]);

  const iconEl = resolveIcon(menu.icon, menuName, 20);

  const iconBtn = (
    <div
      className={`mx-auto flex h-10 w-12 cursor-pointer items-center justify-center rounded-lg transition-all duration-200 ${
        isActive
          ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
          : 'text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200'
      } `}
    >
      {iconEl}
    </div>
  );

  // Leaf item: click navigates, hover shows tooltip
  if (!hasSubmenu) {
    return (
      <div ref={wrapperRef} onMouseEnter={show} onMouseLeave={scheduleClose} className="relative">
        <NavLink to={menu.path} onClick={() => setSidebarOpen(false)}>
          {iconBtn}
        </NavLink>
        {open && (
          <div
            className={`animate-in fade-in pointer-events-none fixed z-[60] rounded-md bg-gray-900 px-3 py-1.5 text-xs font-medium whitespace-nowrap text-white shadow-lg duration-150 dark:bg-gray-200 dark:text-gray-900 ${
              isRTL ? 'slide-in-from-right-1' : 'slide-in-from-left-1'
            }`}
            style={popoverStyle}
          >
            {menuName}
          </div>
        )}
      </div>
    );
  }

  // Directory item: hover/click shows floating submenu
  return (
    <div ref={wrapperRef} onMouseEnter={show} onMouseLeave={scheduleClose} className="relative">
      <div onClick={show}>{iconBtn}</div>

      {open && (
        <>
          {/* Invisible bridge from icon to popover (prevents gap dropout) */}
          <div
            className="fixed z-[59] h-12"
            style={
              isRTL
                ? {
                    top: (popoverStyle.top as number) - 4,
                    right: SIDEBAR_COLLAPSED_W - 8,
                    width: 12,
                  }
                : {
                    top: (popoverStyle.top as number) - 4,
                    left: SIDEBAR_COLLAPSED_W - 8,
                    width: 12,
                  }
            }
            onMouseEnter={cancelClose}
          />
          {/* Popover panel */}
          <div
            onMouseEnter={cancelClose}
            onMouseLeave={scheduleClose}
            className={`animate-in fade-in fixed z-[60] max-h-[70vh] min-w-[210px] overflow-y-auto rounded-lg border border-gray-200 bg-white py-1.5 shadow-xl duration-150 dark:border-gray-700 dark:bg-gray-800 ${
              isRTL ? 'slide-in-from-right-2' : 'slide-in-from-left-2'
            }`}
            style={popoverStyle}
          >
            {/* Header */}
            <div className="mb-1 border-b border-gray-100 px-3 py-1.5 text-[11px] font-semibold tracking-wider text-gray-400 uppercase dark:border-gray-700 dark:text-gray-500">
              {menuName}
            </div>
            <PopoverSubmenu
              items={menu.submenu}
              location={location}
              t={t}
              onNavigate={() => {
                setOpen(false);
                setSidebarOpen(false);
              }}
            />
          </div>
        </>
      )}
    </div>
  );
}

// --- Popover submenu (recursive for nested groups) ---

function PopoverSubmenu({
  items,
  location,
  t,
  onNavigate,
  depth = 0,
}: {
  items: any[];
  location: ReturnType<typeof useLocation>;
  t: (key: string) => string;
  onNavigate: () => void;
  depth?: number;
}) {
  return (
    <div className={depth > 0 ? 'ms-3 border-s border-gray-100 dark:border-gray-700' : ''}>
      {items.map((item: any, index: number) => {
        const itemName = t(item.nameKey || item.name) || item.name;
        const hasChildren = item.submenu && item.submenu.length > 0;

        if (hasChildren) {
          return (
            <PopoverGroup
              key={item.id || index}
              item={item}
              itemName={itemName}
              location={location}
              t={t}
              onNavigate={onNavigate}
              depth={depth}
            />
          );
        }

        const isActive = location.pathname === item.path;
        return (
          <NavLink
            key={item.id || index}
            to={item.path}
            onClick={onNavigate}
            className={`mx-1 flex items-center rounded-md px-3 py-1.5 text-sm transition-colors ${
              isActive
                ? 'bg-blue-50 font-medium text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
                : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-white'
            } `}
          >
            <span className="me-2 flex h-4 w-4 flex-shrink-0 items-center justify-center">
              {resolveIcon(item.icon, itemName, 14)}
            </span>
            <span className="truncate">{itemName}</span>
            {isActive && (
              <span className="ms-auto h-1.5 w-1.5 flex-shrink-0 rounded-full bg-blue-500" />
            )}
          </NavLink>
        );
      })}
    </div>
  );
}

function PopoverGroup({
  item,
  itemName,
  location,
  t,
  onNavigate,
  depth,
}: {
  item: any;
  itemName: string;
  location: ReturnType<typeof useLocation>;
  t: (key: string) => string;
  onNavigate: () => void;
  depth: number;
}) {
  const [expanded, setExpanded] = useState(() => isPathInSubmenu(item.submenu, location.pathname));

  return (
    <div>
      <button
        onClick={() => setExpanded(!expanded)}
        className="mx-1 flex w-full items-center rounded-md px-3 py-1.5 text-sm text-gray-500 transition-colors hover:bg-gray-50 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200"
      >
        <span className="me-2 flex h-4 w-4 flex-shrink-0 items-center justify-center">
          {resolveIcon(item.icon, itemName, 14)}
        </span>
        <span className="flex-1 truncate text-left">{itemName}</span>
        <ChevronRightIcon
          className={`ms-1 h-3 w-3 transition-transform duration-200 ${expanded ? 'rotate-90' : ''}`}
        />
      </button>
      {expanded && (
        <PopoverSubmenu
          items={item.submenu}
          location={location}
          t={t}
          onNavigate={onNavigate}
          depth={depth + 1}
        />
      )}
    </div>
  );
}

function isPathInSubmenu(items: any[], pathname: string): boolean {
  for (const item of items) {
    if (item.path === pathname) return true;
    if (item.submenu && isPathInSubmenu(item.submenu, pathname)) return true;
  }
  return false;
}
