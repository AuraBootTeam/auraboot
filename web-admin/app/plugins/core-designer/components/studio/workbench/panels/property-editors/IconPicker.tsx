/**
 * IconPicker Component
 *
 * Icon picker with search and categories.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useMemo, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import type { BaseEditorProps, IconCategory, IconDefinition } from './types';

interface IconPickerProps extends BaseEditorProps<string> {
  /** Custom icons */
  icons?: IconDefinition[];
  /** Show categories */
  showCategories?: boolean;
  /** Icons per row */
  iconsPerRow?: number;
  /** Icon size */
  iconSize?: 'sm' | 'md' | 'lg';
}

/**
 * Default icon set (commonly used icons)
 */
const DEFAULT_ICONS: IconDefinition[] = [
  // Action icons
  {
    name: 'search',
    category: 'action',
    label: '搜索',
    icon: 'M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z',
    tags: ['查找', 'find'],
  },
  {
    name: 'plus',
    category: 'action',
    label: '添加',
    icon: 'M12 6v6m0 0v6m0-6h6m-6 0H6',
    tags: ['新增', 'add'],
  },
  { name: 'minus', category: 'action', label: '减少', icon: 'M20 12H4', tags: ['删除', 'remove'] },
  {
    name: 'edit',
    category: 'action',
    label: '编辑',
    icon: 'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z',
    tags: ['修改', 'modify'],
  },
  {
    name: 'delete',
    category: 'action',
    label: '删除',
    icon: 'M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16',
    tags: ['移除', 'trash'],
  },
  {
    name: 'save',
    category: 'action',
    label: '保存',
    icon: 'M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4',
    tags: ['存储', 'download'],
  },
  {
    name: 'copy',
    category: 'action',
    label: '复制',
    icon: 'M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z',
    tags: ['拷贝', 'duplicate'],
  },
  {
    name: 'refresh',
    category: 'action',
    label: '刷新',
    icon: 'M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15',
    tags: ['重载', 'reload'],
  },

  // Alert icons
  {
    name: 'info',
    category: 'alert',
    label: '信息',
    icon: 'M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z',
    tags: ['提示', 'tip'],
  },
  {
    name: 'warning',
    category: 'alert',
    label: '警告',
    icon: 'M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z',
    tags: ['注意', 'caution'],
  },
  {
    name: 'error',
    category: 'alert',
    label: '错误',
    icon: 'M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z',
    tags: ['失败', 'fail'],
  },
  {
    name: 'success',
    category: 'alert',
    label: '成功',
    icon: 'M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z',
    tags: ['完成', 'check'],
  },

  // Navigation icons
  {
    name: 'home',
    category: 'navigation',
    label: '首页',
    icon: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6',
    tags: ['主页', 'house'],
  },
  {
    name: 'menu',
    category: 'navigation',
    label: '菜单',
    icon: 'M4 6h16M4 12h16M4 18h16',
    tags: ['导航', 'hamburger'],
  },
  {
    name: 'arrow-left',
    category: 'navigation',
    label: '左箭头',
    icon: 'M10 19l-7-7m0 0l7-7m-7 7h18',
    tags: ['返回', 'back'],
  },
  {
    name: 'arrow-right',
    category: 'navigation',
    label: '右箭头',
    icon: 'M14 5l7 7m0 0l-7 7m7-7H3',
    tags: ['前进', 'forward'],
  },
  {
    name: 'arrow-up',
    category: 'navigation',
    label: '上箭头',
    icon: 'M5 10l7-7m0 0l7 7m-7-7v18',
    tags: ['向上', 'up'],
  },
  {
    name: 'arrow-down',
    category: 'navigation',
    label: '下箭头',
    icon: 'M19 14l-7 7m0 0l-7-7m7 7V3',
    tags: ['向下', 'down'],
  },
  {
    name: 'chevron-left',
    category: 'navigation',
    label: '左尖括号',
    icon: 'M15 19l-7-7 7-7',
    tags: ['上一页', 'prev'],
  },
  {
    name: 'chevron-right',
    category: 'navigation',
    label: '右尖括号',
    icon: 'M9 5l7 7-7 7',
    tags: ['下一页', 'next'],
  },

  // Content icons
  {
    name: 'document',
    category: 'content',
    label: '文档',
    icon: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
    tags: ['文件', 'file'],
  },
  {
    name: 'folder',
    category: 'content',
    label: '文件夹',
    icon: 'M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z',
    tags: ['目录', 'directory'],
  },
  {
    name: 'image',
    category: 'content',
    label: '图片',
    icon: 'M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z',
    tags: ['照片', 'photo'],
  },
  {
    name: 'video',
    category: 'content',
    label: '视频',
    icon: 'M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z',
    tags: ['影片', 'movie'],
  },
  {
    name: 'link',
    category: 'content',
    label: '链接',
    icon: 'M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1',
    tags: ['网址', 'url'],
  },

  // Communication icons
  {
    name: 'mail',
    category: 'communication',
    label: '邮件',
    icon: 'M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z',
    tags: ['邮箱', 'email'],
  },
  {
    name: 'phone',
    category: 'communication',
    label: '电话',
    icon: 'M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z',
    tags: ['手机', 'call'],
  },
  {
    name: 'chat',
    category: 'communication',
    label: '聊天',
    icon: 'M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z',
    tags: ['消息', 'message'],
  },
  {
    name: 'bell',
    category: 'communication',
    label: '通知',
    icon: 'M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9',
    tags: ['提醒', 'notification'],
  },

  // User icons
  {
    name: 'user',
    category: 'social',
    label: '用户',
    icon: 'M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z',
    tags: ['个人', 'person'],
  },
  {
    name: 'users',
    category: 'social',
    label: '用户组',
    icon: 'M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z',
    tags: ['团队', 'group'],
  },
  {
    name: 'settings',
    category: 'action',
    label: '设置',
    icon: 'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z',
    tags: ['配置', 'gear', 'cog'],
  },
  {
    name: 'lock',
    category: 'action',
    label: '锁定',
    icon: 'M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z',
    tags: ['密码', 'secure'],
  },
  {
    name: 'unlock',
    category: 'action',
    label: '解锁',
    icon: 'M8 11V7a4 4 0 118 0m-4 8v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2z',
    tags: ['开放', 'open'],
  },

  // Toggle icons
  {
    name: 'eye',
    category: 'toggle',
    label: '可见',
    icon: 'M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z',
    tags: ['显示', 'visible'],
  },
  {
    name: 'eye-off',
    category: 'toggle',
    label: '隐藏',
    icon: 'M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21',
    tags: ['不可见', 'hidden'],
  },
  {
    name: 'star',
    category: 'toggle',
    label: '收藏',
    icon: 'M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z',
    tags: ['星标', 'favorite'],
  },
  {
    name: 'heart',
    category: 'toggle',
    label: '喜欢',
    icon: 'M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z',
    tags: ['爱心', 'like'],
  },
];

