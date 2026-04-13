import React, { useState, useEffect, useRef } from 'react';
import { useSSE } from '~/hooks/useSSE';
import { Link } from 'react-router';
import {
  Bars3Icon,
  BuildingOffice2Icon,
  Cog6ToothIcon,
  PowerIcon,
  SunIcon,
  MoonIcon,
  ComputerDesktopIcon,
  GlobeAltIcon,
  SparklesIcon,
} from '@heroicons/react/24/outline';
import { useRootLoaderData } from '~/root';
import { useTheme } from '~/contexts/ThemeContext';
import { useI18n } from '~/contexts/I18nContext';
import { useHydrated } from '~/hooks/useHydrated';
import { InboxHeaderWidget } from '~/ui/inbox/InboxDropdown';
import { CommandPalette } from '~/ui/CommandPalette';
import { useAuraBot } from '~/plugins/core-aurabot/components-shell/AuraBotProvider';

interface HeaderProps {
  sidebarOpen?: boolean;
  setSidebarOpen?: (open: boolean) => void;
  // 新增配置选项
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
  const { theme, setTheme, isDark } = useTheme();
  const { t, locale, setLocale } = useI18n();
  const isHydrated = useHydrated();

  const [showUserDropdown, setShowUserDropdown] = useState(false);
  const [showThemeDropdown, setShowThemeDropdown] = useState(false);
  const [showLangDropdown, setShowLangDropdown] = useState(false);
  const [spaces, setSpaces] = useState<Array<{ tenantId: string; tenantName: string; tenantDisplayName: string; spaceType: string }>>([]);

  const userDropdownRef = useRef<HTMLDivElement>(null);
  const themeDropdownRef = useRef<HTMLDivElement>(null);
  const langDropdownRef = useRef<HTMLDivElement>(null);

  const workspaceLabel = locale.startsWith('zh') ? '工作空间' : 'Workspaces';
  const platformConsoleLabel = locale.startsWith('zh') ? '平台控制台' : 'Platform Console';

  // Lazy-load spaces for tenant switching in avatar menu
  useEffect(() => {
    if (!user || simplified) return;
    fetch('/api/tenant-selection/my-spaces')
      .then((res) => (res.ok ? res.json() : null))
      .then((result) => { if (result?.data) setSpaces(result.data); })
      .catch(() => {});
  }, [user, simplified]);

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

