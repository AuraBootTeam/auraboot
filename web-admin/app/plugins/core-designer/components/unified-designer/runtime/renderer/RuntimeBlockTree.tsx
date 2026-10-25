import { resolveFormPresentation } from '~/framework/meta/rendering/pages/formPage/formPresentation';
import { FormSection } from '~/framework/meta/rendering/pages/form/FormSection';
import { getCustomBlockRenderer } from '../customBlockRendererRegistry';
import { DesignerPageModelCodeContext, PreviewListTable } from '../platformTablePreview';
import { DESIGNER_I18N } from '~/shared/designer';
import React from 'react';
import { RuntimeAction } from './RuntimeActions';
import {
  RuntimeActivityTimeline,
  RuntimeAiFillBanner,
  RuntimeBpmPanel,
  RuntimeFieldHistory,
} from './RuntimeAssistance';
import {
  RuntimeChartPreview,
  RuntimeDescriptionPreview,
  RuntimeDividerPreview,
  RuntimeEmbeddedListPreview,
  RuntimeFiltersPreview,
  RuntimeFormWizardPreview,
  RuntimeGerberViewerPreview,
  RuntimeRecordCommentsPreview,
  RuntimeRichTextPreview,
  RuntimeSelectionInfoPreview,
  RuntimeStatCardPreview,
  RuntimeToolbarPreview,
  RuntimeTraceGraphPreview,
} from './RuntimeContentPreviews';
import { RuntimeField } from './RuntimeFieldControls';
import { RuntimeRepeater, RuntimeSubform } from './RuntimeNestedForms';
import { RuntimeColumn, RuntimeTitle } from './RuntimePrimitives';
import { RuntimeWidget } from './RuntimeWidgets';
import {
  RuntimeArtifactTimelinePreview,
  RuntimeCandidateListPreview,
  RuntimeEvidencePanelPreview,
  RuntimeMetricStripPreview,
  RuntimeReviewDrawerPreview,
  RuntimeStatusBannerPreview,
  RuntimeWorkbenchActionBarPreview,
} from './RuntimeWorkbenchPreviews';
import {
  RuntimeFormValueContext,
  RuntimeInitialFormValuesContext,
  RuntimeListSelectionContext,
  RuntimeLocaleContext,
  RuntimeModelFieldsContext,
  RuntimePermissionContext,
  useRuntimeText,
  type RuntimeBlockProps,
  type RuntimeFormContextValue,
  type RuntimeSelectionContextValue,
} from './runtimeContexts';
import {
  collectRuntimeFilterFields,
  collectRuntimeNestedFormValidationErrors,
  collectRuntimeVisibleFormFields,
  isRuntimeBlockVisible,
  isRuntimeFieldValueEmpty,
  validateRuntimeFormField,
} from './runtimeFormLogic';
import {
  formatRuntimeCell,
  getBlockLabel,
  getGridNumber,
  getRawArray,
  getSpanGridStyle,
  getStringProp,
  isRuntimeBlockPermissionAllowed,
  readLocalizedLabel,
} from './runtimePresentation';
import {
  applyRuntimeListFilters,
  getRuntimeRowId,
  getRuntimeTableColumnBlocks,
  getRuntimeTableColumns,
  getRuntimeTableRowActionBlocks,
  getRuntimeTableRows,
} from './runtimeTableLogic';





const FormPresentationContext = React.createContext(resolveFormPresentation());