/**
 * Category labels
 */
const CATEGORY_LABELS: Record<IconCategory, string> = {
  action: '操作',
  alert: '提示',
  av: '媒体',
  communication: '通信',
  content: '内容',
  device: '设备',
  editor: '编辑',
  file: '文件',
  hardware: '硬件',
  image: '图像',
  maps: '地图',
  navigation: '导航',
  notification: '通知',
  places: '地点',
  social: '社交',
  toggle: '切换',
};

/**
 * IconPicker Component
 */
export const IconPicker: React.FC<IconPickerProps> = ({
  value = '',
  onChange,
  disabled = false,
  label,
  error,
  className = '',
  icons = DEFAULT_ICONS,
  showCategories = true,
  iconsPerRow = 8,
  iconSize = 'md',
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<IconCategory | 'all'>('all');
  const pickerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  const [popoverPosition, setPopoverPosition] = useState({ top: 0, left: 0 });

  const updatePopoverPosition = useCallback(() => {
    const trigger = triggerRef.current;
    if (!trigger || typeof window === 'undefined') return;
    const rect = trigger.getBoundingClientRect();
    const width = 320;
    const margin = 8;
    const estimatedHeight = 360;
    const belowTop = rect.bottom + margin;
    const top =
      belowTop + estimatedHeight > window.innerHeight
        ? Math.max(margin, rect.top - estimatedHeight - margin)
        : belowTop;
    setPopoverPosition({
      top,
      left: Math.min(Math.max(margin, rect.left), Math.max(margin, window.innerWidth - width - margin)),
    });
  }, []);

  useEffect(() => {
    if (!isOpen) return;
    updatePopoverPosition();
    window.addEventListener('resize', updatePopoverPosition);
    window.addEventListener('scroll', updatePopoverPosition, true);
    return () => {
      window.removeEventListener('resize', updatePopoverPosition);
      window.removeEventListener('scroll', updatePopoverPosition, true);
    };
  }, [isOpen, updatePopoverPosition]);

  // Close on outside click
  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      const target = e.target as Node;
      if (
        pickerRef.current &&
        !pickerRef.current.contains(target) &&
        !popoverRef.current?.contains(target)
      ) {
        setIsOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isOpen]);

  // Get available categories
  const categories = useMemo(() => {
    const cats = new Set<IconCategory>();
    icons.forEach((icon) => cats.add(icon.category));
    return Array.from(cats);
  }, [icons]);

  // Filter icons
  const filteredIcons = useMemo(() => {
    let filtered = icons;

    // Category filter
    if (selectedCategory !== 'all') {
      filtered = filtered.filter((icon) => icon.category === selectedCategory);
    }

    // Search filter
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      filtered = filtered.filter(
        (icon) =>
          icon.name.toLowerCase().includes(query) ||
          icon.label.toLowerCase().includes(query) ||
          icon.tags?.some((tag) => tag.toLowerCase().includes(query)),
      );
    }

    return filtered;
  }, [icons, selectedCategory, searchQuery]);

  // Get selected icon
  const selectedIcon = useMemo(() => icons.find((icon) => icon.name === value), [icons, value]);

  // Handle icon select
  const handleIconSelect = useCallback(
    (iconName: string) => {
      if (disabled) return;
      onChange(iconName);
      setIsOpen(false);
    },
    [onChange, disabled],
  );

  // Icon size classes
  const sizeClasses = {
    sm: 'w-4 h-4',
    md: 'w-5 h-5',
    lg: 'w-6 h-6',
  };

  // Button size classes
  const buttonSizeClasses = {
    sm: 'w-7 h-7',
    md: 'w-8 h-8',
    lg: 'w-10 h-10',
  };

  return (
    <div className={`relative z-[1000] ${className}`} ref={pickerRef}>
      {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}

      {/* Trigger button */}
      <button
        ref={triggerRef}
        type="button"
        onClick={() => {
          if (disabled) return;
          updatePopoverPosition();
          setIsOpen(!isOpen);
        }}
        disabled={disabled}
        data-testid="icon-picker-trigger"
        className={`flex w-full items-center gap-2 rounded-md border px-3 py-2 ${disabled ? 'cursor-not-allowed bg-gray-100' : 'bg-white hover:border-gray-400'} ${error ? 'border-red-300' : 'border-gray-200'} `}
      >
        <div
          className={`${buttonSizeClasses[iconSize]} flex items-center justify-center rounded bg-gray-50`}
        >
          {selectedIcon ? (
            <svg
              className={`${sizeClasses[iconSize]} text-gray-600`}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d={selectedIcon.icon}
              />
            </svg>
          ) : (
            <svg
              className={`${sizeClasses[iconSize]} text-gray-300`}
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z"
              />
            </svg>
          )}
        </div>
        <span className="flex-1 truncate text-left text-sm text-gray-600">
          {selectedIcon ? selectedIcon.label : '选择图标'}
        </span>
        <svg
          className="h-4 w-4 text-gray-400"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {/* Popover */}
      {isOpen && typeof document !== 'undefined' && createPortal(
        <div
          ref={popoverRef}
          className="fixed z-[1000] w-80 rounded-lg border border-gray-200 bg-white shadow-lg"
          style={{ top: popoverPosition.top, left: popoverPosition.left }}
        >
          {/* Search */}
          <div className="border-b border-gray-100 p-2">
            <div className="relative">
              <svg
                className="absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2 text-gray-400"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                />
              </svg>
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="搜索图标..."
                className="w-full rounded border border-gray-200 py-1.5 pr-3 pl-8 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </div>
          </div>

          {/* Categories */}
          {showCategories && categories.length > 1 && (
            <div className="flex flex-wrap gap-1 border-b border-gray-100 p-2">
              <button
                type="button"
                onClick={() => setSelectedCategory('all')}
                className={`rounded px-2 py-0.5 text-xs ${
                  selectedCategory === 'all'
                    ? 'bg-blue-100 text-blue-700'
                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                }`}
              >
                全部
              </button>
              {categories.map((cat) => (
                <button
                  key={cat}
                  type="button"
                  onClick={() => setSelectedCategory(cat)}
                  className={`rounded px-2 py-0.5 text-xs ${
                    selectedCategory === cat
                      ? 'bg-blue-100 text-blue-700'
                      : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                  }`}
                >
                  {CATEGORY_LABELS[cat]}
                </button>
              ))}
            </div>
          )}

          {/* Icons grid */}
          <div className="max-h-60 overflow-auto p-2">
            {filteredIcons.length === 0 ? (
              <div className="py-4 text-center text-sm text-gray-400">没有找到匹配的图标</div>
            ) : (
              <div
                className="grid gap-1"
                style={{ gridTemplateColumns: `repeat(${iconsPerRow}, minmax(0, 1fr))` }}
              >
                {filteredIcons.map((icon) => (
	                  <button
	                    key={icon.name}
	                    type="button"
	                    onMouseDown={(event) => {
	                      event.preventDefault();
	                      event.stopPropagation();
	                      handleIconSelect(icon.name);
	                    }}
	                    onClick={(event) => {
	                      event.stopPropagation();
	                      handleIconSelect(icon.name);
	                    }}
	                    data-testid={`icon-picker-option-${icon.name}`}
	                    className={` ${buttonSizeClasses[iconSize]} flex items-center justify-center rounded transition-colors hover:bg-gray-100 ${value === icon.name ? 'bg-blue-100 ring-2 ring-blue-500' : ''} `}
	                    title={icon.label}
                  >
                    <svg
                      className={`${sizeClasses[iconSize]} text-gray-600`}
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d={icon.icon}
                      />
                    </svg>
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Clear button */}
          {value && (
            <div className="border-t border-gray-100 p-2">
              <button
                type="button"
                onClick={() => handleIconSelect('')}
                className="w-full text-xs text-gray-500 hover:text-red-500"
              >
                清除选择
              </button>
            </div>
          )}
        </div>,
        document.body,
      )}

      {error && <div className="mt-1 text-xs text-red-500">{error}</div>}
    </div>
  );
};

export default IconPicker;
