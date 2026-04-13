/**
 * ToolbarActionGroup — Configurable toolbar action buttons.
 *
 * Merges DSL button definitions with SavedView toolbar action config
 * to determine which buttons are pinned (visible directly in toolbar)
 * and which are in the overflow "..." menu.
 *
 * The overflow menu also contains built-in actions: Import, Export, Print.
 * A "Configure buttons..." link opens ActionConfigPanel.
 */

import React, { useState, useRef, useEffect, useMemo, useCallback } from 'react';
import type { ButtonConfig } from '~/meta/schemas/types';
import type { ToolbarActionConfig } from '~/smart/types/savedView';
import { cn } from '~/utils/cn';
import { ActionConfigPanel } from './ActionConfigPanel';

export interface ToolbarActionGroupProps {
  buttons: ButtonConfig[];
  toolbarActions?: ToolbarActionConfig[];
  onAction: (button: ButtonConfig) => void;
  onConfigChange: (config: ToolbarActionConfig[]) => void;
  resolveLabel: (button: ButtonConfig) => string;
  evaluateVisible: (button: ButtonConfig) => boolean;
  onImport: () => void;
  onExport: (format: 'xlsx' | 'csv') => void;
  onPrint?: () => void;
  modelCode: string;
  filters?: Array<{ field: string; operator: string; value: unknown }>;
  hideBuiltInImport?: boolean;
}

/** Default pinning logic: primary buttons are pinned, first 2 non-primary are pinned */
export function buildDefaultConfig(buttons: ButtonConfig[]): ToolbarActionConfig[] {
  let nonPrimaryCount = 0;
  return buttons.map((btn, idx) => {
    const isPrimary = btn.primary || btn.variant === 'primary';
    const pinned = isPrimary || nonPrimaryCount < 2;
    if (!isPrimary) nonPrimaryCount++;
    return {
      code: btn.code,
      visible: true,
      pinned,
      order: idx,
    };
  });
}

/** Merge DSL buttons with saved config, preserving new buttons */
export function mergeConfig(
  buttons: ButtonConfig[],
  config?: ToolbarActionConfig[],
): ToolbarActionConfig[] {
  if (!config || config.length === 0) return buildDefaultConfig(buttons);

  const configMap = new Map(config.map((c) => [c.code, c]));
  const merged: ToolbarActionConfig[] = [];

  // First: existing config items (in their saved order)
  for (const c of config) {
    if (buttons.some((b) => b.code === c.code)) {
      merged.push(c);
    }
  }

  // Then: new buttons not in config
  let maxOrder = merged.length > 0 ? Math.max(...merged.map((m) => m.order)) + 1 : 0;
  for (const btn of buttons) {
    if (!configMap.has(btn.code)) {
      merged.push({
        code: btn.code,
        visible: true,
        pinned: false,
        order: maxOrder++,
      });
    }
  }

  return merged.sort((a, b) => a.order - b.order);
}