export function RuntimeBlock({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  switch (block.blockType) {
    case 'form':
      return (
        <RuntimeForm
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'form-section':
    case 'detail-section':
      return (
        <RuntimeSection
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'field':
    case 'filter-field':
      return (
        <RuntimeField
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'list':
      return (
        <RuntimeList
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'table':
    case 'sub-table':
      return (
        <RuntimeTable
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'repeater':
      return (
        <RuntimeRepeater
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'subform':
      return (
        <RuntimeSubform
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'column':
      return <RuntimeColumn block={block} />;
    case 'action-bar':
      return (
        <RuntimeActionBar
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'action':
      return (
        <RuntimeAction
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'dashboard':
      return (
        <RuntimeDashboard
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'widget':
      return (
        <RuntimeWidget
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'tabs':
      return (
        <RuntimeTabs
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'ai-fill-banner':
      return (
        <RuntimeAiFillBanner
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'workflow-panel':
      return (
        <RuntimeBpmPanel
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'activity-timeline':
      return (
        <RuntimeActivityTimeline
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'field-history':
      return (
        <RuntimeFieldHistory
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'metric-strip':
      return <RuntimeMetricStripPreview block={block} />;
    case 'status-banner':
      return <RuntimeStatusBannerPreview block={block} />;
    case 'workbench-action-bar':
      return <RuntimeWorkbenchActionBarPreview block={block} />;
    case 'review-drawer':
      return <RuntimeReviewDrawerPreview block={block} />;
    case 'evidence-panel':
      return <RuntimeEvidencePanelPreview block={block} />;
    case 'record-inspector':
      return (
        <RuntimeRecordInspectorPreview
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    case 'candidate-list':
      return <RuntimeCandidateListPreview block={block} />;
    case 'artifact-timeline':
      return <RuntimeArtifactTimelinePreview block={block} />;
    case 'stat-card':
      return <RuntimeStatCardPreview block={block} />;
    case 'description':
      return <RuntimeDescriptionPreview block={block} />;
    case 'record-comments':
      return <RuntimeRecordCommentsPreview block={block} />;
    case 'embedded-list':
      return <RuntimeEmbeddedListPreview block={block} />;
    // E2 batch — non-family display / chart / graph / layout / form / list blocks.
    // Config-driven representative previews; the live /p/ page renders the real,
    // fully data-bound platform renderer (wired in ui/schema-renderer/BlockRegistry).
    case 'chart':
      return <RuntimeChartPreview block={block} />;
    case 'rich-text':
      return <RuntimeRichTextPreview block={block} />;
    case 'divider':
      return <RuntimeDividerPreview block={block} />;
    case 'toolbar':
      return <RuntimeToolbarPreview block={block} variant="toolbar" />;
    case 'form-buttons':
      return <RuntimeToolbarPreview block={block} variant="form-buttons" />;
    case 'filters':
      return <RuntimeFiltersPreview block={block} />;
    case 'form-wizard':
      return <RuntimeFormWizardPreview block={block} />;
    case 'trace-graph':
      return <RuntimeTraceGraphPreview block={block} />;
    case 'selection-info':
      return <RuntimeSelectionInfoPreview block={block} />;
    case 'gerber-viewer':
      return <RuntimeGerberViewerPreview block={block} />;
    default: {
      // Plugin-contributed custom blocks may register a runtime renderer
      // (e.g. AuraQR's scannability-qc live score). When present it fully
      // replaces the generic container; otherwise fall through so unknown /
      // layout blocks still render their title + children (zero regression).
      const CustomRenderer = getCustomBlockRenderer(block.blockType);
      if (CustomRenderer) {
        return <CustomRenderer block={block} />;
      }
      return (
        <RuntimeContainer
          block={block}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={blockPath}
        />
      );
    }
  }
}

function RuntimeTabs({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  const tabs = block.blocks?.filter((child) => child.blockType === 'tab') ?? [];
  const [activeTabId, setActiveTabId] = React.useState(() => tabs[0]?.id ?? null);
  const resolvedActiveTabId = tabs.some((tab) => tab.id === activeTabId)
    ? activeTabId
    : tabs[0]?.id ?? null;
  const activeTab = tabs.find((tab) => tab.id === resolvedActiveTabId) ?? null;

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-block-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      <div
        className="mt-3 flex flex-wrap gap-2 border-b border-slate-200"
        data-testid={`runtime-tabs-${block.id}`}
      >
        {tabs.map((tab) => {
          const active = tab.id === resolvedActiveTabId;
          return (
            <button
              key={tab.id}
              type="button"
              data-testid={`runtime-tab-trigger-${tab.id}`}
              aria-selected={active}
              onClick={() => setActiveTabId(tab.id)}
              className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
                active
                  ? 'border-blue-600 text-blue-700'
                  : 'border-transparent text-slate-500 hover:border-slate-300 hover:text-slate-700'
              }`}
            >
              {getBlockLabel(tab, locale)}
            </button>
          );
        })}
      </div>
      {activeTab ? (
        <div className="mt-3" data-testid={`runtime-tab-panel-${activeTab.id}`}>
          <RuntimeBlock
            block={activeTab}
            runtimeServices={runtimeServices}
            pageContext={pageContext}
            blockPath={[...blockPath, activeTab.id]}
          />
        </div>
      ) : (
        <div
          className="mt-3 rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-tabs-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.noTabsConfigured)}
        </div>
      )}
    </section>
  );
}

function RuntimeRecordInspectorPreview({
  block,
  runtimeServices,
  pageContext,
  blockPath,
}: RuntimeBlockProps) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  const fields = getRawArray(block, 'fields');
  const childBlocks = block.blocks ?? [];

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-record-inspector-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {fields.length === 0 && childBlocks.length === 0 ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-record-inspector-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.noInspectorFields)}
        </div>
      ) : (
        <>
          {fields.length > 0 && (
            <dl
              className="grid gap-3 sm:grid-cols-2"
              data-testid={`runtime-record-inspector-fields-${block.id}`}
            >
              {fields.map((field, index) => {
                const key =
                  getStringProp(field.field) || getStringProp(field.path) || String(index);
                const label = readLocalizedLabel(field.label, locale) || key;
                const fullWidth = field.span === 2;
                return (
                  <div
                    key={key}
                    className={fullWidth ? 'sm:col-span-2' : undefined}
                    data-testid={`runtime-record-inspector-field-${key}`}
                  >
                    <dt className="text-xs font-medium text-slate-500">{label}</dt>
                    <dd className="mt-1 min-h-5 text-sm text-slate-700">
                      {t(DESIGNER_I18N.unified.runtime.metricPlaceholderValue)}
                    </dd>
                  </div>
                );
              })}
            </dl>
          )}
          {childBlocks.length > 0 && (
            <div className="mt-3 space-y-3">
              {childBlocks.map((child) => (
                <RuntimeBlock
                  key={child.id}
                  block={child}
                  runtimeServices={runtimeServices}
                  pageContext={pageContext}
                  blockPath={[...blockPath, child.id]}
                />
              ))}
            </div>
          )}
        </>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-record-inspector-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeForm({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const hasPermission = React.useContext(RuntimePermissionContext);
  const initialFormValues = React.useContext(RuntimeInitialFormValuesContext);
  const [values, setValues] = React.useState<Record<string, unknown>>(() => ({
    ...initialFormValues,
  }));
  const [errors, setErrors] = React.useState<Record<string, string>>({});
  const validate = React.useCallback(() => {
    const formFields = collectRuntimeVisibleFormFields(block, values, hasPermission);
    const nextErrors = Object.fromEntries([
      ...formFields.flatMap((fieldBlock) => {
        const fieldKey = fieldBlock.field || fieldBlock.id;
        const error = validateRuntimeFormField(fieldBlock, values[fieldKey]);
        return error ? [[fieldKey, error]] : [];
      }),
      ...collectRuntimeNestedFormValidationErrors(block, values, hasPermission),
    ]);
    setErrors(nextErrors);
    return Object.keys(nextErrors).length === 0;
  }, [block, hasPermission, values]);
  const formContext = React.useMemo<RuntimeFormContextValue>(
    () => ({
      values,
      errors,
      setValue: (field, value) => {
        setValues((current) => ({
          ...current,
          [field]: value,
        }));
        setErrors((current) => {
          const nestedPrefix = `${field}.`;
          if (!current[field] && !Object.keys(current).some((key) => key.startsWith(nestedPrefix))) {
            return current;
          }
          const next = { ...current };
          delete next[field];
          for (const key of Object.keys(next)) {
            if (key.startsWith(nestedPrefix)) delete next[key];
          }
          return next;
        });
      },
      validate,
    }),
    [errors, validate, values],
  );

  return (
    <FormPresentationContext.Provider value={resolveFormPresentation(block.extension)}>
    <RuntimeFormValueContext.Provider value={formContext}>
      <RuntimeContainer
        block={block}
        className="space-y-3"
        runtimeServices={runtimeServices}
        pageContext={pageContext}
        blockPath={blockPath}
      />
    </RuntimeFormValueContext.Provider>
    </FormPresentationContext.Provider>
  );
}

function RuntimeList({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const hasPermission = React.useContext(RuntimePermissionContext);
  const [selectedById, setSelectedById] = React.useState<Record<string, Record<string, unknown>>>(
    {},
  );
  const [filterValues, setFilterValues] = React.useState<Record<string, unknown>>({});
  const filterBlocks = React.useMemo(
    () => collectRuntimeFilterFields(block, hasPermission),
    [block, hasPermission],
  );
  const selectionContext = React.useMemo<RuntimeSelectionContextValue>(() => {
    const selectedRowIds = Object.keys(selectedById);
    return {
      selectedRows: selectedRowIds.flatMap((rowId) =>
        selectedById[rowId] ? [selectedById[rowId]] : [],
      ),
      selectedRowIds,
      filterBlocks,
      filterValues,
      setFilterValue: (field, value) => {
        setFilterValues((current) => {
          const next = { ...current };
          if (isRuntimeFieldValueEmpty(value)) {
            delete next[field];
          } else {
            next[field] = value;
          }
          return next;
        });
      },
      toggleRow: (rowId, row) => {
        setSelectedById((current) => {
          if (current[rowId]) {
            const next = { ...current };
            delete next[rowId];
            return next;
          }
          return {
            ...current,
            [rowId]: row,
          };
        });
      },
      isSelected: (rowId) => Boolean(selectedById[rowId]),
    };
  }, [filterBlocks, filterValues, selectedById]);

  return (
    <RuntimeListSelectionContext.Provider value={selectionContext}>
      <RuntimeContainer
        block={block}
        className="space-y-3"
        runtimeServices={runtimeServices}
        pageContext={pageContext}
        blockPath={blockPath}
      />
    </RuntimeListSelectionContext.Provider>
  );
}

function RuntimeContainer({
  block,
  className = '',
  runtimeServices,
  pageContext,
  blockPath,
}: RuntimeBlockProps & { className?: string }) {
  return (
    <section
      className={`rounded-lg border border-slate-200 bg-white p-4 ${className}`}
      data-testid={`runtime-block-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {block.blocks?.map((child) => (
        <RuntimeBlock
          key={child.id}
          block={child}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={[...blockPath, child.id]}
        />
      ))}
    </section>
  );
}

function RuntimeSection({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const presentation = React.useContext(FormPresentationContext);
  const formContext = React.useContext(RuntimeFormValueContext);
  const locale = React.useContext(RuntimeLocaleContext);
  if (!isRuntimeBlockVisible(block, formContext?.values)) return null;

  return (
    <FormSection
      style={getSpanGridStyle(block)}
      title={readLocalizedLabel(block.title, locale)}
      surface={presentation.surface}
      density={presentation.density}
      collapsible={Boolean(block.props?.collapsible ?? block.extension?.collapsible)}
      defaultCollapsed={Boolean(block.props?.defaultCollapsed ?? block.extension?.defaultCollapsed)}
      testId={`runtime-block-${block.id}`}
    >
      <div className="grid grid-cols-12 gap-3">
        {block.blocks?.map((child) => (
          <RuntimeBlock
            key={child.id}
            block={child.layout?.span === undefined && child.layout?.colSpan === undefined ? { ...child, layout: { ...child.layout, span: 12 / (Number(block.layout?.columns ?? block.props?.columns) || presentation.columns) } } : child}
            runtimeServices={runtimeServices}
            pageContext={pageContext}
            blockPath={[...blockPath, child.id]}
          />
        ))}
      </div>
    </FormSection>
  );
}

function RuntimeTable({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const selectionContext = React.useContext(RuntimeListSelectionContext);
  const hasPermission = React.useContext(RuntimePermissionContext);
  const modelFields = React.useContext(RuntimeModelFieldsContext);
  const pageModelCode = React.useContext(DesignerPageModelCodeContext);
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  const rows = applyRuntimeListFilters(getRuntimeTableRows(block), selectionContext);
  const configuredColumnBlocks = getRuntimeTableColumnBlocks(block);
  const columnBlocks = configuredColumnBlocks.filter((child) =>
    isRuntimeBlockPermissionAllowed(child, hasPermission),
  );
  const rowActionBlocks = getRuntimeTableRowActionBlocks(block);
  const columns = getRuntimeTableColumns(block, rows, columnBlocks, configuredColumnBlocks.length > 0);

  // True WYSIWYG: for a page `table` with real columns and no author-supplied
  // representative rows, render the live data table (RecordListView fetches a small page
  // and resolves dict/reference/typed cells). Gated on model metadata being present, so
  // the schematic path (and its tests, which pass no modelFields) is unaffected.
  if (
    block.blockType === 'table' &&
    modelFields.length > 0 &&
    Boolean(pageModelCode) &&
    configuredColumnBlocks.length > 0 &&
    getRuntimeTableRows(block).length === 0
  ) {
    return (
      <div data-testid={`runtime-block-${block.id}`}>
        <PreviewListTable
          modelCode={pageModelCode}
          tableBlock={block}
          modelFields={modelFields}
          locale={locale}
        />
      </div>
    );
  }

  return (
    <div className="rounded-md border border-slate-200" data-testid={`runtime-block-${block.id}`}>
      {block.title ? (
        <div className="border-b border-slate-200 px-3 py-2 text-sm font-semibold text-slate-900">
          {getBlockLabel(block, locale)}
        </div>
      ) : null}
      <div className="grid grid-cols-2 border-b border-slate-200 bg-slate-50">
        {columnBlocks.map((child) => (
          <RuntimeBlock
            key={child.id}
            block={child}
            runtimeServices={runtimeServices}
            pageContext={pageContext}
            blockPath={[...blockPath, child.id]}
          />
        ))}
        {rowActionBlocks.length ? (
          <div
            className="border-r border-slate-200 px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-500 last:border-r-0"
            data-testid={`runtime-row-actions-header-${block.id}`}
          />
        ) : null}
      </div>
      {rows.length ? (
        <table className="w-full text-left text-sm" data-testid={`runtime-table-${block.id}`}>
          <tbody>
            {rows.map((row, index) => {
              const rowId = getRuntimeRowId(row, index);
              return (
                <tr
                  key={rowId}
                  className="border-t border-slate-100"
                  data-testid={`runtime-table-row-${block.id}-${index}`}
                >
                  {selectionContext ? (
                    <td className="w-10 px-3 py-2">
                      <input
                        type="checkbox"
                        data-testid={`runtime-row-select-${block.id}-${index}`}
                        checked={selectionContext.isSelected(rowId)}
                        onChange={() => selectionContext.toggleRow(rowId, row)}
                      />
                    </td>
                  ) : null}
                  {columns.map((column) => (
                    <td
                      key={column}
                      className="px-3 py-2 text-slate-700"
                      data-testid={`runtime-table-cell-${block.id}-${index}-${column}`}
                    >
                      {formatRuntimeCell(row[column])}
                    </td>
                  ))}
                  {rowActionBlocks.length ? (
                    <td className="px-3 py-2 text-right">
                      <div className="inline-flex flex-wrap justify-end gap-2">
                        {rowActionBlocks.map((actionBlock) => (
                          <RuntimeAction
                            key={actionBlock.id}
                            block={actionBlock}
                            runtimeServices={runtimeServices}
                            pageContext={pageContext}
                            blockPath={[...blockPath, actionBlock.id]}
                            currentRow={row}
                            currentRowId={rowId}
                            testIds={{
                              wrapper: `runtime-row-action-wrapper-${block.id}-${actionBlock.id}-${index}`,
                              button: `runtime-row-action-${block.id}-${actionBlock.id}-${index}`,
                              confirm: `runtime-row-action-confirm-${block.id}-${actionBlock.id}-${index}`,
                              status: `runtime-row-action-status-${block.id}-${actionBlock.id}-${index}`,
                              error: `runtime-row-action-error-${block.id}-${actionBlock.id}-${index}`,
                              errorHint: `runtime-row-action-error-hint-${block.id}-${actionBlock.id}-${index}`,
                              overlay: `runtime-row-action-overlay-${block.id}-${actionBlock.id}-${index}`,
                            }}
                          />
                        ))}
                      </div>
                    </td>
                  ) : null}
                </tr>
              );
            })}
          </tbody>
        </table>
      ) : (
        <div className="px-3 py-4 text-sm text-slate-400">
          {t(DESIGNER_I18N.unified.runtime.noRecords)}
        </div>
      )}
    </div>
  );
}

function RuntimeActionBar({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  return (
    <div className="flex gap-2" data-testid={`runtime-block-${block.id}`}>
      {block.blocks?.map((child) => (
        <RuntimeBlock
          key={child.id}
          block={child}
          runtimeServices={runtimeServices}
          pageContext={pageContext}
          blockPath={[...blockPath, child.id]}
        />
      ))}
    </div>
  );
}

function RuntimeDashboard({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const cols = getGridNumber(block.layout?.cols, 12);
  const rowHeight = getGridNumber(block.layout?.rowHeight, 80);
  const gap = getGridNumber(block.layout?.gap, 12);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-block-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      <div
        className="grid"
        data-testid={`runtime-dashboard-grid-${block.id}`}
        style={{
          gap,
          gridAutoRows: `${rowHeight}px`,
          gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))`,
        }}
      >
        {block.blocks?.map((child) => (
          <RuntimeBlock
            key={child.id}
            block={child}
            runtimeServices={runtimeServices}
            pageContext={pageContext}
            blockPath={[...blockPath, child.id]}
          />
        ))}
      </div>
    </section>
  );
}
