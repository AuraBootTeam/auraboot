import React, { useState, useEffect, useRef } from 'react';
import { Link, useFetcher } from 'react-router';
import { SunIcon, MoonIcon, ComputerDesktopIcon, GlobeAltIcon } from '@heroicons/react/24/outline';
import { useTheme } from '~/contexts/ThemeContext';
import { useI18n } from '~/contexts/I18nContext';
import { useHydrated } from '~/hooks/useHydrated';
import { useAuth } from '~/contexts/AuthContext';

export default function AuthHeader() {
  const { theme, setTheme, isDark } = useTheme();
  const { t, locale, setLocale } = useI18n();
  const isHydrated = useHydrated();
  const { isAuthenticated } = useAuth();
  const logoutFetcher = useFetcher();

  const [showThemeDropdown, setShowThemeDropdown] = useState(false);
  const [showLangDropdown, setShowLangDropdown] = useState(false);
  const themeDropdownRef = useRef<HTMLDivElement>(null);
  const langDropdownRef = useRef<HTMLDivElement>(null);

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
    { value: 'light', label: t('theme.light') || '浅色主题', icon: SunIcon },
    { value: 'dark', label: t('theme.dark') || '深色主题', icon: MoonIcon },
    { value: 'auto', label: t('theme.auto') || '跟随系统', icon: ComputerDesktopIcon },
  ];

  const languageOptions = [
    { value: 'zh-CN', label: '简体中文', flag: '🇨🇳' },
    { value: 'en-US', label: 'English', flag: '🇺🇸' },
  ];

  return (
    <header className="fixed top-0 right-0 left-0 z-50 border-b border-gray-200/50 bg-white/80 backdrop-blur-md dark:border-gray-700/50 dark:bg-gray-900/80">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6 lg:px-8">
        {/* Left: Logo */}
        <Link to="/" className="flex items-center">
          <img className="h-8 w-8 rounded-lg" src="/logo192.png" alt="Logo" />
          <span className="ml-3 text-xl font-bold text-gray-900 dark:text-white">AuraBoot</span>
        </Link>

        {/* Right: Toolbar */}
        <div className="flex items-center space-x-2">
          {/* Language toggle */}
          <div className="relative" ref={langDropdownRef}>
            <button
              onClick={() => setShowLangDropdown(!showLangDropdown)}
              className="rounded-lg p-2 text-gray-500 transition-all duration-200 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-700/50 dark:hover:text-gray-200"
              aria-label="Switch language"
            >
              <GlobeAltIcon className="h-5 w-5" />
            </button>
            {showLangDropdown && (
              <div className="absolute right-0 z-50 mt-2 w-40 rounded-lg border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-700 dark:bg-gray-800">
                {languageOptions.map((option) => (
                  <button
                    key={option.value}
                    onClick={() => {
                      setLocale(option.value);
                      setShowLangDropdown(false);
                    }}
                    className={`flex w-full items-center px-4 py-2 text-left text-sm transition-colors hover:bg-gray-100 dark:hover:bg-gray-700 ${
                      locale === option.value
                        ? 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400'
                        : 'text-gray-700 dark:text-gray-300'
                    }`}
                  >
                    <span className="mr-2.5">{option.flag}</span>
                    {option.label}
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Theme toggle */}
          <div className="relative" ref={themeDropdownRef}>
            <button
              onClick={() => setShowThemeDropdown(!showThemeDropdown)}
              className="rounded-lg p-2 text-gray-500 transition-all duration-200 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-700/50 dark:hover:text-gray-200"
              aria-label="Switch theme"
            >
              {isHydrated && isDark ? (
                <MoonIcon className="h-5 w-5" />
              ) : (
                <SunIcon className="h-5 w-5" />
              )}
            </button>
            {showThemeDropdown && (
              <div className="absolute right-0 z-50 mt-2 w-40 rounded-lg border border-gray-200 bg-white py-1 shadow-lg dark:border-gray-700 dark:bg-gray-800">
                {themeOptions.map((option) => {
                  const IconComponent = option.icon;
                  return (
                    <button
                      key={option.value}
                      onClick={() => {
                        setTheme(option.value as any);
                        setShowThemeDropdown(false);
                      }}
                      className={`flex w-full items-center px-4 py-2 text-left text-sm transition-colors hover:bg-gray-100 dark:hover:bg-gray-700 ${
                        theme === option.value
                          ? 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400'
                          : 'text-gray-700 dark:text-gray-300'
                      }`}
                    >
                      <IconComponent className="mr-3 h-4 w-4" />
                      {option.label}
                    </button>
                  );
                })}
              </div>
            )}
          </div>

          {/* Auth buttons */}
          <div className="ml-2 flex items-center space-x-2">
            {isAuthenticated ? (
              <logoutFetcher.Form method="post" action="/logout">
                <button
                  type="submit"
                  className="rounded-lg px-3 py-1.5 text-sm font-medium text-red-600 transition-colors hover:bg-red-50 hover:text-red-700 dark:text-red-400 dark:hover:bg-red-900/20 dark:hover:text-red-300"
                >
                  {t('user.logout') || '退出'}
                </button>
              </logoutFetcher.Form>
            ) : (
              <>
                <Link
                  to="/login"
                  className="rounded-lg px-3 py-1.5 text-sm font-medium text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-700/50 dark:hover:text-white"
                >
                  {t('auth.login') || '登录'}
                </Link>
                <Link
                  to="/signup"
                  className="rounded-lg bg-blue-600 px-4 py-1.5 text-sm font-medium text-white transition-colors hover:bg-blue-700"
                >
                  {t('auth.register') || '注册'}
                </Link>
              </>
            )}
          </div>
        </div>
      </div>
    </header>
  );
}
