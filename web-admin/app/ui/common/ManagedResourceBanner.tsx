import React from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { ResourceOwnerInfo } from '~/hooks/useResourceOwner';

/**
 * Fallback translations for managed resource UI.
 */
const FALLBACKS: Record<string, Record<string, string>> = {
  'plugin.managed.banner.title': {
    'zh-CN': '插件管理的资源',
    'en-US': 'Plugin-Managed Resource',
  },
  'plugin.managed.banner.description': {
    'zh-CN':
      '此资源由插件「{pluginName}」管理。重新导入该插件时（使用 OVERWRITE 策略），您的修改可能被覆盖。',
    'en-US':
      'This resource is managed by plugin "{pluginName}". Your changes may be overwritten when the plugin is re-imported with OVERWRITE strategy.',
  },
  'plugin.managed.banner.already_modified': {
    'zh-CN': '此资源已被手动修改过。使用 OVERWRITE_SAFE 策略导入时将保留您的修改。',
    'en-US':
      'This resource has been manually modified. Using OVERWRITE_SAFE import strategy will preserve your changes.',
  },
  'plugin.managed.confirm.title': {
    'zh-CN': '修改插件管理的资源',
    'en-US': 'Modify Plugin-Managed Resource',
  },
  'plugin.managed.confirm.message': {
    'zh-CN':
      '您正在修改由插件「{pluginName}」管理的资源。下次重新导入该插件时，您的修改可能被覆盖。确定要继续吗？',
    'en-US':
      'You are modifying a resource managed by plugin "{pluginName}". Your changes may be overwritten when the plugin is re-imported. Continue?',
  },
};

function useManagedT() {
  const { t, locale } = useI18n();
  return (key: string, params?: Record<string, string>) => {
    const result = t(key, params);
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

interface ManagedResourceBannerProps {
  owner: ResourceOwnerInfo;
  className?: string;
}

/**
 * Warning banner shown at the top of edit forms for plugin-managed resources.
 * Informs the user that their changes may be overwritten on next plugin import.
 */
export function ManagedResourceBanner({ owner, className = '' }: ManagedResourceBannerProps) {
  const t = useManagedT();

  if (!owner.managed) return null;

  return (
    <div
      className={`flex items-start gap-3 rounded-lg border p-3 ${
        owner.userModified ? 'border-amber-200 bg-amber-50' : 'border-blue-200 bg-blue-50'
      } ${className}`}
      role="alert"
    >
      <svg
        className={`mt-0.5 h-5 w-5 flex-shrink-0 ${
          owner.userModified ? 'text-amber-500' : 'text-blue-500'
        }`}
        viewBox="0 0 20 20"
        fill="currentColor"
      >
        <path
          fillRule="evenodd"
          d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 5a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 5zm0 9a1 1 0 100-2 1 1 0 000 2z"
          clipRule="evenodd"
        />
      </svg>
      <div className="min-w-0 flex-1">
        <p
          className={`text-sm font-medium ${
            owner.userModified ? 'text-amber-800' : 'text-blue-800'
          }`}
        >
          {t('plugin.managed.banner.title')}
        </p>
        <p className={`mt-1 text-sm ${owner.userModified ? 'text-amber-700' : 'text-blue-700'}`}>
          {t('plugin.managed.banner.description', {
            pluginName: owner.pluginName || owner.pluginId || 'Unknown',
          })}
        </p>
        {owner.userModified && (
          <p className="mt-1 text-xs text-amber-600">
            {t('plugin.managed.banner.already_modified')}
          </p>
        )}
      </div>
    </div>
  );
}

/**
 * Get confirmation dialog content for saving changes to a managed resource.
 */
export function getManagedSaveConfirmation(
  t: (key: string, params?: Record<string, string>) => string,
  locale: string,
  owner: ResourceOwnerInfo,
): { title: string; message: string } {
  const resolve = (key: string, params?: Record<string, string>) => {
    const result = t(key, params);
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

  return {
    title: resolve('plugin.managed.confirm.title'),
    message: resolve('plugin.managed.confirm.message', {
      pluginName: owner.pluginName || owner.pluginId || 'Unknown',
    }),
  };
}
