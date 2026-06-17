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
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { ToolbarActionConfig } from '~/framework/smart/types/savedView';
import { cn } from '~/utils/cn';
import { ActionConfigPanel } from './ActionConfigPanel';
import {
  reportTemplateService,
  type ReportTemplateDTO,
} from '~/shared/services/reportTemplateService';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';

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
  hideBuiltInExport?: boolean;
  hideBuiltInPrint?: boolean;
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
  hideBuiltInExport,
  hideBuiltInPrint,
}) => {
  const [menuOpen, setMenuOpen] = useState(false);
  const [configPanelOpen, setConfigPanelOpen] = useState(false);
  const [reportTemplates, setReportTemplates] = useState<ReportTemplateDTO[]>([]);
  const [loadingReports, setLoadingReports] = useState(false);
  const [generatingReport, setGeneratingReport] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const { showErrorToast, showSuccessToast } = useToastContext();

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

  const hasBuiltInMenuItems =
    (!hideBuiltInImport && builtinVisibility._import) ||
    (!hideBuiltInExport && (builtinVisibility._export_excel || builtinVisibility._export_csv)) ||
    (!hideBuiltInPrint && builtinVisibility._print);
  const showMoreButton = overflowButtons.length > 0 || hasBuiltInMenuItems;

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

  useEffect(() => {
    if (!menuOpen) return;
    let cancelled = false;
    setLoadingReports(true);
    reportTemplateService
      .getPublished()
      .then((resp) => {
        if (cancelled) return;
        if (ResultHelper.isSuccess(resp) && resp.data) {
          setReportTemplates(
            resp.data.filter((tpl) => !tpl.category || tpl.category === modelCode),
          );
        }
      })
      .catch(() => {
        if (!cancelled) setReportTemplates([]);
      })
      .finally(() => {
        if (!cancelled) setLoadingReports(false);
      });
    return () => {
      cancelled = true;
    };
  }, [menuOpen, modelCode]);

  const handleGenerateReport = useCallback(
    async (template: ReportTemplateDTO) => {
      setGeneratingReport(true);
      setMenuOpen(false);
      try {
        const blob = await reportTemplateService.generate(template.code, { filters });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `${template.name || template.code}.${template.outputFormat || 'pdf'}`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        URL.revokeObjectURL(url);
        showSuccessToast(`Report generated: ${template.name}`);
      } catch (err) {
        showErrorToast(err instanceof Error ? err.message : 'Report generation failed');
      } finally {
        setGeneratingReport(false);
      }
    },
    [filters, showErrorToast, showSuccessToast],
  );

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
            'rounded-card px-4 py-2 text-sm font-medium transition-colors duration-150',
            button.primary || button.variant === 'primary'
              ? 'bg-accent hover:bg-accent-hover text-white shadow-sm'
              : button.variant === 'danger' || button.danger
                ? 'bg-red-600 text-white shadow-sm hover:bg-red-700'
                : 'border-border bg-panel text-text-2 hover:bg-hover border',
          )}
        >
          {resolveLabel(button)}
        </button>
      ))}

      {/* More button with overflow menu */}
      {showMoreButton && (
        <div ref={menuRef} className="relative inline-block">
          <button
            type="button"
            data-testid="toolbar-more-menu"
            onClick={() => setMenuOpen(!menuOpen)}
            disabled={generatingReport}
            className={cn(
              'rounded-card border-border bg-panel text-text-2 inline-flex items-center justify-center border p-2',
              'hover:bg-hover hover:text-text-2 focus-visible:shadow-focus focus:outline-none',
              'disabled:cursor-not-allowed disabled:opacity-50',
              'transition-colors duration-150',
            )}
            title="More actions"
          >
            {generatingReport ? (
              <span className="rounded-pill border-border-strong border-t-accent h-4 w-4 animate-spin border-2" />
            ) : (
              <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
                <path d="M10 6a2 2 0 110-4 2 2 0 010 4zM10 12a2 2 0 110-4 2 2 0 010 4zM10 18a2 2 0 110-4 2 2 0 010 4z" />
              </svg>
            )}
          </button>

          {menuOpen && (
            <div className="rounded-card-lg border-border bg-panel absolute right-0 z-50 mt-1 min-w-[200px] border py-1 shadow-lg">
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
                  className="text-text-2 hover:bg-hover flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm"
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
                  className="text-text-2 hover:bg-hover flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm"
                >
                  <svg
                    className="text-text-3 h-4 w-4"
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

              {!hideBuiltInExport && builtinVisibility._export_excel && (
                <button
                  type="button"
                  data-testid="more-menu-export-excel"
                  onClick={() => {
                    setMenuOpen(false);
                    onExport('xlsx');
                  }}
                  className="text-text-2 hover:bg-hover flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm"
                >
                  <svg
                    className="text-text-3 h-4 w-4"
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

              {!hideBuiltInExport && builtinVisibility._export_csv && (
                <button
                  type="button"
                  data-testid="more-menu-export-csv"
                  onClick={() => {
                    setMenuOpen(false);
                    onExport('csv');
                  }}
                  className="text-text-2 hover:bg-hover flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm"
                >
                  <svg
                    className="text-text-3 h-4 w-4"
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

              {!hideBuiltInPrint && builtinVisibility._print && (
                <button
                  type="button"
                  data-testid="more-menu-print"
                  onClick={handlePrint}
                  className="text-text-2 hover:bg-hover flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm"
                >
                  <svg
                    className="text-text-3 h-4 w-4"
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

              {(loadingReports || reportTemplates.length > 0) && (
                <>
                  <div className="mx-2 my-1 h-px bg-gray-100" />
                  {loadingReports ? (
                    <div className="text-text-3 px-3.5 py-2 text-center text-xs">
                      Loading reports...
                    </div>
                  ) : (
                    reportTemplates.map((tpl) => (
                      <button
                        key={tpl.pid}
                        type="button"
                        data-testid={`more-menu-report-${tpl.code}`}
                        onClick={() => handleGenerateReport(tpl)}
                        className="text-text-2 hover:bg-hover flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm"
                      >
                        <span className="truncate">{tpl.name}</span>
                        <span
                          className={cn(
                            'ml-auto inline-flex shrink-0 items-center rounded px-1 py-0.5 text-[10px] font-medium',
                            tpl.outputFormat === 'pdf'
                              ? 'bg-red-50 text-red-600'
                              : tpl.outputFormat === 'xlsx'
                                ? 'bg-green-50 text-green-600'
                                : 'bg-blue-50 text-blue-600',
                          )}
                        >
                          {tpl.outputFormat}
                        </span>
                      </button>
                    ))
                  )}
                </>
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
                className="text-accent hover:bg-accent-weak flex w-full items-center gap-2.5 px-3.5 py-2 text-left text-sm font-medium"
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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
      )}

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
