/**
 * DesignerToolbar Component
 *
 * Modular toolbar for the page designer with organized zones.
 *
 * Zone Layout:
 * [A: Navigation] | [B: History] | [C: Zoom] | [D: Device] | --- | [E: Version] | [F: IO] | [G: Preview] | [H: Save] | [I: Publish]
 *
 * @since 3.2.0
 */

import React, { useState, useCallback } from 'react';
import type { PageMeta } from '../../../services/page-manager';
import { PAGE_STATUS_INFO } from '../../../services/page-manager';
import { SaveAsTemplateDialog } from '~/plugins/core-designer/components/studio/components/SaveAsTemplateDialog';
import { AiPageGenerateDialog } from '~/plugins/core-designer/components/studio/components/AiPageGenerateDialog';
import type { MergeMode } from '~/plugins/core-designer/components/studio/components/ai-page-prompt';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';

/**
 * DesignerToolbar props
 */
export interface DesignerToolbarProps {
  /** Page metadata */
  pageMeta?: PageMeta;
  /** Whether there are unsaved changes */
  hasUnsavedChanges?: boolean;
  /** Whether undo is available */
  canUndo?: boolean;
  /** Whether redo is available */
  canRedo?: boolean;
  /** Current zoom level (percentage) */
  zoomLevel?: number;
  /** Whether auto-save is enabled */
  autoSaveEnabled?: boolean;
  /** Last saved time */
  lastSavedAt?: string;
  /** Whether currently saving */
  isSaving?: boolean;
  /** Whether currently publishing */
  isPublishing?: boolean;

  // Callbacks
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
  /** Called when AI generates a page DSL */
  onAiGenerated?: (dsl: { kind: PageSchema['kind']; blocks: PageSchema['blocks']; layout: PageSchema['layout']; schemaVersion: 2; mergeMode?: MergeMode }) => void;
  /** Whether the AI panel is currently open */
  aiPanelOpen?: boolean;
  /** Toggle AI panel visibility */
  onToggleAiPanel?: () => void;
}

/**
 * Toolbar button component
 */
const ToolbarButton: React.FC<{
  icon: React.ReactNode;
  label?: string;
  title: string;
  onClick?: () => void;
  disabled?: boolean;
  active?: boolean;
  variant?: 'default' | 'primary' | 'success' | 'danger';
  size?: 'sm' | 'md';
  'data-testid'?: string;
}> = ({
  icon,
  label,
  title,
  onClick,
  disabled = false,
  active = false,
  variant = 'default',
  size = 'md',
  'data-testid': testId,
}) => {
  const baseClass = size === 'sm' ? 'p-1.5' : 'px-2.5 py-1.5';
  const variantClass = {
    default: active
      ? 'bg-blue-100 text-blue-700'
      : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900',
    primary: 'bg-blue-600 text-white hover:bg-blue-700',
    success: 'bg-green-600 text-white hover:bg-green-700',
    danger: 'text-red-600 hover:bg-red-50',
  }[variant];

  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={title}
      data-testid={testId}
      className={` ${baseClass} flex items-center gap-1.5 rounded-md text-sm font-medium transition-colors ${variantClass} ${disabled ? 'cursor-not-allowed opacity-40' : ''} `}
    >
      {icon}
      {label && <span>{label}</span>}
    </button>
  );
};

/**
 * Toolbar divider
 */
const ToolbarDivider: React.FC = () => <div className="mx-1 h-6 w-px bg-gray-200" />;

/**
 * Toolbar group
 */
const ToolbarGroup: React.FC<{ children: React.ReactNode; className?: string }> = ({
  children,
  className = '',
}) => <div className={`flex items-center gap-0.5 ${className}`}>{children}</div>;

/**
 * DesignerToolbar component
 */
