import React, { useState, useEffect, useRef } from 'react';
import { useSSE } from '~/hooks/useSSE';
import { Link } from 'react-router';
import {
  Bars3Icon,
  SunIcon,
  MoonIcon,
  ComputerDesktopIcon,
  GlobeAltIcon,
  SparklesIcon,
} from '@heroicons/react/24/outline';
import { useRootLoaderData } from '~/root-data';
import { COMMUNITY_BRANDING } from '~/config/branding';
import { useTheme } from '~/contexts/ThemeContext';
import { useI18n } from '~/contexts/I18nContext';
import { useHydrated } from '~/hooks/useHydrated';
import { InboxHeaderWidget } from '~/ui/inbox/InboxDropdown';
import { NotificationHeaderWidget } from '~/ui/notification/NotificationHeaderWidget';
import { CommandPalette } from '~/ui/CommandPalette';
import { UserMenuWidget } from '~/ui/user/UserMenuWidget';
import { useAuraBot } from '~/plugins/core-aurabot/components-shell/AuraBotProvider';

interface HeaderProps {
  sidebarOpen?: boolean;
  setSidebarOpen?: (open: boolean) => void;
  // Additional config options
  showSidebar?: boolean;
  showNotifications?: boolean;
  showLanguageSwitch?: boolean;
  simplified?: boolean;
}

