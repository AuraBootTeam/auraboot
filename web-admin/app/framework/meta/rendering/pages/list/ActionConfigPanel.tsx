/**
 * ActionConfigPanel — Modal overlay for configuring toolbar button visibility,
 * pinning, and order.
 *
 * Buttons above the divider are "pinned" (visible in the toolbar).
 * Buttons below the divider are in the "..." overflow menu.
 * Uses native HTML drag (same pattern as ColumnSettingsPanel).
 */

import React, { useState, useCallback, useRef, useEffect } from 'react';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { ToolbarActionConfig } from '~/framework/smart/types/savedView';
import { cn } from '~/utils/cn';
import { useI18n } from '~/contexts/I18nContext';

export interface ActionConfigPanelProps {
  buttons: ButtonConfig[];
  currentConfig?: ToolbarActionConfig[];
  resolveLabel: (button: ButtonConfig) => string;
  t?: (key: string, params?: Record<string, unknown>, fallback?: string) => string;
  /** Called on every change (toggle, drag reorder) — auto-saves immediately */
  onChange: (config: ToolbarActionConfig[]) => void;
  onClose: () => void;
  /** Built-ins unavailable for this page or actor must not reappear in configuration. */
  hiddenBuiltinCodes?: string[];
}

// Built-in actions that are always available (not from DSL)
const BUILTIN_ACTIONS = [
  { code: '_import', labelKey: 'action.import', fallback: '导入' },
  { code: '_export_excel', labelKey: 'data_tools.export_excel', fallback: '导出 Excel' },
  { code: '_export_csv', labelKey: 'data_tools.export_csv', fallback: '导出 CSV' },
  { code: '_print', labelKey: 'action.print', fallback: '打印' },
];

interface ActionItem {
  code: string;
  label: string;
  visible: boolean;
  pinned: boolean;
  isBuiltin?: boolean;
  mandatory?: boolean;
}

