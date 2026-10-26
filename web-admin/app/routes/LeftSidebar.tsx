import { useApplicationCapabilities } from '~/contexts/ApplicationCapabilitiesContext';
import { filterAgentMenus } from '~/shared/services/agent-surfaces';
import { useRef, useEffect, useState, useCallback } from 'react';
import { NavLink, useLocation, useRevalidator } from 'react-router';
import { useRootLoaderData } from '~/root-data';
import { useI18n } from '~/contexts/I18nContext';
import SidebarSubmenu, { resolveMenuLabel } from '~/routes/SidebarSubmenu';
import { XMarkIcon, ChevronRightIcon } from '@heroicons/react/24/outline';
import { PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import { resolveIcon } from '~/utils/icon-resolver';
import { useDirection } from '~/hooks/useDirection';
import { COMMUNITY_BRANDING } from '~/config/branding';
import { useTenantTheme } from '~/contexts/TenantThemeContext';
import {
  usePagePresentation,
  usePresentationNavigation,
} from '~/shared/layout/PagePresentationContext';
import { UserMenuWidget } from '~/ui/user/UserMenuWidget';
import {
  findCurrentNavigationLink,
  workspaceSidebarGeometry,
} from '~/shared/layout/workspaceShell';

const COLLAPSED_KEY = 'sidebar-collapsed';

interface LeftSidebarProps {
  sidebarOpen: boolean;
  setSidebarOpen: (open: boolean) => void;
}

export default function LeftSidebar({ sidebarOpen, setSidebarOpen }: LeftSidebarProps) {
  const workspace = usePagePresentation()?.mode === 'workspace';
  const { setSidebarWidth, shell, viewportWidth = 1280, isOverlay } = usePresentationNavigation();
  const tokenized = shell?.useThemeTokens === true;
  const tenantTheme = useTenantTheme();
  const location = useLocation();
  const rootData = useRootLoaderData();
  const { agent } = useApplicationCapabilities();
  const menus = filterAgentMenus(rootData?.menus ?? [], agent === 'installed');
  const { t, locale } = useI18n();
  const { isRTL } = useDirection();
  const navRef = useRef<HTMLElement>(null);
  const revalidator = useRevalidator();

  const [preferredCollapsed, setCollapsed] = useState(() => {
    if (typeof window !== 'undefined') {
      return localStorage.getItem(COLLAPSED_KEY) === 'true';
    }
    return false;
  });

  const geometry = shell
    ? workspaceSidebarGeometry(shell, viewportWidth, preferredCollapsed)
    : null;
  const collapsed = geometry?.collapsed ?? preferredCollapsed;
  const panelWidth = geometry?.panelWidth ?? (collapsed ? 68 : workspace ? 216 : 256);
  useEffect(() => {
    setSidebarWidth(shell && menus.length === 0 ? 0 : panelWidth);
  }, [panelWidth, shell, menus.length, setSidebarWidth]);
  useEffect(() => {
    if (shell && isOverlay && sidebarOpen)
      navRef.current?.querySelector<HTMLElement>('a[href], button')?.focus();
  }, [shell, isOverlay, sidebarOpen]);

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

  // Auto-scroll to active menu item when path changes.
  // Use `block: 'center'` so the active item lands in the middle of the
  // scroll container — this prevents adjacent submenu chips from overlapping
  // the click target on initial navigation (which broke Playwright clicks
  // with intercepted-pointer-events failures, e.g. QB-07/08).
  useEffect(() => {
    if (collapsed) return;
    const timer = setTimeout(() => {
      if (navRef.current) {
        const activeItem = findCurrentNavigationLink(navRef.current);
        if (activeItem) {
          activeItem.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [location.pathname, collapsed]);

  const widthClass = collapsed ? 'w-[68px]' : workspace ? 'w-[216px]' : 'w-64';

  if (menus.length === 0) return null;

  // In RTL mode the sidebar is anchored to the right edge; the hidden-state
  // translation must be inverted so it slides out to the right instead of left.
  const hiddenTranslate = isRTL ? 'translate-x-full' : '-translate-x-full';
  const positionClasses = shell
    ? isOverlay
      ? 'fixed inset-y-0 ltr:left-0 rtl:right-0'
      : 'relative shrink-0'
    : 'fixed inset-y-0 ltr:left-0 rtl:right-0 lg:translate-x-0 lg:static lg:inset-0';
  const translateClass =
    shell && !isOverlay ? 'translate-x-0' : sidebarOpen ? 'translate-x-0' : hiddenTranslate;
  const sidebarClasses = `${positionClasses} z-50 ${shell ? '' : widthClass}
    ${tokenized ? 'border-border bg-panel' : 'bg-white dark:bg-gray-800 border-gray-200 dark:border-gray-700'}
    ltr:border-r rtl:border-l transform transition-all duration-300 ease-in-out
    flex flex-col overflow-visible ${translateClass}`;

  return (
    <div
      id="app-sidebar"
      className={`${sidebarClasses} print-hide`}
      data-print="hide"
      data-testid="sidebar"
      style={shell ? { width: panelWidth } : undefined}
      inert={shell && isOverlay && !sidebarOpen ? true : undefined}
      aria-hidden={shell && isOverlay && !sidebarOpen ? true : undefined}
    >
      {workspace && !collapsed && (
        <>
          <NavLink
            to="/"
            className={`flex shrink-0 items-center gap-3 px-6 ${shell ? '' : 'h-14'}`}
            style={shell ? { height: shell.sidebar.brandHeight } : undefined}
          >
            <img
              className="h-6 w-6"
              src={
                tenantTheme?.logoUrl || rootData?.branding?.logoUrl || COMMUNITY_BRANDING.logoUrl
              }
              alt=""
            />
            <span className="text-text text-lg font-semibold tracking-tight">
              {tenantTheme?.brandName ||
                rootData?.branding?.productName ||
                COMMUNITY_BRANDING.productName}
            </span>
          </NavLink>
          <div className="border-border mx-3.5 my-5 rounded-lg border px-3 py-3">
            <div className="text-text text-xs font-semibold">
              {rootData?.user?.tenantName || t('sidebar.workspace', undefined, 'Workspace')}
            </div>
            <div className="text-text-3 mt-1 text-[11px]">
              {t(
                'sidebar.currentSpace',
                undefined,
                locale.startsWith('zh') ? '当前空间' : 'Current space',
              )}
            </div>
          </div>
        </>
      )}
      {/* Top bar: toggle + mobile close */}
      <div
        className={`flex items-center ${collapsed ? 'justify-center' : 'justify-end'} h-8 flex-shrink-0 px-2`}
      >
        {/* Mobile close */}
        {!collapsed && (!shell || isOverlay) && (
          <button
            onClick={() => setSidebarOpen(false)}
            aria-label={t('action.close', undefined, 'Close')}
            className={`me-auto rounded-md p-1.5 ${shell ? '' : 'lg:hidden'} ${tokenized ? 'text-text-3 hover:bg-hover hover:text-text' : 'text-gray-400 hover:bg-gray-100 hover:text-gray-500 dark:hover:bg-gray-700'}`}
          >
            <XMarkIcon className="h-5 w-5" />
          </button>
        )}
        {/* Collapse/Expand toggle */}
        {(!shell || !isOverlay) && (
          <button
            onClick={toggleCollapsed}
            data-testid="sidebar-collapse-toggle"
            aria-expanded={!collapsed}
            className={`rounded-md p-1.5 transition-colors ${tokenized ? 'text-text-3 hover:bg-hover hover:text-text' : 'text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700 dark:hover:text-gray-300'}`}
            title={t(collapsed ? 'sidebar.expand' : 'sidebar.collapse')}
          >
            {collapsed ? <PanelLeftOpen size={16} /> : <PanelLeftClose size={16} />}
          </button>
        )}
      </div>

      {/* Navigation */}
      <nav
        ref={navRef}
        className={`min-h-0 flex-1 ${collapsed ? 'px-2' : workspace ? 'px-3.5' : 'px-4'} scrollbar-thin scrollbar-thumb-gray-300 scrollbar-track-transparent space-y-1 overflow-y-auto py-1 pb-2 hover:scrollbar-thumb-gray-400 dark:scrollbar-thumb-gray-600 dark:hover:scrollbar-thumb-gray-500`}
      >
        {menus.map((menu: any, index: number) => (
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
                name={resolveMenuLabel(t, menu)}
                icon={menu.icon}
                onNavigate={() => setSidebarOpen(false)}
              />
            ) : (
              <NavLink
                to={menu.path}
                className={({ isActive }) =>
                  `group flex items-center rounded-lg px-3 py-2 text-sm font-medium transition-all duration-200 ${
                    isActive
                      ? tokenized
                        ? 'bg-accent-weak text-accent'
                        : 'bg-blue-100 text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
                      : tokenized
                        ? 'text-text-2 hover:bg-hover hover:text-text'
                        : 'text-gray-700 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-700 dark:hover:text-white'
                  } `
                }
                onClick={() => setSidebarOpen(false)}
              >
                <span className="me-3 flex-shrink-0">
                  {resolveIcon(menu.icon, resolveMenuLabel(t, menu), 18)}
                </span>
                <span className="truncate">{resolveMenuLabel(t, menu)}</span>
                {location.pathname === menu.path && (
                  <span
                    className={`ms-auto h-2 w-2 rounded-full ${tokenized ? 'bg-accent' : 'bg-blue-500'}`}
                  />
                )}
              </NavLink>
            )}
          </div>
        ))}
      </nav>

      {/* Account entry — full-width footer row (moved out of the header toolbar) */}
      <div
        className={`flex-shrink-0 border-t border-gray-200 dark:border-gray-700 ${
          collapsed ? 'px-2 py-2' : 'px-3 py-2'
        }`}
      >
        <UserMenuWidget variant="sidebar" collapsed={collapsed} />
      </div>
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
  const tokenized = usePagePresentation()?.workspaceShell?.useThemeTokens === true;
  const [open, setOpen] = useState(false);
  const [popoverStyle, setPopoverStyle] = useState<React.CSSProperties>({});
  const wrapperRef = useRef<HTMLDivElement>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const { isRTL } = useDirection();

  const menuName = resolveMenuLabel(t, menu);
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
      const sidebarRect = wrapperRef.current.closest('#app-sidebar')?.getBoundingClientRect();
      const horizontalPos = isRTL
        ? { right: sidebarRect ? window.innerWidth - sidebarRect.left : SIDEBAR_COLLAPSED_W }
        : { left: sidebarRect?.right ?? SIDEBAR_COLLAPSED_W };
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
          ? tokenized
            ? 'bg-accent-weak text-accent'
            : 'bg-blue-100 text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
          : tokenized
            ? 'text-text-3 hover:bg-hover hover:text-text'
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
                    right: Number(popoverStyle.right ?? SIDEBAR_COLLAPSED_W) - 8,
                    width: 12,
                  }
                : {
                    top: (popoverStyle.top as number) - 4,
                    left: Number(popoverStyle.left ?? SIDEBAR_COLLAPSED_W) - 8,
                    width: 12,
                  }
            }
            onMouseEnter={cancelClose}
          />
          {/* Popover panel */}
          <div
            onMouseEnter={cancelClose}
            onMouseLeave={scheduleClose}
            className={`animate-in fade-in fixed z-[60] max-h-[70vh] min-w-[210px] overflow-y-auto rounded-lg border py-1.5 shadow-xl duration-150 ${tokenized ? 'border-border bg-panel' : 'border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800'} ${
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
  const tokenized = usePagePresentation()?.workspaceShell?.useThemeTokens === true;
  return (
    <div className={depth > 0 ? 'ms-3 border-s border-gray-100 dark:border-gray-700' : ''}>
      {items.map((item: any, index: number) => {
        const itemName = resolveMenuLabel(t, item);
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
                ? tokenized
                  ? 'bg-accent-weak text-accent font-medium'
                  : 'bg-blue-50 font-medium text-blue-700 dark:bg-blue-900/20 dark:text-blue-300'
                : tokenized
                  ? 'text-text-2 hover:bg-hover hover:text-text'
                  : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-white'
            } `}
          >
            <span className="me-2 flex h-4 w-4 flex-shrink-0 items-center justify-center">
              {resolveIcon(item.icon, itemName, 14)}
            </span>
            <span className="truncate">{itemName}</span>
            {isActive && (
              <span
                className={`ms-auto h-1.5 w-1.5 flex-shrink-0 rounded-full ${tokenized ? 'bg-accent' : 'bg-blue-500'}`}
              />
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
  const tokenized = usePagePresentation()?.workspaceShell?.useThemeTokens === true;
  const [expanded, setExpanded] = useState(() => isPathInSubmenu(item.submenu, location.pathname));

  return (
    <div>
      <button
        onClick={() => setExpanded(!expanded)}
        className={`mx-1 flex w-full items-center rounded-md px-3 py-1.5 text-sm transition-colors ${tokenized ? 'text-text-3 hover:bg-hover hover:text-text' : 'text-gray-500 hover:bg-gray-50 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200'}`}
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
