/**
 * Login Channel Management
 *
 * Allows tenant administrators to enable/disable login channels and
 * reorder them using drag-and-drop or up/down arrow buttons.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Bars3Icon,
  ChevronUpIcon,
  ChevronDownIcon,
  EnvelopeIcon,
  DevicePhoneMobileIcon,
  KeyIcon,
  GlobeAltIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import { get, put } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { workspacePageClassName } from '~/shared/layout/WorkspacePageLayout';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type ChannelCode = 'email_password' | 'sms' | 'email_code' | 'wechat' | 'google' | 'apple';

interface LoginChannel {
  id?: string;
  tenantId?: string;
  channel: ChannelCode;
  enabled: boolean;
  sortOrder: number;
}

interface ChannelDisplay {
  code: ChannelCode;
  label: string;
  description: string;
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  iconColor: string;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const CHANNEL_DISPLAY: Record<ChannelCode, Omit<ChannelDisplay, 'code'>> = {
  email_password: {
    label: '邮箱密码登录',
    description: '使用邮箱和密码进行登录',
    icon: EnvelopeIcon,
    iconColor: 'text-blue-600 bg-blue-50 dark:bg-blue-900/30 dark:text-blue-400',
  },
  sms: {
    label: '短信验证码登录',
    description: '通过手机短信验证码登录',
    icon: DevicePhoneMobileIcon,
    iconColor: 'text-green-600 bg-green-50 dark:bg-green-900/30 dark:text-green-400',
  },
  email_code: {
    label: '邮箱验证码登录',
    description: '通过邮箱发送验证码登录',
    icon: EnvelopeIcon,
    iconColor: 'text-purple-600 bg-purple-50 dark:bg-purple-900/30 dark:text-purple-400',
  },
  wechat: {
    label: '微信扫码登录',
    description: '使用微信扫描二维码登录',
    icon: WechatIcon,
    iconColor: 'text-emerald-600 bg-emerald-50 dark:bg-emerald-900/30 dark:text-emerald-400',
  },
  google: {
    label: 'Google 登录',
    description: '使用 Google 账号登录',
    icon: GlobeAltIcon,
    iconColor: 'text-red-600 bg-red-50 dark:bg-red-900/30 dark:text-red-400',
  },
  apple: {
    label: 'Apple 登录',
    description: '使用 Apple ID 登录',
    icon: KeyIcon,
    iconColor: 'text-gray-800 bg-gray-100 dark:bg-gray-700 dark:text-gray-300',
  },
};

const DEFAULT_CHANNELS: ChannelCode[] = [
  'email_password',
  'sms',
  'email_code',
  'wechat',
  'google',
  'apple',
];

// ---------------------------------------------------------------------------
// Page meta
// ---------------------------------------------------------------------------

type MetaArgs = Record<string, unknown>;

export function meta({}: MetaArgs) {
  return [
    { title: '登录渠道管理' },
    { name: 'description', content: '配置租户可用的登录方式及其优先级' },
  ];
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export default function LoginChannelsPage() {
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [channels, setChannels] = useState<LoginChannel[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [hasChanges, setHasChanges] = useState(false);

  // Drag state
  const dragItemRef = useRef<number | null>(null);
  const dragOverItemRef = useRef<number | null>(null);

  // -------------------------------------------------------------------------
  // Data loading
  // -------------------------------------------------------------------------

  const loadChannels = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<LoginChannel[]>('/api/admin/login-channels');
      if (ResultHelper.isSuccess(result) && result.data) {
        // Sort by sortOrder
        const sorted = [...result.data].sort((a, b) => a.sortOrder - b.sortOrder);
        setChannels(sorted);
      } else {
        // If no data from server, generate defaults
        setChannels(
          DEFAULT_CHANNELS.map((ch, i) => ({
            channel: ch,
            enabled: ch === 'email_password',
            sortOrder: i,
          })),
        );
      }
    } catch (e: any) {
      showErrorToast(e.message || '加载登录渠道配置失败');
      setChannels(
        DEFAULT_CHANNELS.map((ch, i) => ({
          channel: ch,
          enabled: ch === 'email_password',
          sortOrder: i,
        })),
      );
    } finally {
      setLoading(false);
    }
  }, [showErrorToast]);

  useEffect(() => {
    loadChannels();
  }, [loadChannels]);

  // -------------------------------------------------------------------------
  // Handlers
  // -------------------------------------------------------------------------

  const handleToggle = (index: number) => {
    setChannels((prev) => {
      const next = [...prev];
      next[index] = { ...next[index], enabled: !next[index].enabled };
      return next;
    });
    setHasChanges(true);
  };

  const handleMoveUp = (index: number) => {
    if (index <= 0) return;
    setChannels((prev) => {
      const next = [...prev];
      [next[index - 1], next[index]] = [next[index], next[index - 1]];
      return next.map((ch, i) => ({ ...ch, sortOrder: i }));
    });
    setHasChanges(true);
  };

  const handleMoveDown = (index: number) => {
    if (index >= channels.length - 1) return;
    setChannels((prev) => {
      const next = [...prev];
      [next[index], next[index + 1]] = [next[index + 1], next[index]];
      return next.map((ch, i) => ({ ...ch, sortOrder: i }));
    });
    setHasChanges(true);
  };

  // Drag-and-drop handlers
  const handleDragStart = (index: number) => {
    dragItemRef.current = index;
  };

  const handleDragEnter = (index: number) => {
    dragOverItemRef.current = index;
  };

  const handleDragEnd = () => {
    if (
      dragItemRef.current === null ||
      dragOverItemRef.current === null ||
      dragItemRef.current === dragOverItemRef.current
    ) {
      dragItemRef.current = null;
      dragOverItemRef.current = null;
      return;
    }

    setChannels((prev) => {
      const next = [...prev];
      const dragItem = next.splice(dragItemRef.current!, 1)[0];
      next.splice(dragOverItemRef.current!, 0, dragItem);
      return next.map((ch, i) => ({ ...ch, sortOrder: i }));
    });

    dragItemRef.current = null;
    dragOverItemRef.current = null;
    setHasChanges(true);
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      const payload = channels.map((ch, i) => ({
        channel: ch.channel,
        enabled: ch.enabled,
        sortOrder: i,
      }));
      const result = await put('/api/admin/login-channels', payload);
      if (ResultHelper.isSuccess(result)) {
        showSuccessToast('登录渠道配置已保存');
        setHasChanges(false);
      } else {
        showErrorToast(result.desc || '保存失败');
      }
    } catch (e: any) {
      showErrorToast(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  // -------------------------------------------------------------------------
  // Render
  // -------------------------------------------------------------------------

  const enabledCount = channels.filter((c) => c.enabled).length;

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className={workspacePageClassName('header')}>
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-xl font-bold text-gray-900 dark:text-white">登录渠道管理</h1>
              <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">
                配置租户可用的登录方式、启用状态和显示顺序
              </p>
            </div>
            <div className="flex items-center gap-3">
              <span className="text-xs text-gray-400 dark:text-gray-500">
                已启用 {enabledCount}/{channels.length} 个渠道
              </span>
              <button
                onClick={handleSave}
                disabled={!hasChanges || saving}
                className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors ${
                  hasChanges
                    ? 'bg-blue-600 text-white hover:bg-blue-700'
                    : 'cursor-not-allowed bg-gray-100 text-gray-400 dark:bg-gray-700 dark:text-gray-500'
                }`}
                data-testid="login-channels-save-btn"
              >
                {saving ? '保存中...' : '保存'}
              </button>
            </div>
          </div>
        </div>
      </div>

      <div className={workspacePageClassName('content')}>
        {loading ? (
          <div className="flex justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
          </div>
        ) : (
          <>
            {/* Channel list */}
            <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
              <div className="divide-y divide-gray-100 dark:divide-gray-700">
                {channels.map((channel, index) => {
                  const display = CHANNEL_DISPLAY[channel.channel];
                  if (!display) return null;
                  const IconComponent = display.icon;

                  return (
                    <div
                      key={channel.channel}
                      draggable
                      onDragStart={() => handleDragStart(index)}
                      onDragEnter={() => handleDragEnter(index)}
                      onDragEnd={handleDragEnd}
                      onDragOver={(e) => e.preventDefault()}
                      className={`flex items-center gap-4 px-4 py-3.5 transition-colors select-none ${
                        channel.enabled
                          ? 'bg-white dark:bg-gray-800'
                          : 'bg-gray-50/50 dark:bg-gray-800/50'
                      } dark:hover:bg-gray-750 cursor-grab hover:bg-gray-50 active:cursor-grabbing`}
                      data-testid={`login-channel-${channel.channel.toLowerCase()}`}
                    >
                      {/* Drag handle */}
                      <div className="shrink-0 text-gray-300 dark:text-gray-600">
                        <Bars3Icon className="h-5 w-5" />
                      </div>

                      {/* Icon */}
                      <div
                        className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${display.iconColor}`}
                      >
                        <IconComponent className="h-5 w-5" />
                      </div>

                      {/* Label + description */}
                      <div className="min-w-0 flex-1">
                        <div
                          className={`text-sm font-medium ${
                            channel.enabled
                              ? 'text-gray-900 dark:text-white'
                              : 'text-gray-400 dark:text-gray-500'
                          }`}
                        >
                          {display.label}
                        </div>
                        <div className="mt-0.5 text-xs text-gray-400 dark:text-gray-500">
                          {display.description}
                        </div>
                      </div>

                      {/* Reorder arrows */}
                      <div className="flex shrink-0 flex-col gap-0.5">
                        <button
                          onClick={() => handleMoveUp(index)}
                          disabled={index === 0}
                          className="p-0.5 text-gray-300 transition-colors hover:text-gray-500 disabled:cursor-not-allowed disabled:opacity-30 dark:text-gray-600 dark:hover:text-gray-400"
                          title="上移"
                        >
                          <ChevronUpIcon className="h-3.5 w-3.5" />
                        </button>
                        <button
                          onClick={() => handleMoveDown(index)}
                          disabled={index === channels.length - 1}
                          className="p-0.5 text-gray-300 transition-colors hover:text-gray-500 disabled:cursor-not-allowed disabled:opacity-30 dark:text-gray-600 dark:hover:text-gray-400"
                          title="下移"
                        >
                          <ChevronDownIcon className="h-3.5 w-3.5" />
                        </button>
                      </div>

                      {/* Toggle */}
                      <button
                        onClick={() => handleToggle(index)}
                        className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 focus:outline-none ${
                          channel.enabled ? 'bg-blue-600' : 'bg-gray-300 dark:bg-gray-600'
                        }`}
                        role="switch"
                        aria-checked={channel.enabled}
                        data-testid={`login-channel-toggle-${channel.channel.toLowerCase()}`}
                      >
                        <span
                          className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform ${
                            channel.enabled ? 'translate-x-5.5' : 'translate-x-0.5'
                          }`}
                          style={{
                            transform: channel.enabled ? 'translateX(22px)' : 'translateX(2px)',
                          }}
                        />
                      </button>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Info box */}
            <div className="mt-4 rounded-lg border border-blue-200 bg-blue-50 p-4 dark:border-blue-800 dark:bg-blue-900/20">
              <h3 className="mb-1.5 text-sm font-medium text-blue-800 dark:text-blue-300">
                使用说明
              </h3>
              <ul className="space-y-1 text-xs text-blue-700 dark:text-blue-400">
                <li>-- 拖拽或使用箭头按钮调整登录方式的显示顺序</li>
                <li>-- 启用的登录方式将在登录页面按顺序展示给用户</li>
                <li>
                  -- OAuth 登录（微信、Google、Apple）需要先在「云服务配置」中完成对应服务商配置
                </li>
                <li>-- 短信登录需要先配置短信服务商（腾讯云 / 阿里云 / AWS SNS）</li>
                <li>-- 建议至少保留一个登录方式处于启用状态</li>
              </ul>
            </div>

            {/* Unsaved changes indicator */}
            {hasChanges && (
              <div className="fixed bottom-6 left-1/2 z-40 flex -translate-x-1/2 items-center gap-3 rounded-full bg-gray-900 px-5 py-2.5 text-sm text-white shadow-lg dark:bg-gray-700">
                <span className="h-2 w-2 animate-pulse rounded-full bg-amber-400" />
                <span>有未保存的更改</span>
                <button
                  onClick={handleSave}
                  disabled={saving}
                  className="ml-1 rounded-full bg-blue-600 px-3 py-1 text-xs font-medium transition-colors hover:bg-blue-700"
                >
                  {saving ? '保存中...' : '保存'}
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Custom icon for WeChat (not available in heroicons)
// ---------------------------------------------------------------------------

function WechatIcon(props: React.SVGProps<SVGSVGElement>) {
  return (
    <svg
      {...props}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {/* Simplified WeChat icon using two overlapping speech bubbles */}
      <path d="M8.5 11.5c-3.04 0-5.5-2.12-5.5-4.75S5.46 2 8.5 2s5.5 2.12 5.5 4.75c0 1.28-.66 2.44-1.72 3.28l.47 1.72-2.05-1.13c-.69.2-1.42.38-2.2.38-.17 0-.33 0-.5-.02" />
      <path d="M15.5 22c2.76 0 5.5-1.92 5.5-4.5S18.26 13 15.5 13 10 14.92 10 17.5c0 1.16.58 2.22 1.52 3.02l-.42 1.48 1.78-.98c.84.22 1.71.48 2.62.48" />
      {/* Dots for the chat bubbles */}
      <circle cx="7" cy="6.75" r=".5" fill="currentColor" stroke="none" />
      <circle cx="10" cy="6.75" r=".5" fill="currentColor" stroke="none" />
      <circle cx="14" cy="17.5" r=".5" fill="currentColor" stroke="none" />
      <circle cx="17" cy="17.5" r=".5" fill="currentColor" stroke="none" />
    </svg>
  );
}
