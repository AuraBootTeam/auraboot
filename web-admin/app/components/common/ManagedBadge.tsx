import React from 'react';
import { useI18n } from '~/contexts/I18nContext';

/**
 * Fallback translations for managed resource UI.
 * These are platform-level UI strings, not business labels.
 */
const FALLBACKS: Record<string, Record<string, string>> = {
  'plugin.managed.badge.tooltip': {
    'zh-CN': '此资源由插件管理',
    'en-US': 'This resource is managed by a plugin',
  },
  'plugin.managed.badge.modified.tooltip': {
    'zh-CN': '此资源由插件管理，但已被手动修改',
    'en-US': 'This resource is managed by a plugin but has been manually modified',
  },
  'plugin.managed.badge.modified': {
    'zh-CN': '已修改',
    'en-US': 'modified',
  },
};

function useManagedT() {
  const { t, locale } = useI18n();
  return (key: string, params?: Record<string, string>) => {
    const result = t(key, params);
    // If t() returned the key itself (no translation found), use fallback
    if (result === key && FALLBACKS[key]) {
      let text = FALLBACKS[key][locale] || FALLBACKS[key]['en-US'] || key;
      if (params) {
        Object.entries(params).forEach(([k, v]) => {
          text = text.split(`{${k}}`).join(v);
        });
      }
      return text;
    }
    return result;
  };
}

interface ManagedBadgeProps {
  pluginName: string;
  userModified?: boolean;
  className?: string;
}

/**
 * Badge shown in list pages to indicate a resource is managed by a plugin.
 */
export function ManagedBadge({ pluginName, userModified, className = '' }: ManagedBadgeProps) {
  const t = useManagedT();

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${
        userModified
          ? 'border border-amber-200 bg-amber-50 text-amber-700'
          : 'border border-blue-200 bg-blue-50 text-blue-700'
      } ${className}`}
      title={
        userModified
          ? t('plugin.managed.badge.modified.tooltip')
          : t('plugin.managed.badge.tooltip')
      }
    >
      <svg className="h-3 w-3 flex-shrink-0" viewBox="0 0 16 16" fill="currentColor">
        <path d="M4 4a4 4 0 0 1 8 0v2h.5A1.5 1.5 0 0 1 14 7.5v5a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 2 12.5v-5A1.5 1.5 0 0 1 3.5 6H4V4zm2 0v2h4V4a2 2 0 1 0-4 0z" />
      </svg>
      {pluginName}
      {userModified && (
        <span className="text-amber-500" title={t('plugin.managed.badge.modified')}>
          *
        </span>
      )}
    </span>
  );
}
