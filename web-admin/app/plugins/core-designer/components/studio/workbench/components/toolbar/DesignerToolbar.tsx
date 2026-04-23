/**
 * DesignerToolbar Component
 *
 * Reframed as a two-tier workbench header:
 * 1. Page identity + save state + primary actions
 * 2. History / zoom / device / version controls
 *
 * This keeps the page designer header readable on real project widths instead
 * of collapsing every action into a single crowded row.
 *
 * @since 3.2.0
 */

import React, { useCallback, useState } from 'react';
import { PAGE_STATUS_INFO, type PageMeta } from '../../../services/page-manager';
import { SaveAsTemplateDialog } from '~/plugins/core-designer/components/studio/components/SaveAsTemplateDialog';
import { AiPageGenerateDialog } from '~/plugins/core-designer/components/studio/components/AiPageGenerateDialog';
import type { MergeMode } from '~/plugins/core-designer/components/studio/components/ai-page-prompt';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { usePermissions } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N } from '~/shared/designer/designerI18n';

export interface DesignerToolbarProps {
  pageMeta?: PageMeta;
  hasUnsavedChanges?: boolean;
  canUndo?: boolean;
  canRedo?: boolean;
  zoomLevel?: number;
  currentDevice?: string;
  autoSaveEnabled?: boolean;
  lastSavedAt?: string;
  isSaving?: boolean;
  isPublishing?: boolean;
  onBack?: () => void;
  onUndo?: () => void;
  onRedo?: () => void;
  onClear?: () => void;
  onZoomIn?: () => void;
  onZoomOut?: () => void;
  onZoomReset?: () => void;
  onZoomChange?: (level: number) => void;
  onDeviceChange?: (device: string) => void;
  onVersionHistory?: () => void;
  onImport?: () => void;
  onExport?: () => void;
  onPreview?: () => void;
  onSave?: () => void;
  onPublish?: () => void;
  onSettings?: () => void;
  onShortcutHelp?: () => void;
  onAiGenerated?: (dsl: {
    kind: PageSchema['kind'];
    blocks: PageSchema['blocks'];
    layout: PageSchema['layout'];
    schemaVersion: 2;
    mergeMode?: MergeMode;
  }) => void;
  aiPanelOpen?: boolean;
  onToggleAiPanel?: () => void;
}

const ToolbarButton: React.FC<{
  icon: React.ReactNode;
  label?: string;
  title: string;
  disabledTitle?: string;
  onClick?: () => void;
  disabled?: boolean;
  active?: boolean;
  variant?: 'default' | 'primary';
  size?: 'sm' | 'md';
  'data-testid'?: string;
}> = ({
  icon,
  label,
  title,
  disabledTitle,
  onClick,
  disabled = false,
  active = false,
  variant = 'default',
  size = 'md',
  'data-testid': testId,
}) => {
  const baseClass = size === 'sm' ? 'h-8 px-2.5 text-xs' : 'h-10 px-3.5 text-sm';
  const variantClass =
    variant === 'primary'
      ? 'border-blue-600 bg-blue-600 text-white hover:border-blue-700 hover:bg-blue-700'
      : active
        ? 'border-blue-200 bg-blue-50 text-blue-700'
        : 'border-slate-200 bg-white text-slate-700 hover:border-slate-300 hover:bg-slate-50';

  const resolvedTitle = disabled && disabledTitle ? disabledTitle : title;

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={resolvedTitle}
      aria-label={resolvedTitle}
      data-testid={testId}
      className={`${baseClass} inline-flex items-center gap-1.5 rounded-xl border font-medium transition-colors ${
        disabled ? 'cursor-not-allowed opacity-40' : ''
      } ${variantClass}`}
    >
      {icon}
      {label && <span>{label}</span>}
    </button>
  );
};

const ControlGroup: React.FC<{ label: string; children: React.ReactNode }> = ({
  label,
  children,
}) => (
  <div className="flex flex-wrap items-center gap-2 rounded-2xl border border-slate-200 bg-white px-2 py-2">
    <span className="px-1 text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-400">
      {label}
    </span>
    <div className="flex flex-wrap items-center gap-2">{children}</div>
  </div>
);

