/**
 * 自动保存组件
 *
 * 提供页面内容的自动保存功能，包括保存状态指示和手动保存触发
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Save, Check, AlertCircle, Clock, Wifi, WifiOff } from 'lucide-react';
import { getVersionManager } from '~/studio/services/managers';
import type { PageSchema } from '~/studio/domain/schema/types';
import { VersionType } from '~/studio/domain/metadata/types';

/**
 * 保存状态
 */
export type SaveStatus = 'idle' | 'saving' | 'saved' | 'error' | 'offline';

/**
 * 自动保存属性
 */
export interface AutoSaveProps {
  pageId: string;
  schema: PageSchema;
  onSave?: (success: boolean) => void;
  autoSaveInterval?: number; // 自动保存间隔（毫秒）
  debounceDelay?: number; // 防抖延迟（毫秒）
  className?: string;
}

/**
 * 保存状态指示器
 */
const SaveStatusIndicator = ({ status, lastSaved }: { status: SaveStatus; lastSaved?: Date }) => {
  const getStatusConfig = () => {
    switch (status) {
      case 'saving':
        return {
          icon: <Save className="h-4 w-4 animate-pulse" />,
          text: '保存中...',
          color: 'text-blue-500',
        };
      case 'saved':
        return {
          icon: <Check className="h-4 w-4" />,
          text: '已保存',
          color: 'text-green-500',
        };
      case 'error':
        return {
          icon: <AlertCircle className="h-4 w-4" />,
          text: '保存失败',
          color: 'text-red-500',
        };
      case 'offline':
        return {
          icon: <WifiOff className="h-4 w-4" />,
          text: '离线模式',
          color: 'text-gray-500',
        };
      default:
        return {
          icon: <Clock className="h-4 w-4" />,
          text: '未保存',
          color: 'text-gray-500',
        };
    }
  };

  const { icon, text, color } = getStatusConfig();

  return (
    <div className={`flex items-center gap-2 ${color}`}>
      {icon}
      <span className="text-sm">{text}</span>
      {lastSaved && status === 'saved' && (
        <span className="text-xs text-gray-400">{formatLastSaved(lastSaved)}</span>
      )}
    </div>
  );
};

/**
 * 格式化最后保存时间
 */
function formatLastSaved(date: Date): string {
  const now = new Date();
  const diff = now.getTime() - date.getTime();

  if (diff < 60000) {
    // 1分钟内
    return '刚刚';
  } else if (diff < 3600000) {
    // 1小时内
    return `${Math.floor(diff / 60000)}分钟前`;
  } else if (diff < 86400000) {
    // 24小时内
    return `${Math.floor(diff / 3600000)}小时前`;
  } else {
    return date.toLocaleDateString();
  }
}

/**
 * 自动保存组件
 */