  // 处理点击外部关闭下拉菜单
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (userDropdownRef.current && !userDropdownRef.current.contains(event.target as Node)) {
        setShowUserDropdown(false);
      }
      if (themeDropdownRef.current && !themeDropdownRef.current.contains(event.target as Node)) {
        setShowThemeDropdown(false);
      }
      if (langDropdownRef.current && !langDropdownRef.current.contains(event.target as Node)) {
        setShowLangDropdown(false);
      }
    };

    const handleEscapeKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setShowUserDropdown(false);
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
    <header
      className="print-hide fixed top-0 right-0 left-0 z-50 border-b border-gray-200 bg-white/95 shadow-lg backdrop-blur-sm dark:border-gray-700 dark:bg-gray-800/95"
      data-print="hide"
    >
      <div className="flex h-16 items-center justify-between bg-gradient-to-r from-white/50 to-gray-50/50 px-4 sm:px-6 lg:px-8 dark:from-gray-800/50 dark:to-gray-900/50">
        {/* 左侧：Logo和菜单按钮 */}
        <div className="flex items-center">
          {showSidebar && (
            <button
              type="button"
              className="rounded-lg border border-blue-200 bg-blue-50 p-3 text-blue-600 shadow-sm transition-all duration-200 hover:bg-blue-100 hover:shadow-md focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none lg:hidden dark:border-blue-800 dark:bg-blue-900/20 dark:text-blue-400 dark:hover:bg-blue-900/30 dark:focus:ring-offset-gray-800"
              onClick={() => setSidebarOpen?.(!sidebarOpen)}
            >
              <Bars3Icon className="h-6 w-6" />
            </button>
          )}

          <Link to="/" className="ms-4 flex items-center lg:ms-0">
            <img className="h-8 w-8 rounded-lg" src="/logo192.png" alt="Logo" />
            <span className="ms-3 text-xl font-bold text-gray-900 dark:text-white">AuraBoot</span>
          </Link>

          {/* Current tenant name — always visible context indicator */}
          {!simplified && user?.tenantName && (
            <span className="ms-3 hidden items-center text-sm text-gray-400 sm:flex dark:text-gray-500">
              <span className="mx-2">·</span>
              <span data-testid="current-tenant-name">{user.tenantName}</span>
            </span>
          )}
        </div>

        {/* 右侧：工具栏 */}
        <div className="flex items-center space-x-4">
          {/* 全局搜索 Cmd+K */}
          {!simplified && <CommandPalette />}

          {/* AuraBot toggle */}
          {!simplified && (
            <button
              onClick={toggleAI}
              data-testid="ai-panel-toggle"
              className={`rounded-xl p-2.5 transition-all duration-200 hover:scale-105 hover:shadow-md ${
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

          {/* 语言切换 - 只在非简化模式显示 */}
          {!simplified && showLanguageSwitch && (
            <div className="relative" ref={langDropdownRef} data-testid="lang-toggle">
              <button
                onClick={() => setShowLangDropdown(!showLangDropdown)}
                className="rounded-xl p-2.5 text-gray-500 transition-all duration-200 hover:scale-105 hover:bg-gray-100 hover:text-gray-700 hover:shadow-md dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200"
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

          {/* 主题切换 */}
          <div className="relative" ref={themeDropdownRef} data-testid="theme-toggle">
            <button
              onClick={() => setShowThemeDropdown(!showThemeDropdown)}
              className="rounded-xl p-2.5 text-gray-500 transition-all duration-200 hover:scale-105 hover:bg-gray-100 hover:text-gray-700 hover:shadow-md dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200"
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

          {/* 用户菜单 */}
          {user ? (
            <div className="relative" ref={userDropdownRef} data-testid="user-menu">
              <button
                onClick={() => setShowUserDropdown(!showUserDropdown)}
                className="flex items-center rounded-full p-1.5 ring-2 ring-transparent transition-all duration-200 hover:scale-105 hover:bg-gray-100 hover:shadow-md hover:ring-gray-200 dark:hover:bg-gray-700 dark:hover:ring-gray-600"
              >
                <img
                  className="h-8 w-8 rounded-full object-cover shadow-sm"
                  src="/avatar.jpeg"
                  alt="User avatar"
                />
              </button>

              {showUserDropdown && (
                <div
                  data-testid="user-dropdown"
                  className="absolute end-0 z-[70] mt-2 w-64 rounded-lg border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-700 dark:bg-gray-800"
                >
                  {/* User info */}
                  <div className="border-b border-gray-200 px-4 py-3 dark:border-gray-700">
                    <p className="text-sm font-medium text-gray-900 dark:text-white">
                      {user.name || t('user.defaultName')}
                    </p>
                    <p className="truncate text-xs text-gray-500 dark:text-gray-400">
                      {user.email}
                    </p>
                  </div>

                  {/* Tenant list */}
                  {spaces.filter(s => s.spaceType === 'business').length > 0 && (
                    <div className="border-b border-gray-200 py-1 dark:border-gray-700">
                      <p className="px-4 py-1 text-xs font-semibold uppercase tracking-wider text-gray-400 dark:text-gray-500">
                        {workspaceLabel}
                      </p>
                      {spaces.filter(s => s.spaceType === 'business').map((space) => {
                        const isCurrent = String(user.tenantId) === String(space.tenantId);
                        return (
                          <button
                            key={space.tenantId}
                            data-testid={`tenant-switch-${space.tenantId}`}
                            onClick={() => {
                              if (isCurrent) return;
                              setShowUserDropdown(false);
                              const form = document.createElement('form');
                              form.method = 'POST';
                              form.action = '/_action/switch-space';
                              const tid = document.createElement('input');
                              tid.type = 'hidden'; tid.name = 'tenantId'; tid.value = space.tenantId;
                              form.appendChild(tid);
                              const redir = document.createElement('input');
                              redir.type = 'hidden'; redir.name = 'redirectTo'; redir.value = '/';
                              form.appendChild(redir);
                              document.body.appendChild(form);
                              form.submit();
                            }}
                            className={`flex w-full items-center gap-3 px-4 py-2 text-sm transition-colors ${
                              isCurrent
                                ? 'bg-blue-50 font-medium text-blue-700 dark:bg-blue-900/20 dark:text-blue-400'
                                : 'text-gray-700 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700'
                            }`}
                          >
                            <BuildingOffice2Icon className={`h-4 w-4 flex-shrink-0 ${isCurrent ? 'text-blue-500' : 'text-gray-400'}`} />
                            <span className="truncate">{space.tenantDisplayName || space.tenantName}</span>
                            {isCurrent && <span className="ms-auto text-xs text-blue-500">&#10003;</span>}
                          </button>
                        );
                      })}
                    </div>
                  )}

                  {/* Platform Console — only for platform_admin users */}
                  {spaces.some(s => s.spaceType === 'platform') && (
                    <div className="border-b border-gray-200 py-1 dark:border-gray-700">
                      <button
                        data-testid="platform-console-link"
                        onClick={() => {
                          setShowUserDropdown(false);
                          const platformSpace = spaces.find(s => s.spaceType === 'platform');
                          if (!platformSpace) return;
                          const form = document.createElement('form');
                          form.method = 'POST';
                          form.action = '/_action/switch-space';
                          const tid = document.createElement('input');
                          tid.type = 'hidden'; tid.name = 'tenantId'; tid.value = platformSpace.tenantId;
                          form.appendChild(tid);
                          const redir = document.createElement('input');
                          redir.type = 'hidden'; redir.name = 'redirectTo'; redir.value = '/platform/marketplace';
                          form.appendChild(redir);
                          document.body.appendChild(form);
                          form.submit();
                        }}
                        className="flex w-full items-center gap-3 px-4 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700"
                      >
                        <Cog6ToothIcon className="h-4 w-4 flex-shrink-0 text-gray-400" />
                        <span>{platformConsoleLabel}</span>
                      </button>
                    </div>
                  )}

                  {/* Logout */}
                  <Link
                    to="/logout"
                    className="flex items-center px-4 py-2 text-sm text-red-600 transition-colors hover:bg-gray-100 dark:text-red-400 dark:hover:bg-gray-700"
                    onClick={() => setShowUserDropdown(false)}
                  >
                    <PowerIcon className="me-3 h-4 w-4" />
                    {t('user.logout')}
                  </Link>
                </div>
              )}
            </div>
          ) : (
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
  );
}