export const DesignerToolbar: React.FC<DesignerToolbarProps> = ({
  pageMeta,
  hasUnsavedChanges = false,
  canUndo = false,
  canRedo = false,
  zoomLevel = 100,
  currentDevice = 'desktop',
  autoSaveEnabled = true,
  lastSavedAt,
  isSaving = false,
  isPublishing = false,
  onBack,
  onUndo,
  onRedo,
  onClear,
  onZoomIn,
  onZoomOut,
  onZoomReset,
  onZoomChange,
  onDeviceChange,
  onVersionHistory,
  onImport,
  onExport,
  onPreview,
  onSave,
  onPublish,
  onSettings,
  onShortcutHelp,
  onAiGenerated,
  aiPanelOpen = false,
  onToggleAiPanel,
}) => {
  const [showZoomMenu, setShowZoomMenu] = useState(false);
  const [showDeviceMenu, setShowDeviceMenu] = useState(false);
  const [showSaveAsTemplate, setShowSaveAsTemplate] = useState(false);
  const [showAiGenerate, setShowAiGenerate] = useState(false);

  const { hasPermission } = usePermissions();
  const canManage = hasPermission('page.page.manage');
  const canSave = canManage;
  const canPublish = canManage;
  const canImport = canManage;
  const canExport = canManage;
  const { locale } = useI18n();

  const statusInfo = pageMeta?.status ? PAGE_STATUS_INFO[pageMeta.status] : null;
  const zoomPresets = [50, 75, 100, 125, 150, 200];
  const devices = [
    { id: 'desktop', label: '桌面', width: 1920 },
    { id: 'laptop', label: '笔记本', width: 1440 },
    { id: 'tablet', label: '平板', width: 768 },
    { id: 'mobile', label: '手机', width: 375 },
  ];
  const currentDeviceMeta = devices.find((item) => item.id === currentDevice) ?? devices[0];

  const formatLastSaved = useCallback((dateStr?: string) => {
    if (!dateStr) return '';
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1) return '刚刚';
    if (diffMin < 60) return `${diffMin} 分钟前`;
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  }, []);

  return (
    <div className="border-b border-slate-200 bg-white px-4 py-3">
      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-3 2xl:flex-row 2xl:items-start 2xl:justify-between">
          <div className="flex min-w-0 items-start gap-3">
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M10 19l-7-7m0 0l7-7m-7 7h18"
                  />
                </svg>
              }
              title="返回页面列表"
              onClick={onBack}
              data-testid="toolbar-back"
            />
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="max-w-[420px] truncate text-2xl font-semibold tracking-tight text-slate-950">
                  {pageMeta?.title ?? '未命名页面'}
                </h1>
                {statusInfo && (
                  <span
                    className={`rounded-full border px-2.5 py-1 text-xs font-medium ${statusInfo.color} ${statusInfo.bgColor}`}
                  >
                    {statusInfo.label}
                  </span>
                )}
                <span
                  className={`rounded-full px-2.5 py-1 text-xs font-medium ${
                    hasUnsavedChanges
                      ? 'bg-amber-50 text-amber-700 ring-1 ring-inset ring-amber-200'
                      : 'bg-emerald-50 text-emerald-700 ring-1 ring-inset ring-emerald-200'
                  }`}
                  data-testid="toolbar-draft-state"
                >
                  {hasUnsavedChanges ? '待保存' : '已同步'}
                </span>
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-2 text-sm text-slate-500">
                <span>页面设计器</span>
                <span className="text-slate-300">/</span>
                <span>当前设备：{currentDeviceMeta.label}</span>
                <span className="text-slate-300">/</span>
                <span>缩放 {zoomLevel}%</span>
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2 2xl:justify-end">
            {autoSaveEnabled && (
              <div
                className="inline-flex min-h-10 items-center gap-2 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm"
                data-testid="toolbar-save-status"
              >
                {isSaving ? (
                  <>
                    <svg
                      className="h-4 w-4 animate-spin text-blue-500"
                      fill="none"
                      viewBox="0 0 24 24"
                    >
                      <circle
                        className="opacity-25"
                        cx="12"
                        cy="12"
                        r="10"
                        stroke="currentColor"
                        strokeWidth="4"
                      />
                      <path
                        className="opacity-75"
                        fill="currentColor"
                        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                      />
                    </svg>
                    <span className="font-medium text-blue-700">正在保存</span>
                  </>
                ) : lastSavedAt ? (
                  <>
                    <span className="h-2 w-2 rounded-full bg-emerald-500" />
                    <span className="text-slate-700">已保存 {formatLastSaved(lastSavedAt)}</span>
                  </>
                ) : hasUnsavedChanges ? (
                  <span className="font-medium text-amber-600">存在未保存修改</span>
                ) : (
                  <span className="text-slate-500">等待第一次保存</span>
                )}
              </div>
            )}

            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                  />
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                  />
                </svg>
              }
              label="预览"
              title="预览页面"
              onClick={onPreview}
              data-testid="toolbar-preview"
            />
            <ToolbarButton
              icon={<span className="text-sm">&#x2728;</span>}
              label="AI 助手"
              title="切换 AI 助手面板"
              onClick={onToggleAiPanel || (() => setShowAiGenerate(true))}
              active={aiPanelOpen}
              data-testid="toolbar-ai-generate"
            />
            {pageMeta && (
              <ToolbarButton
                icon={
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"
                    />
                  </svg>
                }
                label="模板"
                title="另存为模板"
                onClick={() => setShowSaveAsTemplate(true)}
                data-testid="toolbar-save-as-template"
              />
            )}
            <ToolbarButton
              icon={
                isSaving ? (
                  <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle
                      className="opacity-25"
                      cx="12"
                      cy="12"
                      r="10"
                      stroke="currentColor"
                      strokeWidth="4"
                    />
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                    />
                  </svg>
                ) : (
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4"
                    />
                  </svg>
                )
              }
              label="保存"
              title="保存"
              disabledTitle={
                !canSave
                  ? (DESIGNER_I18N.permissions.missingManage[locale] ??
                    DESIGNER_I18N.permissions.missingManage['en-US'])
                  : undefined
              }
              onClick={onSave}
              disabled={isSaving || !hasUnsavedChanges || !canSave}
              data-testid="toolbar-save"
            />
            <ToolbarButton
              icon={
                isPublishing ? (
                  <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle
                      className="opacity-25"
                      cx="12"
                      cy="12"
                      r="10"
                      stroke="currentColor"
                      strokeWidth="4"
                    />
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                    />
                  </svg>
                ) : (
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
                    />
                  </svg>
                )
              }
              label="发布"
              title="发布页面"
              disabledTitle={
                !canPublish
                  ? (DESIGNER_I18N.permissions.missingManage[locale] ??
                    DESIGNER_I18N.permissions.missingManage['en-US'])
                  : undefined
              }
              onClick={onPublish}
              variant="primary"
              disabled={isPublishing || !canPublish}
              data-testid="toolbar-publish"
            />
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-3 py-3">
          <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
            工作台
          </div>

          <ControlGroup label="历史">
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M3 10h10a8 8 0 018 8v2M3 10l6 6m-6-6l6-6"
                  />
                </svg>
              }
              title="撤销"
              onClick={onUndo}
              disabled={!canUndo}
              size="sm"
              data-testid="toolbar-undo"
            />
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M21 10h-10a8 8 0 00-8 8v2M21 10l-6 6m6-6l-6-6"
                  />
                </svg>
              }
              title="重做"
              onClick={onRedo}
              disabled={!canRedo}
              size="sm"
              data-testid="toolbar-redo"
            />
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                  />
                </svg>
              }
              title="清空"
              onClick={onClear}
              size="sm"
            />
          </ControlGroup>

          <ControlGroup label="缩放">
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0zM13 10H7"
                  />
                </svg>
              }
              title="缩小"
              onClick={onZoomOut}
              disabled={zoomLevel <= 25}
              size="sm"
              data-testid="toolbar-zoom-out"
            />
            <div className="relative">
              <button
                type="button"
                onClick={() => setShowZoomMenu((prev) => !prev)}
                className="min-w-[76px] rounded-xl border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50"
                data-testid="toolbar-zoom-level"
              >
                {zoomLevel}%
              </button>
              {showZoomMenu && (
                <div className="absolute top-full left-0 z-50 mt-2 min-w-[128px] rounded-xl border border-slate-200 bg-white py-1 shadow-lg">
                  {zoomPresets.map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      onClick={() => {
                        onZoomChange?.(preset);
                        setShowZoomMenu(false);
                      }}
                      className={`w-full px-3 py-2 text-left text-sm hover:bg-slate-50 ${
                        zoomLevel === preset ? 'bg-blue-50 text-blue-600' : 'text-slate-700'
                      }`}
                    >
                      {preset}%
                    </button>
                  ))}
                  <div className="my-1 border-t border-slate-100" />
                  <button
                    type="button"
                    onClick={() => {
                      onZoomReset?.();
                      setShowZoomMenu(false);
                    }}
                    className="w-full px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-50"
                  >
                    适配屏幕
                  </button>
                </div>
              )}
            </div>
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0zM10 7v6m3-3H7"
                  />
                </svg>
              }
              title="放大"
              onClick={onZoomIn}
              disabled={zoomLevel >= 200}
              size="sm"
              data-testid="toolbar-zoom-in"
            />
          </ControlGroup>

          <ControlGroup label="设备">
            <div className="relative">
              <button
                type="button"
                onClick={() => setShowDeviceMenu((prev) => !prev)}
                className="flex h-8 items-center gap-1.5 rounded-xl border border-slate-200 px-3 text-sm font-medium text-slate-700 hover:bg-slate-50"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
                  />
                </svg>
                <span>{currentDeviceMeta.label}</span>
                <span className="text-xs text-slate-400">{currentDeviceMeta.width}px</span>
              </button>
              {showDeviceMenu && (
                <div className="absolute top-full left-0 z-50 mt-2 min-w-[170px] rounded-xl border border-slate-200 bg-white py-1 shadow-lg">
                  {devices.map((device) => (
                    <button
                      key={device.id}
                      type="button"
                      onClick={() => {
                        onDeviceChange?.(device.id);
                        setShowDeviceMenu(false);
                      }}
                      className={`flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-slate-50 ${
                        currentDevice === device.id ? 'bg-blue-50 text-blue-700' : 'text-slate-700'
                      }`}
                    >
                      <span>{device.label}</span>
                      <span className="text-xs text-slate-400">{device.width}px</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </ControlGroup>

          <ControlGroup label="版本">
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                  />
                </svg>
              }
              label="历史"
              title="版本历史"
              onClick={onVersionHistory}
            />
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
                  />
                </svg>
              }
              title="导入"
              disabledTitle={
                DESIGNER_I18N.permissions.missingManage[locale] ??
                DESIGNER_I18N.permissions.missingManage['en-US']
              }
              onClick={onImport}
              disabled={!canImport}
              size="sm"
              data-testid="toolbar-import"
            />
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                  />
                </svg>
              }
              title="导出"
              disabledTitle={
                DESIGNER_I18N.permissions.missingManage[locale] ??
                DESIGNER_I18N.permissions.missingManage['en-US']
              }
              onClick={onExport}
              disabled={!canExport}
              size="sm"
              data-testid="toolbar-export"
            />
          </ControlGroup>

          <ControlGroup label="更多">
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                  />
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                  />
                </svg>
              }
              title="设置"
              onClick={onSettings}
              size="sm"
            />
            <ToolbarButton
              icon={
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M8.228 9c.549-1.165 2.03-2 3.772-2 2.21 0 4 1.343 4 3 0 1.4-1.278 2.575-3.006 2.907-.542.104-.994.54-.994 1.093m0 3h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                  />
                </svg>
              }
              title="快捷键帮助"
              onClick={onShortcutHelp}
              size="sm"
            />
          </ControlGroup>
        </div>
      </div>

      {pageMeta && (
        <SaveAsTemplateDialog
          open={showSaveAsTemplate}
          onClose={() => setShowSaveAsTemplate(false)}
          pagePid={pageMeta.id}
          currentName={pageMeta.title}
          onSuccess={() => {
            setShowSaveAsTemplate(false);
          }}
        />
      )}

      <AiPageGenerateDialog
        open={showAiGenerate}
        onClose={() => setShowAiGenerate(false)}
        onGenerated={(dsl) => {
          setShowAiGenerate(false);
          onAiGenerated?.(dsl);
        }}
      />
    </div>
  );
};

export default DesignerToolbar;
