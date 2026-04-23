/**
 * ListPageHeader — Extracted header section from ListPageContent.
 *
 * Contains the page title, ViewSelector, and ToolbarActionGroup (which
 * replaces the flat button rendering + ToolbarMoreMenu).
 */

import React from 'react';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import type {
  ToolbarActionConfig,
  SavedView,
  ViewType,
} from '~/framework/smart/types/savedView';
import { ViewSelector } from '~/framework/smart/components/view/ViewSelector';
import { ToolbarActionGroup } from './ToolbarActionGroup';
import { deriveTestId } from '~/framework/meta/rendering/utils/deriveTestId';

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
    <div className="border-b border-gray-200 px-6 py-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h2 className="text-lg font-semibold text-gray-900">{title}</h2>
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
        </div>
        <div
          className="print-hide flex items-center gap-2"
          data-print="hide"
          data-testid={deriveTestId('list', modelCode, 'toolbar')}
        >
          {isTenantMemberPage && onInvite && (
            <button
              type="button"
              data-testid="invite-section"
              onClick={onInvite}
              className="inline-flex items-center gap-1.5 rounded-md bg-emerald-600 px-3 py-1.5 text-sm font-medium text-white shadow-sm transition-colors duration-150 hover:bg-emerald-700"
            >
              Invite
            </button>
          )}
          {isTenantMemberPage && onImportMembers && (
            <button
              type="button"
              data-testid="member-import-entry"
              onClick={onImportMembers}
              className="inline-flex items-center gap-1.5 rounded-md bg-blue-600 px-3 py-1.5 text-sm font-medium text-white shadow-sm transition-colors duration-150 hover:bg-blue-700"
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