export const ToolbarActionGroup: React.FC<ToolbarActionGroupProps> = ({
  buttons,
  toolbarActions,
  onAction,
  onConfigChange,
  resolveLabel,
  evaluateVisible,
  onImport,
  onExport,
  onPrint,
  modelCode,
  filters,
  hideBuiltInImport,
}) => {
  const [menuOpen, setMenuOpen] = useState(false);
  const [configPanelOpen, setConfigPanelOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  const effectiveConfig = useMemo(
    () => mergeConfig(buttons, toolbarActions),
    [buttons, toolbarActions],
  );

  // Determine visibility of built-in actions from saved config
  const builtinVisibility = useMemo(() => {
    const map: Record<string, boolean> = {
      _import: true,
      _export_excel: true,
      _export_csv: true,
      _print: true,
    };
    toolbarActions?.forEach((ta) => {
      if (ta.code in map) map[ta.code] = ta.visible;
    });
    return map;
  }, [toolbarActions]);

  // Separate pinned and overflow buttons
  const { pinnedButtons, overflowButtons } = useMemo(() => {
    const pinned: ButtonConfig[] = [];
    const overflow: ButtonConfig[] = [];

    for (const cfg of effectiveConfig) {
      if (!cfg.visible) continue;
      const btn = buttons.find((b) => b.code === cfg.code);
      if (!btn) continue;
      if (!evaluateVisible(btn)) continue;
      if (cfg.pinned) {
        pinned.push(btn);
      } else {
        overflow.push(btn);
      }
    }
    return { pinnedButtons: pinned, overflowButtons: overflow };
  }, [effectiveConfig, buttons, evaluateVisible]);

  // Close menu on outside click
  useEffect(() => {
    if (!menuOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [menuOpen]);

  const handlePrint = useCallback(() => {
    setMenuOpen(false);
    if (onPrint) {
      onPrint();
    } else {
      window.print();
    }
  }, [onPrint]);

  return (
    <>
      {/* Pinned buttons rendered directly in toolbar */}
      {pinnedButtons.map((button) => (
        <button
          type="button"
          key={button.code}
          data-testid={`toolbar-btn-${button.code}`}
          onClick={() => onAction(button)}
          className={cn(
            'rounded-lg px-4 py-2 text-sm font-medium transition-colors duration-150',
            button.primary || button.variant === 'primary'
              ? 'bg-blue-600 text-white shadow-sm hover:bg-blue-700'
              : button.variant === 'danger' || button.danger
                ? 'bg-red-600 text-white shadow-sm hover:bg-red-700'
                : 'border border-gray-200 bg-white text-gray-700 hover:bg-gray-50',
          )}
        >
          {resolveLabel(button)}
        </button>
      ))}

      {/* More button with overflow menu */}
      <div ref={menuRef} className="relative inline-block">
        <button
          type="button"
          data-testid="toolbar-more-menu"
          onClick={() => setMenuOpen(!menuOpen)}
          className={cn(
            'inline-flex items-center justify-center rounded-lg border border-gray-200 bg-white p-2 text-gray-500',
            'hover:bg-gray-50 hover:text-gray-700 focus:ring-2 focus:ring-blue-500 focus:outline-none',
            'transition-colors duration-150',
          )}
          title="More actions"
        >
          <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
            <path d="M10 6a2 2 0 110-4 2 2 0 010 4zM10 12a2 2 0 110-4 2 2 0 010 4zM10 18a2 2 0 110-4 2 2 0 010 4z" />
          </svg>
        </button>

        {menuOpen && (
          <div className="absolute right-0 z-50 mt-1 min-w-[200px] rounded-xl border border-gray-200 bg-white py-1 shadow-lg">
            {/* Overflow action buttons */}
            {overflowButtons.map((button) => (
              <button
                key={button.code}
                type="button"
                data-testid={`more-menu-action-${button.code}`}
                onClick={() => {
                  setMenuOpen(false);
                  onAction(button);
                }}
                className="flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
              >
                {resolveLabel(button)}
              </button>
            ))}

            {overflowButtons.length > 0 && <div className="mx-2 my-1 h-px bg-gray-100" />}

            {/* Built-in actions — conditionally rendered based on config visibility */}
            {!hideBuiltInImport && builtinVisibility._import && (
              <button
                type="button"
                data-testid="more-menu-import"
                onClick={() => {
                  setMenuOpen(false);
                  onImport();
                }}
                className="flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
              >
                <svg
                  className="h-4 w-4 text-gray-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
                  />
                </svg>
                Import
              </button>
            )}

            {builtinVisibility._export_excel && (
              <button
                type="button"
                data-testid="more-menu-export-excel"
                onClick={() => {
                  setMenuOpen(false);
                  onExport('xlsx');
                }}
                className="flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
              >
                <svg
                  className="h-4 w-4 text-gray-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                  />
                </svg>
                Export Excel
              </button>
            )}

            {builtinVisibility._export_csv && (
              <button
                type="button"
                data-testid="more-menu-export-csv"
                onClick={() => {
                  setMenuOpen(false);
                  onExport('csv');
                }}
                className="flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
              >
                <svg
                  className="h-4 w-4 text-gray-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                  />
                </svg>
                Export CSV
              </button>
            )}

            {builtinVisibility._print && (
              <button
                type="button"
                data-testid="more-menu-print"
                onClick={handlePrint}
                className="flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
              >
                <svg
                  className="h-4 w-4 text-gray-400"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"
                  />
                </svg>
                Print
              </button>
            )}

            <div className="mx-2 my-1 h-px bg-gray-100" />

            {/* Configure buttons link */}
            <button
              type="button"
              data-testid="more-menu-configure-buttons"
              onClick={() => {
                setMenuOpen(false);
                setConfigPanelOpen(true);
              }}
              className="flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm font-medium text-blue-600 hover:bg-blue-50"
            >
              <svg
                className="h-4 w-4"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                />
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                />
              </svg>
              Configure buttons...
            </button>
          </div>
        )}
      </div>

      {/* Action config panel */}
      {configPanelOpen && (
        <ActionConfigPanel
          buttons={buttons}
          currentConfig={toolbarActions}
          resolveLabel={resolveLabel}
          onChange={(config) => onConfigChange(config)}
          onClose={() => setConfigPanelOpen(false)}
        />
      )}
    </>
  );
};

export default ToolbarActionGroup;