export default function Header({
  sidebarOpen,
  setSidebarOpen,
  showSidebar = true,
  showNotifications = true,
  showLanguageSwitch = true,
  simplified = false,
}: HeaderProps) {
  const { state: aiState, togglePanel: toggleAI } = useAuraBot();
  const rootData = useRootLoaderData();
  const user = rootData?.user ?? null;
  const branding = rootData?.branding ?? COMMUNITY_BRANDING;
  const hasMenus = (rootData?.menus?.length ?? 0) > 0;
  const { theme, setTheme, isDark } = useTheme();
  const { t, locale, setLocale } = useI18n();
  const isHydrated = useHydrated();

  const [showThemeDropdown, setShowThemeDropdown] = useState(false);
  const [showLangDropdown, setShowLangDropdown] = useState(false);
  // Hydration marker (same pattern as Login.tsx): the SSR header renders the
  // avatar long before React attaches its click handlers, so E2E must be able
  // to wait for interactivity instead of clicking a dead button.
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => setHydrated(true), []);

  const themeDropdownRef = useRef<HTMLDivElement>(null);
  const langDropdownRef = useRef<HTMLDivElement>(null);

  const sidebarToggleLabel = sidebarOpen
    ? t('sidebar.closeMenu', undefined, 'Close navigation menu')
    : t('sidebar.openMenu', undefined, 'Open navigation menu');

  // Env chip — visible only when the build is non-production. Reads the Vite
  // mode at build time so production bundles drop the chip entirely.
  const envMode = import.meta.env.MODE;
  const envChipLabel =
    envMode === 'production'
      ? null
      : envMode === 'staging'
        ? 'Staging'
        : envMode === 'test'
          ? 'Test'
          : 'Dev';
  // Hide the trailing "· {tenantName}" indicator when the tenant name already
  // encodes the env (e.g. "AuraBoot Dev") — otherwise "AuraBoot [Dev] · AuraBoot Dev"
  // double-renders the env signal.
  const tenantDuplicatesChip =
    !!envChipLabel &&
    !!user?.tenantName &&
    user.tenantName.toLowerCase().endsWith(envChipLabel.toLowerCase());

  // Connect to SSE for real-time data sync via useSSE hook
  // (provides exponential backoff, tab visibility pause, and proper cleanup)
  useSSE({
    url: '/api/notifications/stream',
    enabled: !!user,
    listeners: [
      {
        event: 'data-sync-connected',
        handler: (data: { connectionId?: number }) => {
          if (data.connectionId) {
            (window as any).__auraSSEConnectionId = data.connectionId;
            window.dispatchEvent(
              new CustomEvent('aura:sse-connected', {
                detail: { connectionId: data.connectionId },
              }),
            );
          }
        },
      },
      {
        event: 'data:changed',
        handler: (detail: Record<string, unknown>) => {
          window.dispatchEvent(new CustomEvent('aura:data-changed', { detail }));
        },
      },
    ],
  });

  // Close dropdowns when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (themeDropdownRef.current && !themeDropdownRef.current.contains(event.target as Node)) {
        setShowThemeDropdown(false);
      }
      if (langDropdownRef.current && !langDropdownRef.current.contains(event.target as Node)) {
        setShowLangDropdown(false);
      }
    };

    const handleEscapeKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setShowThemeDropdown(false);
        setShowLangDropdown(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleEscapeKey);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscapeKey);
    };
  }, []);

  const themeOptions = [
    { value: 'light', label: t('theme.light'), icon: SunIcon },
    { value: 'dark', label: t('theme.dark'), icon: MoonIcon },
    { value: 'auto', label: t('theme.auto'), icon: ComputerDesktopIcon },
  ];

  const languageOptions = [
    { value: 'zh-CN', label: '简体中文', flag: '🇨🇳' },
    { value: 'en-US', label: 'English', flag: '🇺🇸' },
    { value: 'ja-JP', label: '日本語', flag: '🇯🇵' },
    { value: 'ko-KR', label: '한국어', flag: '🇰🇷' },
  ];

  return (
    <>
      <header
        className="print-hide fixed top-0 right-0 left-0 z-50 border-b border-gray-200 bg-white/95 shadow-lg backdrop-blur-sm dark:border-gray-700 dark:bg-gray-800/95"
        data-print="hide"
        data-hydrated={hydrated ? 'true' : 'false'}
      >
        <div className="flex h-14 items-center justify-between bg-gradient-to-r from-white/50 to-gray-50/50 px-2 sm:px-6 lg:px-8 dark:from-gray-800/50 dark:to-gray-900/50">
          {/* Left: logo and menu button */}
          <div className="flex min-w-0 items-center">
            {showSidebar && hasMenus && (
              <button
                type="button"
                aria-controls="app-sidebar"
                aria-expanded={Boolean(sidebarOpen)}
                aria-label={sidebarToggleLabel}
                data-testid="header-sidebar-toggle"
                className="rounded-lg border border-blue-200 bg-blue-50 p-3 text-blue-600 shadow-sm transition-all duration-200 hover:bg-blue-100 hover:shadow-md focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none lg:hidden dark:border-blue-800 dark:bg-blue-900/20 dark:text-blue-400 dark:hover:bg-blue-900/30 dark:focus:ring-offset-gray-800"
                onClick={() => setSidebarOpen?.(!sidebarOpen)}
              >
                <Bars3Icon className="h-6 w-6" />
              </button>
            )}

            <Link to="/" className="ms-2 flex shrink-0 items-center sm:ms-4 lg:ms-0">
              <img
                className="h-7 w-7 rounded-lg sm:h-8 sm:w-8"
                src={branding.logoUrl}
                alt={branding.productName}
              />
              <span
                data-testid="header-brand-name"
                className="ms-3 hidden text-xl font-bold text-gray-900 xl:inline dark:text-white"
              >
                {branding.productName}
              </span>
              {envChipLabel && (
                <span
                  data-testid="header-env-chip"
                  className="ml-2 hidden rounded bg-[#f6f9fc] px-1.5 py-0.5 text-[11px] font-medium text-gray-500 xl:inline-flex dark:bg-gray-700 dark:text-gray-300"
                >
                  {envChipLabel}
                </span>
              )}
            </Link>

            {/* Current tenant name — hidden when env chip already encodes it (avoids "AuraBoot [Dev] · AuraBoot Dev") */}
            {!simplified && user?.tenantName && !tenantDuplicatesChip && (
              <span className="ms-3 hidden items-center text-sm text-gray-400 2xl:flex dark:text-gray-500">
                <span className="mx-2">·</span>
                <span data-testid="current-tenant-name">{user.tenantName}</span>
              </span>
            )}
          </div>

          {/* Right: toolbar */}
          <div className="flex shrink-0 items-center gap-1">
            {/* Global search Cmd+K */}
            {!simplified && <CommandPalette />}

            {/* AuraBot toggle */}
            {!simplified && (
              <button
                onClick={toggleAI}
                data-testid="ai-panel-toggle"
                className={`hidden h-8 w-8 items-center justify-center rounded-xl transition-all duration-200 hover:scale-105 hover:shadow-md sm:flex ${
                  aiState.panelState === 'expanded'
                    ? 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400'
                    : 'text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200'
                }`}
                title="AuraBot (⌘J)"
              >
                <SparklesIcon className="h-5 w-5" />
              </button>
            )}

            {/* Unified inbox entry point */}
            {!simplified && showNotifications && <InboxHeaderWidget />}

            {/* Notification centre bell (unread badge + SSE live updates) */}
            {!simplified && showNotifications && <NotificationHeaderWidget />}

            {/* Language switch — only shown in non-compact mode */}
            {!simplified && showLanguageSwitch && (
              <div
                className="relative hidden sm:block"
                ref={langDropdownRef}
                data-testid="lang-toggle"
              >
                <button
                  onClick={() => setShowLangDropdown(!showLangDropdown)}
                  className="flex h-8 w-8 items-center justify-center rounded-xl text-gray-500 transition-all duration-200 hover:scale-105 hover:bg-gray-100 hover:text-gray-700 hover:shadow-md dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200"
                >
                  <GlobeAltIcon className="h-5 w-5" />
                </button>

                {showLangDropdown && (
                  <div
                    data-testid="lang-dropdown"
                    className="absolute end-0 z-50 mt-2 w-48 rounded-lg border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-700 dark:bg-gray-800"
                  >
                    {languageOptions.map((option) => (
                      <button
                        key={option.value}
                        onClick={() => {
                          setLocale(option.value);
                          setShowLangDropdown(false);
                        }}
                        className={`flex w-full items-center px-4 py-2 text-start text-sm transition-colors hover:bg-gray-100 dark:hover:bg-gray-700 ${
                          locale === option.value
                            ? 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400'
                            : 'text-gray-700 dark:text-gray-300'
                        }`}
                      >
                        <span className="me-2.5">{option.flag}</span>
                        {option.label}
                      </button>
                    ))}
                  </div>
                )}
              </div>
            )}

            {/* Theme switch */}
            <div
              className="relative hidden sm:block"
              ref={themeDropdownRef}
              data-testid="theme-toggle"
            >
              <button
                onClick={() => setShowThemeDropdown(!showThemeDropdown)}
                className="flex h-8 w-8 items-center justify-center rounded-xl text-gray-500 transition-all duration-200 hover:scale-105 hover:bg-gray-100 hover:text-gray-700 hover:shadow-md dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200"
              >
                {isHydrated && isDark ? (
                  <MoonIcon className="h-6 w-6" />
                ) : (
                  <SunIcon className="h-6 w-6" />
                )}
              </button>

              {showThemeDropdown && (
                <div
                  data-testid="theme-dropdown"
                  className="absolute end-0 z-50 mt-2 w-48 rounded-lg border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-700 dark:bg-gray-800"
                >
                  {themeOptions.map((option) => {
                    const IconComponent = option.icon;
                    return (
                      <button
                        key={option.value}
                        onClick={() => {
                          setTheme(option.value as any);
                          setShowThemeDropdown(false);
                        }}
                        className={`flex w-full items-center px-4 py-2 text-start text-sm transition-colors hover:bg-gray-100 dark:hover:bg-gray-700 ${
                          theme === option.value
                            ? 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400'
                            : 'text-gray-700 dark:text-gray-300'
                        }`}
                      >
                        <IconComponent className="me-3 h-4 w-4" />
                        {option.label}
                      </button>
                    );
                  })}
                </div>
              )}
            </div>

            {/* Account menu lives in UserMenuWidget (bottom-start corner) */}
            {!user && (
              <div className="flex items-center space-x-3">
                <Link
                  to="/login"
                  className="font-medium text-gray-600 transition-colors hover:text-gray-900 dark:text-gray-300 dark:hover:text-white"
                >
                  {t('auth.login')}
                </Link>
                <Link
                  to="/signup"
                  className="rounded-lg bg-blue-600 px-4 py-2 font-medium text-white transition-colors hover:bg-blue-700"
                >
                  {t('auth.register')}
                </Link>
              </div>
            )}
          </div>
        </div>
      </header>

      {/* Account entry (avatar + profile/workspace/logout menu) — docked in
          the sidebar footer when the navigation sidebar is present; the
          floating bottom-start widget is the fallback for sidebar-less
          surfaces (e.g. tenant selection, accounts without menus) */}
      {!(showSidebar && hasMenus) && <UserMenuWidget simplified={simplified} />}
    </>
  );
}