export function AutoSave({
  pageId,
  schema,
  onSave,
  autoSaveInterval = 30000, // 30秒
  debounceDelay = 2000, // 2秒
  className = '',
}: AutoSaveProps) {
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle');
  const [lastSaved, setLastSaved] = useState<Date | undefined>();
  const [isOnline, setIsOnline] = useState(navigator.onLine);
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  const versionManager = getVersionManager();
  const saveTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const autoSaveIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const lastSchemaRef = useRef<PageSchema>(schema);
  const isSavingRef = useRef(false);

  /**
   * 执行保存操作
   */
  const performSave = useCallback(
    async (force = false) => {
      if (isSavingRef.current && !force) return;
      if (!isOnline && !force) {
        setSaveStatus('offline');
        return;
      }

      try {
        isSavingRef.current = true;
        setSaveStatus('saving');

        // 创建新的快照版本
        await versionManager.createVersion(pageId, {
          schema,
          type: VersionType.SNAPSHOT,
          description: '自动保存',
        });

        setSaveStatus('saved');
        setLastSaved(new Date());
        setHasUnsavedChanges(false);
        lastSchemaRef.current = schema;

        onSave?.(true);
      } catch (error) {
        console.error('Auto save failed:', error);
        setSaveStatus('error');
        onSave?.(false);
      } finally {
        isSavingRef.current = false;
      }
    },
    [pageId, schema, versionManager, isOnline, onSave],
  );

  /**
   * 防抖保存
   */
  const debouncedSave = useCallback(() => {
    if (saveTimeoutRef.current) {
      clearTimeout(saveTimeoutRef.current);
    }

    saveTimeoutRef.current = setTimeout(() => {
      performSave();
    }, debounceDelay);
  }, [performSave, debounceDelay]);

  /**
   * 手动保存
   */
  const manualSave = useCallback(() => {
    if (saveTimeoutRef.current) {
      clearTimeout(saveTimeoutRef.current);
    }
    performSave(true);
  }, [performSave]);

  /**
   * 检查是否有变更
   */
  const checkForChanges = useCallback(() => {
    const hasChanges = JSON.stringify(schema) !== JSON.stringify(lastSchemaRef.current);
    setHasUnsavedChanges(hasChanges);

    if (hasChanges && isOnline) {
      debouncedSave();
    }
  }, [schema, debouncedSave, isOnline]);

  /**
   * 监听网络状态
   */
  useEffect(() => {
    const handleOnline = () => {
      setIsOnline(true);
      setSaveStatus('idle');

      // 网络恢复时，如果有未保存的变更，立即保存
      if (hasUnsavedChanges) {
        performSave();
      }
    };

    const handleOffline = () => {
      setIsOnline(false);
      setSaveStatus('offline');
    };

    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);

    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, [hasUnsavedChanges, performSave]);

  /**
   * 监听schema变更
   */
  useEffect(() => {
    checkForChanges();
  }, [checkForChanges]);

  /**
   * 设置自动保存定时器
   */
  useEffect(() => {
    if (autoSaveInterval > 0 && isOnline) {
      autoSaveIntervalRef.current = setInterval(() => {
        if (hasUnsavedChanges && !isSavingRef.current) {
          performSave();
        }
      }, autoSaveInterval);

      return () => {
        if (autoSaveIntervalRef.current) {
          clearInterval(autoSaveIntervalRef.current);
        }
      };
    }
  }, [autoSaveInterval, hasUnsavedChanges, isOnline, performSave]);

  /**
   * 页面卸载前保存
   */
  useEffect(() => {
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      if (hasUnsavedChanges) {
        event.preventDefault();
        event.returnValue = '您有未保存的更改，确定要离开吗？';

        // 尝试同步保存
        navigator.sendBeacon(
          '/api/pages/save',
          JSON.stringify({
            pageId,
            schema,
          }),
        );
      }
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [hasUnsavedChanges, pageId, schema]);

  /**
   * 清理定时器
   */
  useEffect(() => {
    return () => {
      if (saveTimeoutRef.current) {
        clearTimeout(saveTimeoutRef.current);
      }
      if (autoSaveIntervalRef.current) {
        clearInterval(autoSaveIntervalRef.current);
      }
    };
  }, []);

  return (
    <div className={`flex items-center gap-4 ${className}`}>
      {/* 网络状态指示器 */}
      <div className="flex items-center gap-1">
        {isOnline ? (
          <Wifi className="h-4 w-4 text-green-500" />
        ) : (
          <WifiOff className="h-4 w-4 text-red-500" />
        )}
      </div>

      {/* 保存状态指示器 */}
      <SaveStatusIndicator status={saveStatus} lastSaved={lastSaved} />

      {/* 手动保存按钮 */}
      <button
        onClick={manualSave}
        disabled={!hasUnsavedChanges || saveStatus === 'saving'}
        className={`rounded border px-3 py-1.5 text-sm transition-colors ${
          hasUnsavedChanges && saveStatus !== 'saving'
            ? 'border-blue-500 bg-blue-500 text-white hover:bg-blue-600'
            : 'cursor-not-allowed border-gray-200 bg-gray-100 text-gray-400'
        } `}
        title={hasUnsavedChanges ? '立即保存' : '没有未保存的更改'}
      >
        <Save className="mr-1 inline h-4 w-4" />
        保存
      </button>

      {/* 错误重试按钮 */}
      {saveStatus === 'error' && (
        <button
          onClick={manualSave}
          className="rounded border border-red-500 bg-red-500 px-3 py-1.5 text-sm text-white transition-colors hover:bg-red-600"
          title="重试保存"
        >
          重试
        </button>
      )}

      {/* 离线提示 */}
      {saveStatus === 'offline' && hasUnsavedChanges && (
        <div className="rounded border border-yellow-200 bg-yellow-100 px-3 py-1.5 text-sm text-yellow-800">
          离线模式，将在网络恢复后自动保存
        </div>
      )}
    </div>
  );
}

/**
 * 自动保存Hook
 */
export function useAutoSave(
  pageId: string,
  schema: PageSchema,
  options: {
    autoSaveInterval?: number;
    debounceDelay?: number;
    onSave?: (success: boolean) => void;
  } = {},
) {
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle');
  const [lastSaved, setLastSaved] = useState<Date | undefined>();
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  const versionManager = getVersionManager();
  const saveTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const lastSchemaRef = useRef<PageSchema>(schema);

  const performSave = useCallback(async () => {
    try {
      setSaveStatus('saving');

      await versionManager.createVersion(pageId, {
        schema,
        type: VersionType.SNAPSHOT,
        description: '自动保存',
      });

      setSaveStatus('saved');
      setLastSaved(new Date());
      setHasUnsavedChanges(false);
      lastSchemaRef.current = schema;

      options.onSave?.(true);
    } catch (error) {
      console.error('Auto save failed:', error);
      setSaveStatus('error');
      options.onSave?.(false);
    }
  }, [pageId, schema, versionManager, options]);

  const debouncedSave = useCallback(() => {
    if (saveTimeoutRef.current) {
      clearTimeout(saveTimeoutRef.current);
    }

    saveTimeoutRef.current = setTimeout(() => {
      performSave();
    }, options.debounceDelay || 2000);
  }, [performSave, options.debounceDelay]);

  const manualSave = useCallback(() => {
    if (saveTimeoutRef.current) {
      clearTimeout(saveTimeoutRef.current);
    }
    performSave();
  }, [performSave]);

  // 监听schema变更
  useEffect(() => {
    const hasChanges = JSON.stringify(schema) !== JSON.stringify(lastSchemaRef.current);
    setHasUnsavedChanges(hasChanges);

    if (hasChanges) {
      debouncedSave();
    }
  }, [schema, debouncedSave]);

  // 清理定时器
  useEffect(() => {
    return () => {
      if (saveTimeoutRef.current) {
        clearTimeout(saveTimeoutRef.current);
      }
    };
  }, []);

  return {
    saveStatus,
    lastSaved,
    hasUnsavedChanges,
    manualSave,
  };
}
