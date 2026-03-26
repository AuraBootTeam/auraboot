/**
 * Notification Preferences Page
 *
 * Channel x Category matrix with toggle switches.
 * SYSTEM + IN_APP cannot be disabled. Changes are saved immediately on toggle.
 */

import { useEffect } from 'react';
import { Link } from 'react-router';
import { BellIcon, Cog6ToothIcon, ArrowLeftIcon } from '@heroicons/react/24/outline';
import {
  useNotificationPreferences,
  CHANNELS,
  CATEGORIES,
  CHANNEL_LABELS,
  CATEGORY_LABELS,
} from '~/hooks/useNotificationPreferences';

type MetaArgs = Record<string, unknown>;

export function meta({}: MetaArgs) {
  return [{ title: '通知偏好设置' }, { name: 'description', content: '管理通知渠道和分类偏好' }];
}

export default function NotificationPreferencesPage() {
  const { loading, updating, fetchPreferences, updatePreference, isEnabled, isForced } =
    useNotificationPreferences();

  useEffect(() => {
    fetchPreferences();
  }, [fetchPreferences]);

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-5xl px-6 py-6">
          <div className="mb-2 flex items-center gap-3">
            <Link
              to="/notifications"
              className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700 dark:hover:text-gray-300"
              title="返回通知中心"
            >
              <ArrowLeftIcon className="h-5 w-5" />
            </Link>
            <div className="flex items-center gap-2">
              <Cog6ToothIcon className="h-7 w-7 text-indigo-600 dark:text-indigo-400" />
              <h1 className="text-2xl font-bold text-gray-900 dark:text-white">通知偏好设置</h1>
            </div>
          </div>
          <p className="ml-12 text-sm text-gray-500 dark:text-gray-400">
            选择您希望通过哪些渠道接收不同类型的通知。系统通知的站内信渠道始终保持开启。
          </p>
        </div>
      </div>

      {/* Main Content */}
      <div className="mx-auto max-w-5xl px-6 py-8">
        {loading ? (
          <LoadingSkeleton />
        ) : (
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className="dark:bg-gray-750 bg-gray-50">
                    <th className="min-w-[140px] px-6 py-4 text-left text-sm font-semibold text-gray-700 dark:text-gray-300">
                      <div className="flex items-center gap-2">
                        <BellIcon className="h-4 w-4" />
                        通知类型
                      </div>
                    </th>
                    {CHANNELS.map((channel) => (
                      <th
                        key={channel}
                        className="min-w-[100px] px-4 py-4 text-center text-sm font-semibold text-gray-700 dark:text-gray-300"
                      >
                        {CHANNEL_LABELS[channel] || channel}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
                  {CATEGORIES.map((category) => (
                    <tr
                      key={category}
                      className="dark:hover:bg-gray-750/50 transition-colors hover:bg-gray-50/50"
                    >
                      <td className="px-6 py-4">
                        <div className="flex items-center gap-2">
                          <CategoryBadge category={category} />
                          <span className="text-sm font-medium text-gray-900 dark:text-gray-100">
                            {CATEGORY_LABELS[category] || category}
                          </span>
                        </div>
                      </td>
                      {CHANNELS.map((channel) => {
                        const enabled = isEnabled(channel, category);
                        const forced = isForced(channel, category);
                        const isUpdating = updating === `${channel}:${category}`;

                        return (
                          <td key={channel} className="px-4 py-4 text-center">
                            <div className="flex justify-center">
                              <ToggleSwitch
                                enabled={enabled}
                                forced={forced}
                                loading={isUpdating}
                                onChange={(newEnabled) =>
                                  updatePreference(channel, category, newEnabled)
                                }
                              />
                            </div>
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Footer hint */}
            <div className="dark:bg-gray-750 border-t border-gray-100 bg-gray-50 px-6 py-3 dark:border-gray-700">
              <p className="text-xs text-gray-500 dark:text-gray-400">
                <span className="inline-flex items-center gap-1">
                  <span className="inline-block h-2 w-2 rounded-full bg-indigo-400"></span>
                  带锁标记的开关表示该渠道不可关闭
                </span>
              </p>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Toggle switch component.
 */
function ToggleSwitch({
  enabled,
  forced,
  loading,
  onChange,
}: {
  enabled: boolean;
  forced: boolean;
  loading: boolean;
  onChange: (enabled: boolean) => void;
}) {
  const disabled = forced || loading;

  return (
    <button
      type="button"
      role="switch"
      aria-checked={enabled}
      disabled={disabled}
      onClick={() => onChange(!enabled)}
      className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors duration-200 ease-in-out focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 focus:outline-none dark:focus:ring-offset-gray-800 ${
        enabled ? 'bg-indigo-600' : 'bg-gray-300 dark:bg-gray-600'
      } ${disabled ? 'cursor-not-allowed opacity-70' : 'cursor-pointer hover:shadow-md'} ${loading ? 'animate-pulse' : ''} `}
    >
      <span
        className={`inline-block h-4 w-4 transform rounded-full bg-white shadow-sm transition-transform duration-200 ease-in-out ${enabled ? 'translate-x-6' : 'translate-x-1'} `}
      />
      {forced && (
        <span className="absolute -top-1 -right-1 flex h-3.5 w-3.5 items-center justify-center rounded-full bg-indigo-500">
          <svg className="h-2 w-2 text-white" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z"
              clipRule="evenodd"
            />
          </svg>
        </span>
      )}
    </button>
  );
}

/**
 * Category badge with color coding.
 */
function CategoryBadge({ category }: { category: string }) {
  const colors: Record<string, string> = {
    BUSINESS: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    APPROVAL: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
    SYSTEM: 'bg-gray-100 text-gray-700 dark:bg-gray-700 dark:text-gray-300',
    ALERT: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
  };

  return (
    <span
      className={`inline-flex h-2 w-2 rounded-full ${
        colors[category]?.split(' ')[0] || 'bg-gray-300'
      }`}
    />
  );
}

/**
 * Loading skeleton for the preference matrix.
 */
function LoadingSkeleton() {
  return (
    <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
      <div className="p-6">
        {/* Header row skeleton */}
        <div className="mb-6 flex items-center gap-4">
          <div className="h-5 w-24 animate-pulse rounded bg-gray-200 dark:bg-gray-700" />
          {CHANNELS.map((ch) => (
            <div key={ch} className="h-5 w-16 animate-pulse rounded bg-gray-200 dark:bg-gray-700" />
          ))}
        </div>
        {/* Row skeletons */}
        {CATEGORIES.map((cat) => (
          <div key={cat} className="mb-4 flex items-center gap-4">
            <div className="h-5 w-20 animate-pulse rounded bg-gray-200 dark:bg-gray-700" />
            {CHANNELS.map((ch) => (
              <div
                key={ch}
                className="h-6 w-11 animate-pulse rounded-full bg-gray-200 dark:bg-gray-700"
              />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