export const DesignerToolbar: React.FC<DesignerToolbarProps> = ({
  pageMeta,
  hasUnsavedChanges = false,
  canUndo = false,
  canRedo = false,
  zoomLevel = 100,
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

  const statusInfo = pageMeta?.status ? PAGE_STATUS_INFO[pageMeta.status] : null;

  const formatLastSaved = useCallback((dateStr?: string) => {
    if (!dateStr) return '';
    const date = new Date(dateStr);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMin = Math.floor(diffMs / 60000);

    if (diffMin < 1) return 'Just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  }, []);

  const zoomPresets = [50, 75, 100, 125, 150, 200];
  const devices = [
    { id: 'desktop', label: 'Desktop', width: 1920 },
    { id: 'laptop', label: 'Laptop', width: 1440 },
    { id: 'tablet', label: 'Tablet', width: 768 },
    { id: 'mobile', label: 'Mobile', width: 375 },
  ];

  return (
    <div className="flex h-12 items-center justify-between border-b border-gray-200 bg-white px-3">
      {/* Left section */}
      <div className="flex items-center gap-2">
        {/* Zone A: Navigation */}
        <ToolbarGroup>
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
            title="Back to pages (Esc)"
            onClick={onBack}
            data-testid="toolbar-back"
          />
          {pageMeta && (
            <div className="ml-2 flex items-center gap-2">
              <span className="max-w-[200px] truncate font-medium text-gray-900">
                {pageMeta.title}
              </span>
              {statusInfo && (
                <span
                  className={`rounded px-1.5 py-0.5 text-xs ${statusInfo.color} ${statusInfo.bgColor}`}
                >
                  {statusInfo.label}
                </span>
              )}
              {hasUnsavedChanges && (
                <span className="h-2 w-2 rounded-full bg-orange-500" title="Unsaved changes" />
              )}
            </div>
          )}
        </ToolbarGroup>

        <ToolbarDivider />

        {/* Zone B: History */}
        <ToolbarGroup>
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
            title="Undo (Ctrl+Z)"
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
            title="Redo (Ctrl+Shift+Z)"
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
            title="Clear all"
            onClick={onClear}
            size="sm"
          />
        </ToolbarGroup>

        <ToolbarDivider />

        {/* Zone C: Zoom */}
        <ToolbarGroup>
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
            title="Zoom out (Ctrl+-)"
            onClick={onZoomOut}
            disabled={zoomLevel <= 25}
            size="sm"
            data-testid="toolbar-zoom-out"
          />
          <div className="relative">
            <button
              onClick={() => setShowZoomMenu(!showZoomMenu)}
              className="min-w-[60px] rounded px-2 py-1 text-center text-sm text-gray-600 hover:bg-gray-100"
              data-testid="toolbar-zoom-level"
            >
              {zoomLevel}%
            </button>
            {showZoomMenu && (
              <div className="absolute top-full left-0 z-50 mt-1 min-w-[100px] rounded-lg border border-gray-200 bg-white py-1 shadow-lg">
                {zoomPresets.map((preset) => (
                  <button
                    key={preset}
                    onClick={() => {
                      onZoomChange?.(preset);
                      setShowZoomMenu(false);
                    }}
                    className={`w-full px-3 py-1.5 text-left text-sm hover:bg-gray-100 ${
                      zoomLevel === preset ? 'bg-blue-50 text-blue-600' : 'text-gray-700'
                    }`}
                  >
                    {preset}%
                  </button>
                ))}
                <div className="my-1 border-t border-gray-100" />
                <button
                  onClick={() => {
                    onZoomReset?.();
                    setShowZoomMenu(false);
                  }}
                  className="w-full px-3 py-1.5 text-left text-sm text-gray-700 hover:bg-gray-100"
                >
                  Fit to screen
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
            title="Zoom in (Ctrl++)"
            onClick={onZoomIn}
            disabled={zoomLevel >= 200}
            size="sm"
            data-testid="toolbar-zoom-in"
          />
        </ToolbarGroup>

        <ToolbarDivider />

        {/* Zone D: Device */}
        <ToolbarGroup>
          <div className="relative">
            <button
              onClick={() => setShowDeviceMenu(!showDeviceMenu)}
              className="flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-sm text-gray-600 hover:bg-gray-100"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
                />
              </svg>
              <span>Desktop</span>
              <svg className="h-3 w-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M19 9l-7 7-7-7"
                />
              </svg>
            </button>
            {showDeviceMenu && (
              <div className="absolute top-full left-0 z-50 mt-1 min-w-[140px] rounded-lg border border-gray-200 bg-white py-1 shadow-lg">
                {devices.map((device) => (
                  <button
                    key={device.id}
                    onClick={() => {
                      onDeviceChange?.(device.id);
                      setShowDeviceMenu(false);
                    }}
                    className="flex w-full items-center justify-between px-3 py-1.5 text-left text-sm text-gray-700 hover:bg-gray-100"
                  >
                    <span>{device.label}</span>
                    <span className="text-xs text-gray-400">{device.width}px</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        </ToolbarGroup>
      </div>

      {/* Right section */}
      <div className="flex items-center gap-2">
        {/* Save status indicator */}
        {autoSaveEnabled && (
          <span
            className="mr-2 flex items-center gap-1 text-xs"
            data-testid="toolbar-save-status"
          >
            {isSaving ? (
              <>
                <svg
                  className="h-3 w-3 animate-spin text-blue-500"
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
                <span className="text-blue-500">Saving...</span>
              </>
            ) : lastSavedAt ? (
              <>
                <span className="h-2 w-2 rounded-full bg-green-500" />
                <span className="text-gray-400">
                  Saved {formatLastSaved(lastSavedAt)}
                </span>
              </>
            ) : hasUnsavedChanges ? (
              <span className="text-orange-500">Unsaved changes</span>
            ) : null}
          </span>
        )}

        {/* Zone E: Version */}
        <ToolbarGroup>
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
            label="History"
            title="Version history"
            onClick={onVersionHistory}
          />
        </ToolbarGroup>

        <ToolbarDivider />

        {/* Zone F: Import/Export */}
        <ToolbarGroup>
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
            title="Import"
            onClick={onImport}
            size="sm"
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
            title="Export"
            onClick={onExport}
            size="sm"
          />
        </ToolbarGroup>

        <ToolbarDivider />

        {/* Zone G: Preview */}
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
          label="Preview"
          title="Preview page (Ctrl+P)"
          onClick={onPreview}
          data-testid="toolbar-preview"
        />

        <ToolbarDivider />

        {/* AI Generate — toggles the side panel */}
        <ToolbarButton
          icon={<span className="text-sm">&#x2728;</span>}
          label="AI"
          title="Toggle AI assistant panel"
          onClick={onToggleAiPanel || (() => setShowAiGenerate(true))}
          active={aiPanelOpen}
          data-testid="toolbar-ai-generate"
        />

        {/* Zone H: Save as Template */}
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
            label="Template"
            title="Save as Template"
            onClick={() => setShowSaveAsTemplate(true)}
            data-testid="toolbar-save-as-template"
          />
        )}

        {/* Zone H: Save */}
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
          label="Save"
          title="Save (Ctrl+S)"
          onClick={onSave}
          disabled={isSaving || !hasUnsavedChanges}
          data-testid="toolbar-save"
        />

        {/* Zone I: Publish */}
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
          label="Publish"
          title="Publish page"
          onClick={onPublish}
          variant="primary"
          disabled={isPublishing}
          data-testid="toolbar-publish"
        />

        <ToolbarDivider />

        {/* Settings & Help */}
        <ToolbarGroup>
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
            title="Settings"
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
            title="Keyboard shortcuts (?)"
            onClick={onShortcutHelp}
            size="sm"
          />
        </ToolbarGroup>
      </div>

      {/* Save as Template Dialog */}
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

      {/* AI Page Generate Dialog */}
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
