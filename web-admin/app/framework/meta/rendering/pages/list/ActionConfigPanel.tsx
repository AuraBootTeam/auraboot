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

export interface ActionConfigPanelProps {
  buttons: ButtonConfig[];
  currentConfig?: ToolbarActionConfig[];
  resolveLabel: (button: ButtonConfig) => string;
  /** Called on every change (toggle, drag reorder) — auto-saves immediately */
  onChange: (config: ToolbarActionConfig[]) => void;
  onClose: () => void;
}

// Built-in actions that are always available (not from DSL)
const BUILTIN_ACTIONS = [
  { code: '_import', label: 'Import' },
  { code: '_export_excel', label: 'Export Excel' },
  { code: '_export_csv', label: 'Export CSV' },
  { code: '_print', label: 'Print' },
];

interface ActionItem {
  code: string;
  label: string;
  visible: boolean;
  pinned: boolean;
  isBuiltin?: boolean;
}

export const ActionConfigPanel: React.FC<ActionConfigPanelProps> = ({
  buttons,
  currentConfig,
  resolveLabel,
  onChange,
  onClose,
}) => {
  const [items, setItems] = useState<ActionItem[]>([]);
  const initialized = useRef(false);
  const dragItem = useRef<number | null>(null);
  const dragOverItem = useRef<number | null>(null);
  const dragSection = useRef<'pinned' | 'overflow' | null>(null);

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
        visible: cfg?.visible ?? true,
        pinned: cfg?.pinned ?? (isPrimary || idx < 2),
        isBuiltin: false,
      };
    });

    // Built-in items (always start unpinned unless configured)
    const builtinItems: ActionItem[] = BUILTIN_ACTIONS.map((ba, i) => {
      const cfg = configMap.get(ba.code);
      return {
        code: ba.code,
        label: ba.label,
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
  }, [buttons, currentConfig, resolveLabel]);

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

  const toggleVisible = useCallback((code: string) => {
    setItems((prev) => {
      const next = prev.map((item) => (item.code === code ? { ...item, visible: !item.visible } : item));
      emitChange(next);
      return next;
    });
  }, [emitChange]);

  const togglePinned = useCallback((code: string) => {
    setItems((prev) => {
      const next = prev.map((item) => (item.code === code ? { ...item, pinned: !item.pinned } : item));
      emitChange(next);
      return next;
    });
  }, [emitChange]);

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

        const next = section === 'pinned' ? [...reordered, ...otherItems] : [...otherItems, ...reordered];
        emitChange(next);
        return next;
      });
    }

    dragItem.current = null;
    dragOverItem.current = null;
    dragSection.current = null;
  }, [emitChange]);

  const renderItem = (
    item: ActionItem,
    index: number,
    section: 'pinned' | 'overflow',
  ) => (
    <div
      key={item.code}
      draggable
      onDragStart={() => handleDragStart(index, section)}
      onDragEnter={() => handleDragEnter(index)}
      onDragEnd={handleDragEnd}
      onDragOver={(e) => e.preventDefault()}
      className={cn(
        'flex items-center gap-2 rounded px-3 py-2 text-sm transition-colors',
        'cursor-grab active:cursor-grabbing',
        'hover:bg-gray-50',
        !item.visible && 'opacity-50',
      )}
    >
      {/* Drag handle */}
      <svg className="h-4 w-4 shrink-0 text-gray-400" fill="currentColor" viewBox="0 0 20 20">
        <path d="M7 2a2 2 0 10.001 4.001A2 2 0 007 2zm0 6a2 2 0 10.001 4.001A2 2 0 007 8zm0 6a2 2 0 10.001 4.001A2 2 0 007 14zm6-8a2 2 0 10-.001-4.001A2 2 0 0013 6zm0 2a2 2 0 10.001 4.001A2 2 0 0013 8zm0 6a2 2 0 10.001 4.001A2 2 0 0013 14z" />
      </svg>

      {/* Label */}
      <span className="flex-1 truncate text-gray-700">
        {item.label}
        {item.isBuiltin && (
          <span className="ml-1.5 text-[10px] font-normal text-gray-400">(built-in)</span>
        )}
      </span>

      {/* Pin/unpin toggle — explicit move between sections */}
      <button
        type="button"
        onClick={() => togglePinned(item.code)}
        className="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-blue-600"
        title={item.pinned ? 'Move to menu' : 'Pin to toolbar'}
      >
        {item.pinned ? (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M12 5v14M5 12l7 7 7-7" />
          </svg>
        ) : (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M12 19V5M5 12l7-7 7 7" />
          </svg>
        )}
      </button>

      {/* Visibility toggle */}
      <button
        type="button"
        onClick={() => toggleVisible(item.code)}
        className={cn(
          'rounded p-1 transition-colors',
          item.visible ? 'text-green-600 hover:bg-green-50' : 'text-gray-300 hover:bg-gray-100',
        )}
        title={item.visible ? 'Hide button' : 'Show button'}
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-md rounded-lg bg-white shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-5 py-4">
          <h3 className="text-base font-semibold text-gray-900">Configure Buttons</h3>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
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
          <div className="mb-2 text-xs font-medium uppercase tracking-wide text-gray-500">
            Toolbar
          </div>
          <div className="mb-3 space-y-0.5">
            {pinnedItems.length === 0 ? (
              <div className="px-3 py-2 text-sm text-gray-400 italic">No pinned buttons</div>
            ) : (
              pinnedItems.map((item, idx) => renderItem(item, idx, 'pinned'))
            )}
          </div>

          {/* Divider */}
          <div className="my-3 flex items-center gap-2">
            <div className="flex-1 border-t border-gray-200" />
            <span className="text-xs text-gray-400">Below this line: in ··· menu</span>
            <div className="flex-1 border-t border-gray-200" />
          </div>

          {/* Overflow section */}
          <div className="mb-2 text-xs font-medium uppercase tracking-wide text-gray-500">
            More Menu
          </div>
          <div className="space-y-0.5">
            {overflowItems.length === 0 ? (
              <div className="px-3 py-2 text-sm text-gray-400 italic">No overflow buttons</div>
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
