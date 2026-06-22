/**
 * ListPageHeader — Extracted header section from ListPageContent.
 *
 * Contains the page title, ViewSelector, and ToolbarActionGroup (which
 * replaces the flat button rendering + ToolbarMoreMenu).
 */

import React from 'react';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type { ToolbarActionConfig, SavedView, ViewType } from '~/framework/smart/types/savedView';
import { ViewSelector } from '~/framework/smart/components/view/ViewSelector';
import { ToolbarActionGroup } from './ToolbarActionGroup';
import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { PresetViewBar } from './PresetViewBar';
import type { QuickFilterPresetKey } from './quickFilterPresets';

export interface ListPageHeaderProps {
  /** Page title (already localized) */
  title: string;
  /** Model code for test IDs and export */
  modelCode: string;
  /** SavedView state */
  savedViews: SavedView[];
  currentView: SavedView | null;
  viewsLoading: boolean;
  activeViewType: ViewType;
  onSelectView: (pid: string) => void;
  onCreateView: (viewType?: ViewType) => void;
  onManageViews: () => void;
  onViewTypeChange: (vt: ViewType) => void;
  /** Action buttons from DSL toolbar/form-buttons block */
  buttons: ButtonConfig[];
  /** Toolbar action config from SavedView */
  toolbarActions?: ToolbarActionConfig[];
  /** Callbacks */
  onAction: (button: ButtonConfig) => void;
  onToolbarConfigChange: (config: ToolbarActionConfig[]) => void;
  resolveLabel: (button: ButtonConfig) => string;
  evaluateVisible: (button: ButtonConfig) => boolean;
  onImport: () => void;
  onExport: (format: 'xlsx' | 'csv') => void;
  /** Active preset view (T8); null when none selected */
  activePreset?: QuickFilterPresetKey | null;
  /** Toggle a preset view on/off */
  onSelectPreset?: (key: QuickFilterPresetKey) => void;
  /** Save the active preset view as a personal SavedView */
  onSaveActivePreset?: () => void;
  /** Hide the preset-view bar (e.g. config-only pages) */
  hidePresetViews?: boolean;
  /** Current filter conditions for export */
  exportFilters?: Array<{ field: string; operator: string; value: unknown }>;
  /** Whether this is a tenant member page (shows Invite button) */
  isTenantMemberPage?: boolean;
  onInvite?: () => void;
  onImportMembers?: () => void;
  hideSavedViews?: boolean;
  hideBuiltInImport?: boolean;
  hideBuiltInExport?: boolean;
  hideBuiltInPrint?: boolean;
}

export const ListPageHeader: React.FC<ListPageHeaderProps> = ({
  title,
  modelCode,
  savedViews,
  currentView,
  viewsLoading,
  activeViewType,
  onSelectView,
  onCreateView,
  onManageViews,
  onViewTypeChange,
  buttons,
  toolbarActions,
  onAction,
  onToolbarConfigChange,
  resolveLabel,
  evaluateVisible,
  onImport,
  onExport,
  activePreset,
  onSelectPreset,
  onSaveActivePreset,
  hidePresetViews,
  exportFilters,
  isTenantMemberPage,
  onInvite,
  onImportMembers,
  hideSavedViews,
  hideBuiltInImport,
  hideBuiltInExport,
  hideBuiltInPrint,
}) => {
  return (
    <div className="border-border border-b px-6 py-3">
      <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex min-w-0 flex-wrap items-center gap-3">
          <h2 className="text-text flex-shrink-0 whitespace-nowrap text-lg font-semibold">
            {title}
          </h2>
          {!hideSavedViews && (
            <ViewSelector
              views={savedViews}
              currentView={currentView}
              onSelectView={onSelectView}
              onCreateView={onCreateView}
              onManageViews={onManageViews}
              loading={viewsLoading}
              activeViewType={activeViewType}
              onViewTypeChange={onViewTypeChange}
            />
          )}
          {!hidePresetViews && onSelectPreset && (
            <>
              <div className="bg-border hidden h-5 w-px sm:block" aria-hidden />
              <PresetViewBar
                activePreset={activePreset ?? null}
                onSelectPreset={onSelectPreset}
                onSaveActivePreset={onSaveActivePreset}
              />
            </>
          )}
        </div>
        <div
          className="print-hide flex flex-wrap items-center gap-2 lg:justify-end"
          data-print="hide"
          data-testid={deriveTestId('list', modelCode, 'toolbar')}
        >
          {isTenantMemberPage && onInvite && (
            <button
              type="button"
              data-testid="invite-section"
              onClick={onInvite}
              className="rounded-control inline-flex items-center gap-1.5 bg-emerald-600 px-3 py-1.5 text-sm font-medium text-white shadow-sm transition-colors duration-150 hover:bg-emerald-700"
            >
              Invite
            </button>
          )}
          {isTenantMemberPage && onImportMembers && (
            <button
              type="button"
              data-testid="member-import-entry"
              onClick={onImportMembers}
              className="rounded-control bg-accent hover:bg-accent-hover inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white shadow-sm transition-colors duration-150"
            >
              Import Members
            </button>
          )}
          <ToolbarActionGroup
            buttons={buttons}
            toolbarActions={toolbarActions}
            onAction={onAction}
            onConfigChange={onToolbarConfigChange}
            resolveLabel={resolveLabel}
            evaluateVisible={evaluateVisible}
            onImport={onImport}
            onExport={onExport}
            modelCode={modelCode}
            filters={exportFilters}
            hideBuiltInImport={hideBuiltInImport ?? isTenantMemberPage}
            hideBuiltInExport={hideBuiltInExport}
            hideBuiltInPrint={hideBuiltInPrint}
          />
        </div>
      </div>
    </div>
  );
};

export default ListPageHeader;