export const ActionConfigPanel: React.FC<ActionConfigPanelProps> = ({
  buttons,
  currentConfig,
  resolveLabel,
  t,
  onChange,
  onClose,
  hiddenBuiltinCodes,
}) => {
  const { t: contextT } = useI18n();
  const translateValue = t ?? contextT;
  const [items, setItems] = useState<ActionItem[]>([]);
  const initialized = useRef(false);
  const dragItem = useRef<number | null>(null);
  const dragOverItem = useRef<number | null>(null);
  const dragSection = useRef<'pinned' | 'overflow' | null>(null);
  const translate = useCallback(
    (key: string, fallback: string) => {
      const value = translateValue(key, undefined, fallback);
      return value && value !== key ? value : fallback;
    },
    [translateValue],
  );

  // Initialize from currentConfig or build defaults (DSL + built-in actions)
  useEffect(() => {
    const configMap = new Map((currentConfig || []).map((c) => [c.code, c]));

    // DSL buttons
    const dslItems: ActionItem[] = buttons.map((btn, idx) => {
      const cfg = configMap.get(btn.code);
      const isPrimary = btn.primary || btn.variant === 'primary';
      return {
        code: btn.code,
        label: resolveLabel(btn),
        visible: btn.mandatory ? true : (cfg?.visible ?? true),
        pinned: cfg?.pinned ?? (isPrimary || idx < 2),
        isBuiltin: false,
        mandatory: btn.mandatory === true,
      };
    });

    // Built-in items (always start unpinned unless configured)
    const builtinItems: ActionItem[] = BUILTIN_ACTIONS.filter(
      (ba) => !hiddenBuiltinCodes?.includes(ba.code),
    ).map((ba, _i) => {
      const cfg = configMap.get(ba.code);
      return {
        code: ba.code,
        label: translateValue(ba.labelKey, undefined, ba.fallback),
        visible: cfg?.visible ?? true,
        pinned: cfg?.pinned ?? false,
        isBuiltin: true,
      };
    });

    const result = [...dslItems, ...builtinItems];

    // Sort: pinned first (in config order), then overflow
    if (currentConfig && currentConfig.length > 0) {
      result.sort((a, b) => {
        const aIdx = currentConfig.findIndex((c) => c.code === a.code);
        const bIdx = currentConfig.findIndex((c) => c.code === b.code);
        const aOrder = aIdx >= 0 ? aIdx : 999;
        const bOrder = bIdx >= 0 ? bIdx : 999;
        return aOrder - bOrder;
      });
    }

    setItems(result);
    // Mark as initialized after first render so we don't auto-save the initial load
    initialized.current = true;
  }, [buttons, currentConfig, hiddenBuiltinCodes, resolveLabel, translateValue]);

  // Auto-save on every change (skip initial mount)
  const emitChange = useCallback(
    (nextItems: ActionItem[]) => {
      const pinned = nextItems.filter((i) => i.pinned);
      const overflow = nextItems.filter((i) => !i.pinned);
      const all = [...pinned, ...overflow];
      const config: ToolbarActionConfig[] = all.map((item, idx) => ({
        code: item.code,
        visible: item.visible,
        pinned: item.pinned,
        order: idx,
      }));
      onChange(config);
    },
    [onChange],
  );

  const pinnedItems = items.filter((i) => i.pinned);
  const overflowItems = items.filter((i) => !i.pinned);

  const toggleVisible = useCallback(
    (code: string) => {
      setItems((prev) => {
        const next = prev.map((item) =>
          item.code === code && !item.mandatory ? { ...item, visible: !item.visible } : item,
        );
        emitChange(next);
        return next;
      });
    },
    [emitChange],
  );

  const togglePinned = useCallback(
    (code: string) => {
      setItems((prev) => {
        const next = prev.map((item) =>
          item.code === code ? { ...item, pinned: !item.pinned } : item,
        );
        emitChange(next);
        return next;
      });
    },
    [emitChange],
  );

  // Drag within pinned or overflow sections
  const handleDragStart = useCallback((index: number, section: 'pinned' | 'overflow') => {
    dragItem.current = index;
    dragSection.current = section;
  }, []);

  const handleDragEnter = useCallback((index: number) => {
    dragOverItem.current = index;
  }, []);

  const handleDragEnd = useCallback(() => {
    if (dragItem.current === null || dragOverItem.current === null || !dragSection.current) {
      dragItem.current = null;
      dragOverItem.current = null;
      dragSection.current = null;
      return;
    }

    const from = dragItem.current;
    const to = dragOverItem.current;
    const section = dragSection.current;

    if (from !== to) {
      setItems((prev) => {
        const sectionItems =
          section === 'pinned' ? prev.filter((i) => i.pinned) : prev.filter((i) => !i.pinned);
        const otherItems =
          section === 'pinned' ? prev.filter((i) => !i.pinned) : prev.filter((i) => i.pinned);

        const reordered = [...sectionItems];
        const [removed] = reordered.splice(from, 1);
        reordered.splice(to, 0, removed);

        const next =
          section === 'pinned' ? [...reordered, ...otherItems] : [...otherItems, ...reordered];
        emitChange(next);
        return next;
      });
    }

    dragItem.current = null;
    dragOverItem.current = null;
    dragSection.current = null;
  }, [emitChange]);

  const renderItem = (item: ActionItem, index: number, section: 'pinned' | 'overflow') => (
    <div
      key={item.code}
      data-testid={`action-config-row-${item.code}`}
      draggable
      onDragStart={() => handleDragStart(index, section)}
      onDragEnter={() => handleDragEnter(index)}
      onDragEnd={handleDragEnd}
      onDragOver={(e) => e.preventDefault()}
      className={cn(
        'flex items-center gap-2 rounded px-3 py-2 text-sm transition-colors',
        'cursor-grab active:cursor-grabbing',
        'hover:bg-hover',
        !item.visible && 'opacity-50',
      )}
    >
      {/* Drag handle */}
      <svg className="text-text-3 h-4 w-4 shrink-0" fill="currentColor" viewBox="0 0 20 20">
        <path d="M7 2a2 2 0 10.001 4.001A2 2 0 007 2zm0 6a2 2 0 10.001 4.001A2 2 0 007 8zm0 6a2 2 0 10.001 4.001A2 2 0 007 14zm6-8a2 2 0 10-.001-4.001A2 2 0 0013 6zm0 2a2 2 0 10.001 4.001A2 2 0 0013 8zm0 6a2 2 0 10.001 4.001A2 2 0 0013 14z" />
      </svg>

      {/* Label */}
      <span className="text-text-2 flex-1 truncate">
        {item.label}
        {item.isBuiltin && (
          <span className="text-text-3 ml-1.5 text-[10px] font-normal">
            ({translateValue('action_config.builtin', undefined, '内置')})
          </span>
        )}
        {item.mandatory && (
          <span className="bg-accent-weak text-accent ml-1.5 rounded px-1.5 py-0.5 text-[10px] font-medium">
            {translate('common.saved_view_mandatory', 'Required')}
          </span>
        )}
      </span>

      {/* Pin/unpin toggle — explicit move between sections */}
      <button
        type="button"
        data-testid={`action-config-pin-${item.code}`}
        onClick={() => togglePinned(item.code)}
        className="text-text-3 hover:bg-hover hover:text-accent rounded p-1"
        title={
          item.pinned
            ? translateValue('action_config.move_to_menu', undefined, '移到更多菜单')
            : translateValue('action_config.pin_to_toolbar', undefined, '固定到工具栏')
        }
      >
        {item.pinned ? (
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          >
            <path d="M12 5v14M5 12l7 7 7-7" />
          </svg>
        ) : (
          <svg
            width="16"
            height="16"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          >
            <path d="M12 19V5M5 12l7-7 7 7" />
          </svg>
        )}
      </button>

      {/* Visibility toggle */}
      <button
        type="button"
        data-testid={`action-config-visible-${item.code}`}
        onClick={() => toggleVisible(item.code)}
        disabled={item.mandatory}
        className={cn(
          'rounded p-1 transition-colors',
          item.visible
            ? 'text-status-green hover:bg-status-green-bg'
            : 'text-text-3 hover:bg-hover',
        )}
        title={
          item.mandatory
            ? translate(
                'common.saved_view_mandatory_action_reason',
                'Required actions cannot be hidden in a personal view',
              )
            : item.visible
              ? translateValue('action_config.hide_button', undefined, '隐藏按钮')
              : translateValue('action_config.show_button', undefined, '显示按钮')
        }
      >
        <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          {item.visible ? (
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M15 12a3 3 0 11-6 0 3 3 0 016 0z M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
            />
          ) : (
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.878 9.878L3 3m6.878 6.878L21 21"
            />
          )}
        </svg>
      </button>
    </div>
  );

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      data-testid="action-config-panel"
    >
      <div className="rounded-card bg-panel w-full max-w-md shadow-xl">
        {/* Header */}
        <div className="border-border flex items-center justify-between border-b px-5 py-4">
          <h3 className="text-text text-base font-semibold">
            {translateValue('action_config.title', undefined, '配置按钮')}
          </h3>
          <button
            type="button"
            data-testid="action-config-close"
            aria-label={translateValue('action.close', undefined, '关闭')}
            onClick={onClose}
            className="rounded-control text-text-3 hover:bg-hover hover:text-text-2 p-1"
          >
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Body */}
        <div className="max-h-[60vh] overflow-y-auto px-5 py-4">
          {/* Toolbar (pinned) section */}
          <div className="text-text-2 mb-2 text-xs font-medium tracking-wide uppercase">
            {translateValue('action_config.toolbar', undefined, '工具栏')}
          </div>
          <div className="mb-3 space-y-0.5">
            {pinnedItems.length === 0 ? (
              <div className="text-text-3 px-3 py-2 text-sm italic">
                {translateValue('action_config.no_pinned', undefined, '没有固定按钮')}
              </div>
            ) : (
              pinnedItems.map((item, idx) => renderItem(item, idx, 'pinned'))
            )}
          </div>

          {/* Divider */}
          <div className="my-3 flex items-center gap-2">
            <div className="border-border flex-1 border-t" />
            <span className="text-text-3 text-xs">
              {translateValue('action_config.divider', undefined, '此线以下显示在 ··· 菜单')}
            </span>
            <div className="border-border flex-1 border-t" />
          </div>

          {/* Overflow section */}
          <div className="text-text-2 mb-2 text-xs font-medium tracking-wide uppercase">
            {translateValue('action_config.more_menu', undefined, '更多菜单')}
          </div>
          <div className="space-y-0.5">
            {overflowItems.length === 0 ? (
              <div className="text-text-3 px-3 py-2 text-sm italic">
                {translateValue('action_config.no_overflow', undefined, '没有更多按钮')}
              </div>
            ) : (
              overflowItems.map((item, idx) => renderItem(item, idx, 'overflow'))
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default ActionConfigPanel;
