import React from 'react';
import { usePermissions } from '~/contexts/AuthContext';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import { getChartComponent, normalizeChartType } from '~/framework/smart/charts/SharedChartFactory';
import { ControlledFieldRenderer } from '~/framework/meta/rendering/ControlledFieldRenderer';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { DataSourceProvider } from '~/framework/meta/contexts/DataSourceContext';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { buildPreviewFieldConfig } from './platformFieldPreview';
import { DesignerPageModelCodeContext, PreviewListTable } from './platformTablePreview';
import type { DslBlockV3, ModelFieldDefinition, PageSchemaV3 } from '../types';
import { getCustomBlockRenderer } from './customBlockRendererRegistry';
import {
  normalizeRuntimeExecutionError,
  type RuntimeExecutionContext,
  type RuntimeExecutionIssue,
  type RuntimeHelperBlockData,
  type RuntimePickerOption,
  type RuntimeExecutionServices,
  type RuntimeWidgetData,
} from './runtimeExecution';

interface RecursiveBlockRendererProps {
  schema: PageSchemaV3;
  runtimeServices?: RuntimeExecutionServices;
  permissionEvaluator?: (permissionCode: string) => boolean;
  /**
   * Model field metadata for the page's primary model. When provided, form `field`
   * blocks render the real platform control (true WYSIWYG) via {@link RuntimePlatformField}
   * instead of the generic representative input. Absent (default) → legacy preview,
   * so existing callers/tests are unaffected.
   */
  modelFields?: ModelFieldDefinition[];
}

export function RecursiveBlockRenderer({
  schema,
  runtimeServices,
  permissionEvaluator,
  modelFields,
}: RecursiveBlockRendererProps) {
  const { hasPermission } = usePermissions();
  const { locale } = useI18n();
  const evaluatePermission = permissionEvaluator ?? hasPermission;
  const modelFieldList = modelFields ?? EMPTY_MODEL_FIELDS;
  // WYSIWYG is active only when model metadata is supplied (workbench preview). In that
  // mode the real platform controls (SmartSelect/pickers) call useFieldDataSource, which
  // hard-requires a DataSourceManager in context — so we provide a lightweight one.
  const wysiwygActive = modelFieldList.length > 0;
  const dataSourceManager = React.useMemo(
    () => new DataSourceManager(createExpressionContext({ locale })),
    [locale],
  );
  const pageContext: RuntimePageContext = {
    source: 'unified-designer-runtime-preview',
    pageId: schema.id,
    pageKind: schema.kind,
    schemaVersion: schema.schemaVersion,
    routeQuery: getCurrentRouteQuery(),
  };

  const tree = (
    <RuntimeLocaleContext.Provider value={locale}>
      <RuntimeModelFieldsContext.Provider value={modelFieldList}>
        <DesignerPageModelCodeContext.Provider value={schema.modelCode}>
        <RuntimePermissionContext.Provider value={evaluatePermission}>
          <div
            className="grid grid-cols-12 gap-4"
            data-testid={`runtime-page-${schema.id}`}
            data-schema-version={schema.schemaVersion}
          >
            {schema.blocks.map((block) => (
              <RuntimeBlock
                key={block.id}
                block={block}
                runtimeServices={runtimeServices}
                pageContext={pageContext}
                blockPath={[block.id]}
              />
            ))}
          </div>
        </RuntimePermissionContext.Provider>
        </DesignerPageModelCodeContext.Provider>
      </RuntimeModelFieldsContext.Provider>
    </RuntimeLocaleContext.Provider>
  );

  return wysiwygActive ? (
    <DataSourceProvider manager={dataSourceManager}>{tree}</DataSourceProvider>
  ) : (
    tree
  );
}

const RuntimeLocaleContext = React.createContext<string>('zh-CN');

/** Resolve a runtime preview i18n string for the current locale. */
function useRuntimeText() {
  const locale = React.useContext(RuntimeLocaleContext);
  return (entry: Record<string, string>) => resolveDesignerText(entry, locale);
}

type RuntimePageContext = Pick<
  RuntimeExecutionContext,
  'source' | 'pageId' | 'pageKind' | 'schemaVersion' | 'routeQuery'
>;

interface RuntimeFormContextValue {
  values: Record<string, unknown>;
  errors: Record<string, string>;
  setValue: (field: string, value: unknown) => void;
  validate: () => boolean;
}

const RuntimeFormValueContext = React.createContext<RuntimeFormContextValue | null>(null);

interface RuntimeSelectionContextValue {
  selectedRows: Array<Record<string, unknown>>;
  selectedRowIds: string[];
  filterBlocks: DslBlockV3[];
  filterValues: Record<string, unknown>;
  setFilterValue: (field: string, value: unknown) => void;
  toggleRow: (rowId: string, row: Record<string, unknown>) => void;
  isSelected: (rowId: string) => boolean;
}

const RuntimeListSelectionContext = React.createContext<RuntimeSelectionContextValue | null>(null);
const RuntimePermissionContext = React.createContext<(permissionCode: string) => boolean>(
  () => false,
);

/** Stable empty reference so the default context value never triggers re-renders. */
const EMPTY_MODEL_FIELDS: ModelFieldDefinition[] = [];
/**
 * Model field metadata for the page's primary model, provided by the workbench preview.
 * Consumed by {@link RuntimeField} to render the real platform control for `field` blocks.
 */
const RuntimeModelFieldsContext =
  React.createContext<ModelFieldDefinition[]>(EMPTY_MODEL_FIELDS);

interface RuntimeBlockProps {
  block: DslBlockV3;
  runtimeServices?: RuntimeExecutionServices;
  pageContext: RuntimePageContext;
  blockPath: string[];
}

function RuntimeBlock({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
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
    case 'bpm-panel':
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
              {getBlockLabel(tab)}
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

function RuntimeAiFillBanner({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
  const t = useRuntimeText();
  const [applied, setApplied] = React.useState(false);
  const { runtimeData, loading, runtimeError, permissionCode, permissionAllowed } =
    useRuntimeHelperBlockData(
      block,
      runtimeServices,
      pageContext,
      blockPath,
    );
  const description =
    getStringProp(runtimeData?.description) ||
    getStringProp(block.props?.description) ||
    t(DESIGNER_I18N.unified.runtime.aiReviewHint);
  const feedback =
    getStringProp(runtimeData?.feedback) ||
    getStringProp(block.props?.feedback) ||
    t(DESIGNER_I18N.unified.runtime.suggestionsApplied);
  const suggestedFields = getAiSuggestedFields(
    runtimeData?.suggestedFields ?? block.props?.suggestedFields ?? block.props?.fields,
  );
  const emptyText =
    getStringProp(runtimeData?.emptyText) ||
    getStringProp(block.props?.emptyText) ||
    t(DESIGNER_I18N.unified.runtime.noSuggestions);
  const applySuggestions = () => {
    suggestedFields.forEach((field) => {
      if (field.field) {
        formContext?.setValue(field.field, field.value);
      }
    });
    setApplied(true);
  };

  return (
    <section
      className="rounded-lg border border-blue-200 bg-blue-50 p-4"
      data-testid={`runtime-ai-fill-banner-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <RuntimeTitle block={block} />
          <p
            className="text-sm text-blue-800"
            data-testid={`runtime-ai-fill-description-${block.id}`}
          >
            {description}
          </p>
          <RuntimeHelperDataStatus
            blockId={block.id}
            data={runtimeData}
            loading={loading}
            error={runtimeError}
            permissionCode={permissionCode}
            permissionAllowed={permissionAllowed}
          />
        </div>
        <button
          type="button"
          className="rounded-md border border-blue-300 bg-white px-3 py-1.5 text-sm font-medium text-blue-700 hover:bg-blue-100"
          data-testid={`runtime-ai-fill-apply-${block.id}`}
          onClick={applySuggestions}
        >
          Apply suggestions
        </button>
      </div>
      {suggestedFields.length ? (
        <div className="mt-3 grid gap-2 sm:grid-cols-2">
          {suggestedFields.map((field, index) => (
            <div
              key={`${field.field}-${index}`}
              className="rounded-md border border-blue-100 bg-white px-3 py-2 text-sm"
              data-testid={`runtime-ai-fill-field-${block.id}-${index}`}
            >
              <div className="font-medium text-slate-800">{field.label || field.field}</div>
              <div className="mt-1 text-slate-500">{field.value}</div>
            </div>
          ))}
        </div>
      ) : !loading && !runtimeError ? (
        <div
          className="mt-3 rounded-md border border-dashed border-blue-200 bg-white/70 px-3 py-3 text-sm text-blue-500"
          data-testid={`runtime-ai-fill-empty-${block.id}`}
        >
          {emptyText}
        </div>
      ) : null}
      {applied ? (
        <div className="mt-3 text-sm text-blue-700" data-testid={`runtime-ai-fill-status-${block.id}`}>
          {feedback}
        </div>
      ) : null}
    </section>
  );
}

function RuntimeBpmPanel({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const t = useRuntimeText();
  const { runtimeData, loading, runtimeError, permissionCode, permissionAllowed } =
    useRuntimeHelperBlockData(
      block,
      runtimeServices,
      pageContext,
      blockPath,
    );
  const hasRuntimeData = runtimeData !== null;
  const status =
    getStringProp(runtimeData?.status) ||
    (hasRuntimeData ? '' : getStringProp(block.props?.status) || 'draft');
  const description =
    getStringProp(runtimeData?.description) ||
    (hasRuntimeData ? '' : getStringProp(block.props?.description));
  const assignee =
    getStringProp(runtimeData?.assignee) ||
    (hasRuntimeData ? '' : getStringProp(block.props?.assignee));
  const dueAt =
    getStringProp(runtimeData?.dueAt) || (hasRuntimeData ? '' : getStringProp(block.props?.dueAt));
  const actions = getBpmActions(runtimeData?.actions ?? block.props?.actions);
  const emptyText =
    getStringProp(runtimeData?.emptyText) ||
    getStringProp(block.props?.emptyText) ||
    t(DESIGNER_I18N.unified.runtime.noWorkflowTasks);
  const hasBpmContent = Boolean(status || description || assignee || dueAt || actions.length);

  return (
    <section
      className="rounded-lg border border-indigo-200 bg-white p-4"
      data-testid={`runtime-bpm-panel-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <RuntimeTitle block={block} />
          {description ? <p className="text-sm text-slate-500">{description}</p> : null}
          <RuntimeHelperDataStatus
            blockId={block.id}
            data={runtimeData}
            loading={loading}
            error={runtimeError}
            permissionCode={permissionCode}
            permissionAllowed={permissionAllowed}
          />
        </div>
        {status ? (
          <span
            className="rounded-full bg-indigo-50 px-2.5 py-1 text-xs font-semibold uppercase tracking-wide text-indigo-700"
            data-testid={`runtime-bpm-status-${block.id}`}
          >
            {status}
          </span>
        ) : null}
      </div>
      <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
        {assignee ? (
          <div data-testid={`runtime-bpm-assignee-${block.id}`}>
            <dt className="text-xs font-medium uppercase text-slate-400">
              {t(DESIGNER_I18N.unified.runtime.assignee)}
            </dt>
            <dd className="text-slate-800">{assignee}</dd>
          </div>
        ) : null}
        {dueAt ? (
          <div data-testid={`runtime-bpm-due-${block.id}`}>
            <dt className="text-xs font-medium uppercase text-slate-400">
              {t(DESIGNER_I18N.unified.runtime.due)}
            </dt>
            <dd className="text-slate-800">{dueAt}</dd>
          </div>
        ) : null}
      </dl>
      {actions.length ? (
        <div className="mt-4 flex flex-wrap gap-2">
          {actions.map((action, index) => (
            <button
              key={`${action.label}-${index}`}
              type="button"
              className="rounded-md border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-700"
              data-testid={`runtime-bpm-action-${block.id}-${index}`}
            >
              {action.label}
            </button>
          ))}
        </div>
      ) : null}
      {!hasBpmContent && !loading && !runtimeError ? (
        <div
          className="mt-3 rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-bpm-empty-${block.id}`}
        >
          {emptyText}
        </div>
      ) : null}
    </section>
  );
}

function RuntimeActivityTimeline({
  block,
  runtimeServices,
  pageContext,
  blockPath,
}: RuntimeBlockProps) {
  const t = useRuntimeText();
  const { runtimeData, loading, runtimeError, permissionCode, permissionAllowed } =
    useRuntimeHelperBlockData(
      block,
      runtimeServices,
      pageContext,
      blockPath,
    );
  const items = getTimelineItems(runtimeData?.items ?? block.props?.items);
  const emptyText =
    getStringProp(runtimeData?.emptyText) ||
    getStringProp(block.props?.emptyText) ||
    t(DESIGNER_I18N.unified.runtime.noActivity);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-activity-timeline-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      <RuntimeHelperDataStatus
        blockId={block.id}
        data={runtimeData}
        loading={loading}
        error={runtimeError}
        permissionCode={permissionCode}
        permissionAllowed={permissionAllowed}
      />
      {items.length ? (
        <ol className="space-y-3">
          {items.map((item, index) => (
            <li
              key={`${item.action}-${index}`}
              className="border-l-2 border-slate-200 pl-3"
              data-testid={`runtime-activity-item-${block.id}-${index}`}
            >
              <div className="text-sm font-medium text-slate-800">
                {item.actor ? `${item.actor} · ` : ''}
                {item.action}
              </div>
              {item.time ? <div className="text-xs text-slate-400">{item.time}</div> : null}
              {item.description ? (
                <div className="mt-1 text-sm text-slate-500">{item.description}</div>
              ) : null}
            </li>
          ))}
        </ol>
      ) : (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-activity-empty-${block.id}`}
        >
          {emptyText}
        </div>
      )}
    </section>
  );
}

function RuntimeFieldHistory({
  block,
  runtimeServices,
  pageContext,
  blockPath,
}: RuntimeBlockProps) {
  const t = useRuntimeText();
  const { runtimeData, loading, runtimeError, permissionCode, permissionAllowed } =
    useRuntimeHelperBlockData(
      block,
      runtimeServices,
      pageContext,
      blockPath,
    );
  const entries = getFieldHistoryEntries(runtimeData?.entries ?? block.props?.entries);
  const emptyText =
    getStringProp(runtimeData?.emptyText) ||
    getStringProp(block.props?.emptyText) ||
    t(DESIGNER_I18N.unified.runtime.noFieldChanges);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-field-history-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      <RuntimeHelperDataStatus
        blockId={block.id}
        data={runtimeData}
        loading={loading}
        error={runtimeError}
        permissionCode={permissionCode}
        permissionAllowed={permissionAllowed}
      />
      {entries.length ? (
        <div className="overflow-hidden rounded-md border border-slate-200">
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase text-slate-500">
              <tr>
                <th className="px-3 py-2">{t(DESIGNER_I18N.unified.runtime.field)}</th>
                <th className="px-3 py-2">{t(DESIGNER_I18N.unified.runtime.from)}</th>
                <th className="px-3 py-2">{t(DESIGNER_I18N.unified.runtime.to)}</th>
                <th className="px-3 py-2">{t(DESIGNER_I18N.unified.runtime.by)}</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry, index) => (
                <tr
                  key={`${entry.field}-${index}`}
                  className="border-t border-slate-100"
                  data-testid={`runtime-field-history-entry-${block.id}-${index}`}
                >
                  <td className="px-3 py-2 font-medium text-slate-800">{entry.field}</td>
                  <td className="px-3 py-2 text-slate-500">{entry.from}</td>
                  <td className="px-3 py-2 text-slate-800">{entry.to}</td>
                  <td className="px-3 py-2 text-slate-500">{entry.changedBy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-field-history-empty-${block.id}`}
        >
          {emptyText}
        </div>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Workbench-block representative previews (metric-strip / status-banner).
//
// These blocks are fully data-bound by the platform meta-rendering renderers
// (framework/meta/rendering/blocks/MetricStripBlockRenderer +
// StatusBannerBlockRenderer) on the live /p/ page, which read a SchemaRuntime /
// dataSource / polling. The designer's own runtime (this file) is a separate
// model (DslBlockV3) without that runtime, so here we render a CONFIG-DRIVEN
// REPRESENTATIVE PREVIEW only: metric labels + placeholder values, and the tone /
// title status mapping. The complete data binding renders on the published page.
// (Deliberately NOT bridging the platform data-binding renderer into the designer
// runtime — see BlockRegistry / InspectorSchemaRegistry comments.)
// ---------------------------------------------------------------------------

// Token-backed tone utility classes shared with the platform renderers. No raw
// hex — these resolve through the Tailwind theme tokens.
const WORKBENCH_TONE_CARD_CLASS: Record<string, string> = {
  green: 'border-emerald-200 bg-emerald-50 text-emerald-900',
  amber: 'border-amber-200 bg-amber-50 text-amber-900',
  red: 'border-rose-200 bg-rose-50 text-rose-900',
  blue: 'border-blue-200 bg-blue-50 text-blue-900',
  purple: 'border-violet-200 bg-violet-50 text-violet-900',
  gray: 'border-slate-200 bg-white text-slate-900',
  default: 'border-slate-200 bg-white text-slate-900',
};

const WORKBENCH_TONE_DOT_CLASS: Record<string, string> = {
  green: 'bg-emerald-500',
  amber: 'bg-amber-500',
  red: 'bg-rose-500',
  blue: 'bg-blue-500',
  purple: 'bg-violet-500',
  gray: 'bg-slate-400',
  default: 'bg-slate-400',
};

function readLocalizedLabel(value: unknown, locale: string): string {
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const map = value as Record<string, string>;
    return (
      map[locale] || map['en-US'] || map.en || map['zh-CN'] || Object.values(map)[0] || ''
    );
  }
  return '';
}

function getMetricStripMetrics(block: DslBlockV3): Array<Record<string, unknown>> {
  const metrics = (block as unknown as Record<string, unknown>).metrics;
  if (!Array.isArray(metrics)) return [];
  return metrics.filter((metric): metric is Record<string, unknown> => isRecord(metric));
}

function RuntimeMetricStripPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  const metrics = getMetricStripMetrics(block);
  const variant = getStringProp((block as unknown as Record<string, unknown>).variant) || 'cards';
  const placeholder = t(DESIGNER_I18N.unified.runtime.metricPlaceholderValue);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-metric-strip-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {metrics.length === 0 ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-metric-strip-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.noMetricsConfigured)}
        </div>
      ) : (
        <div
          className={
            variant === 'chips'
              ? 'flex flex-wrap gap-2'
              : 'grid items-stretch gap-3 sm:grid-cols-2 lg:grid-cols-4'
          }
          data-testid={`runtime-metric-strip-grid-${block.id}`}
        >
          {metrics.map((metric, index) => {
            const key = getStringProp(metric.key) || getStringProp(metric.valueField) || String(index);
            const label = readLocalizedLabel(metric.label, locale) || key;
            const tone = getStringProp(metric.tone) || 'default';
            const toneClass = WORKBENCH_TONE_CARD_CLASS[tone] || WORKBENCH_TONE_CARD_CLASS.default;
            if (variant === 'chips') {
              return (
                <span
                  key={key}
                  data-testid={`runtime-metric-strip-item-${key}`}
                  className={`inline-flex min-h-9 items-center gap-2 rounded-full border px-3 py-1.5 text-sm ${toneClass}`}
                >
                  <span className="font-medium">{label}</span>
                  <span className="rounded-full bg-white/70 px-2 py-0.5 text-xs font-semibold">
                    {placeholder}
                  </span>
                </span>
              );
            }
            return (
              <div
                key={key}
                data-testid={`runtime-metric-strip-item-${key}`}
                className={`overflow-hidden rounded-md border p-3 text-left shadow-sm ${toneClass}`}
              >
                <div className="truncate text-xs font-medium" title={label}>
                  {label}
                </div>
                <div
                  className="mt-1 truncate text-2xl font-semibold"
                  data-testid={`runtime-metric-strip-value-${key}`}
                >
                  {placeholder}
                </div>
              </div>
            );
          })}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-metric-strip-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeStatusBannerPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  const raw = block as unknown as Record<string, unknown>;
  const toneMap = isRecord(raw.toneMap) ? raw.toneMap : {};
  const titleMap = isRecord(raw.titleMap) ? raw.titleMap : {};
  // Pick the first configured status to present as a representative banner; if
  // none configured we show the empty hint. We prefer a status that has a title.
  const statusKeys = Array.from(
    new Set([...Object.keys(titleMap), ...Object.keys(toneMap)].filter((key) => key !== '__default')),
  );
  const sampleStatus = statusKeys[0];
  const configured = Boolean(sampleStatus);
  const tone = configured ? getStringProp(toneMap[sampleStatus]) || 'gray' : 'gray';
  const toneClass = WORKBENCH_TONE_CARD_CLASS[tone] || WORKBENCH_TONE_CARD_CLASS.gray;
  const dotClass = WORKBENCH_TONE_DOT_CLASS[tone] || WORKBENCH_TONE_DOT_CLASS.gray;
  const title = configured
    ? readLocalizedLabel(titleMap[sampleStatus], locale) || sampleStatus
    : '';

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-status-banner-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {configured ? (
        <div
          className={`flex items-start gap-3 rounded-md border p-3 shadow-sm ${toneClass}`}
          data-testid={`runtime-status-banner-sample-${block.id}`}
          data-status={sampleStatus}
        >
          <span className={`mt-1 h-2.5 w-2.5 shrink-0 rounded-full ${dotClass}`} />
          <div className="min-w-0">
            <div
              className="text-sm font-semibold"
              data-testid={`runtime-status-banner-title-${block.id}`}
            >
              {title}
            </div>
            <div className="mt-0.5 text-xs opacity-70">{sampleStatus}</div>
          </div>
        </div>
      ) : (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-status-banner-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.statusBannerNotConfigured)}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-status-banner-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Workbench-family batch 2 representative previews. Same architecture as the
// metric-strip / status-banner previews above: read the BARE top-level config
// the platform renderers read, render a config-driven representative placeholder
// (labels + scaffold, no live data), an empty state, and the shared workbench
// preview hint. Live data binding renders on the published /p/ page via the
// platform renderers (framework/meta/rendering/blocks/*). Token-backed utility
// classes only — no raw hex.
// ---------------------------------------------------------------------------

const WORKBENCH_PREVIEW_BUTTON_CLASS: Record<string, string> = {
  primary: 'border-blue-200 bg-blue-50 text-blue-700',
  secondary: 'border-slate-200 bg-white text-slate-700',
  danger: 'border-rose-200 bg-rose-50 text-rose-700',
  ghost: 'border-transparent bg-slate-50 text-slate-600',
  default: 'border-slate-200 bg-white text-slate-700',
};

function getRawArray(block: DslBlockV3, key: string): Array<Record<string, unknown>> {
  const value = (block as unknown as Record<string, unknown>)[key];
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is Record<string, unknown> => isRecord(item));
}

function getRawRecord(block: DslBlockV3, key: string): Record<string, unknown> {
  const value = (block as unknown as Record<string, unknown>)[key];
  return isRecord(value) ? value : {};
}

function RuntimeWorkbenchActionBarPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  const actions = getRawArray(block, 'actions');

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-workbench-action-bar-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {actions.length === 0 ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-workbench-action-bar-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.noActionsConfigured)}
        </div>
      ) : (
        <div
          className="flex flex-wrap gap-2"
          data-testid={`runtime-workbench-action-bar-actions-${block.id}`}
        >
          {actions.map((action, index) => {
            const key =
              getStringProp(action.code) || getStringProp(action.id) || String(index);
            const label = readLocalizedLabel(action.label, locale) || key;
            const variant = getStringProp(action.variant) || 'secondary';
            const variantClass =
              WORKBENCH_PREVIEW_BUTTON_CLASS[variant] || WORKBENCH_PREVIEW_BUTTON_CLASS.default;
            return (
              <span
                key={key}
                data-testid={`runtime-workbench-action-bar-action-${key}`}
                className={`inline-flex min-h-8 items-center rounded-md border px-3 py-1.5 text-sm font-medium ${variantClass}`}
              >
                {label}
              </span>
            );
          })}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-workbench-action-bar-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeEvidencePanelPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  const sections = getRawArray(block, 'sections');

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-evidence-panel-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {sections.length === 0 ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-evidence-panel-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.noEvidenceSections)}
        </div>
      ) : (
        <div className="space-y-3" data-testid={`runtime-evidence-panel-sections-${block.id}`}>
          {sections.map((section, index) => {
            const key =
              getStringProp(section.key) || getStringProp(section.field) || String(index);
            const label = readLocalizedLabel(section.label, locale) || key;
            const isJson = getStringProp(section.format) === 'json';
            return (
              <div key={key} data-testid={`runtime-evidence-panel-section-${key}`}>
                <div className="text-xs font-medium uppercase tracking-wide text-slate-500">
                  {label}
                </div>
                <div
                  className={`mt-1 rounded-md border border-slate-100 bg-slate-50 px-3 py-2 text-xs ${
                    isJson ? 'font-mono text-slate-500' : 'text-slate-600'
                  }`}
                >
                  {t(DESIGNER_I18N.unified.runtime.metricPlaceholderValue)}
                </div>
              </div>
            );
          })}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-evidence-panel-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
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

function RuntimeCandidateListPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  const item = getRawRecord(block, 'item');
  const detailFields = Array.isArray(item.detailFields)
    ? (item.detailFields.filter((f): f is Record<string, unknown> => isRecord(f)) as Array<
        Record<string, unknown>
      >)
    : [];
  const actions = getRawArray(block, 'actions');
  const titleField = getStringProp(item.titleField);
  const configured = Boolean(titleField || detailFields.length > 0 || actions.length > 0);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-candidate-list-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {!configured ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-candidate-list-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.noCandidateFields)}
        </div>
      ) : (
        <div
          className="rounded-md border border-slate-200 p-3"
          data-testid={`runtime-candidate-list-sample-${block.id}`}
        >
          <div className="font-mono text-sm font-semibold text-slate-700">
            {titleField || t(DESIGNER_I18N.unified.runtime.candidatesLabel)}
          </div>
          {detailFields.length > 0 && (
            <dl className="mt-2 grid grid-cols-1 gap-2 text-xs sm:grid-cols-2">
              {detailFields.map((field, index) => {
                const key =
                  getStringProp(field.key) || getStringProp(field.field) || String(index);
                const label = readLocalizedLabel(field.label, locale) || key;
                return (
                  <div
                    key={key}
                    data-testid={`runtime-candidate-list-field-${key}`}
                  >
                    <dt className="text-slate-500">{label}</dt>
                    <dd className="mt-0.5 text-slate-700">
                      {t(DESIGNER_I18N.unified.runtime.metricPlaceholderValue)}
                    </dd>
                  </div>
                );
              })}
            </dl>
          )}
          {actions.length > 0 && (
            <div className="mt-3 flex flex-wrap gap-2 border-t border-slate-100 pt-3">
              {actions.map((action, index) => {
                const key =
                  getStringProp(action.code) || getStringProp(action.id) || String(index);
                const label = readLocalizedLabel(action.label, locale) || key;
                const variant = getStringProp(action.variant) || 'primary';
                const variantClass =
                  WORKBENCH_PREVIEW_BUTTON_CLASS[variant] || WORKBENCH_PREVIEW_BUTTON_CLASS.primary;
                return (
                  <span
                    key={key}
                    data-testid={`runtime-candidate-list-action-${key}`}
                    className={`inline-flex min-h-8 items-center rounded-md border px-3 py-1.5 text-xs font-medium ${variantClass}`}
                  >
                    {label}
                  </span>
                );
              })}
            </div>
          )}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-candidate-list-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeArtifactTimelinePreview({ block }: { block: DslBlockV3 }) {
  const t = useRuntimeText();
  const item = getRawRecord(block, 'item');
  const titleField = getStringProp(item.titleField);
  const revisionField = getStringProp(item.revisionField);
  const statusField = getStringProp(item.statusField);
  const fileIdField = getStringProp(item.fileIdField);
  const configured = Boolean(
    titleField || revisionField || statusField || fileIdField || getStringProp(item.keyField),
  );

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-artifact-timeline-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {!configured ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-artifact-timeline-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.noTimelineFields)}
        </div>
      ) : (
        <ol
          className="space-y-2"
          data-testid={`runtime-artifact-timeline-sample-${block.id}`}
        >
          <li className="grid grid-cols-[auto_1fr_auto] items-start gap-3">
            <span className="mt-1 h-2.5 w-2.5 shrink-0 rounded-full bg-blue-500" />
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <span
                  className="font-mono text-sm font-semibold text-slate-700"
                  data-testid={`runtime-artifact-timeline-title-${block.id}`}
                >
                  {titleField || t(DESIGNER_I18N.unified.runtime.metricPlaceholderValue)}
                </span>
                {revisionField && (
                  <span className="rounded-full border border-blue-200 bg-blue-50 px-2 py-0.5 text-xs text-blue-700">
                    {revisionField}
                  </span>
                )}
                {statusField && (
                  <span className="rounded-full border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-xs text-emerald-700">
                    {statusField}
                  </span>
                )}
              </div>
            </div>
            {fileIdField && (
              <span
                className="self-start rounded-md border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600"
                data-testid={`runtime-artifact-timeline-download-${block.id}`}
              >
                {fileIdField}
              </span>
            )}
          </li>
        </ol>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-artifact-timeline-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeReviewDrawerPreview({ block }: { block: DslBlockV3 }) {
  const t = useRuntimeText();
  const summaryBadges = getRawArray(block, 'summaryBadges');
  const compare = getRawRecord(block, 'compare');
  const candidates = getRawRecord(block, 'candidates');
  const hasContext =
    getStringProp((block as unknown as Record<string, unknown>).context).length > 0 ||
    getStringProp((block as unknown as Record<string, unknown>).contextDataSource).length > 0;
  const rawFields = Array.isArray(compare.rawFields) ? compare.rawFields.length : 0;
  const canonicalFields = Array.isArray(compare.canonicalFields)
    ? compare.canonicalFields.length
    : 0;
  const candidateActions = Array.isArray(candidates.actions) ? candidates.actions.length : 0;
  const configured = hasContext || summaryBadges.length > 0 || rawFields + canonicalFields > 0;

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-review-drawer-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {!configured ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-review-drawer-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.reviewDrawerNotConfigured)}
        </div>
      ) : (
        <div
          className="rounded-md border border-blue-200 bg-blue-50/40 p-3"
          data-testid={`runtime-review-drawer-sample-${block.id}`}
        >
          <div className="text-sm font-semibold text-slate-700">
            {t(DESIGNER_I18N.unified.runtime.reviewDrawerPreview)}
          </div>
          <div
            className="mt-2 flex flex-wrap gap-2"
            data-testid={`runtime-review-drawer-summary-${block.id}`}
          >
            {summaryBadges.length > 0 ? (
              summaryBadges.map((badge, index) => {
                const key =
                  getStringProp(badge.key) ||
                  getStringProp(badge.valueField) ||
                  String(index);
                const tone = getStringProp(badge.tone) || 'gray';
                const toneClass =
                  WORKBENCH_TONE_CARD_CLASS[tone] || WORKBENCH_TONE_CARD_CLASS.default;
                return (
                  <span
                    key={key}
                    data-testid={`runtime-review-drawer-badge-${key}`}
                    className={`inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-semibold ${toneClass}`}
                  >
                    {key}
                  </span>
                );
              })
            ) : (
              <span className="text-xs text-slate-400">
                {t(DESIGNER_I18N.unified.runtime.metricPlaceholderValue)}
              </span>
            )}
          </div>
          <div className="mt-3 grid gap-2 text-xs text-slate-500 sm:grid-cols-2">
            {rawFields + canonicalFields > 0 && (
              <div data-testid={`runtime-review-drawer-compare-${block.id}`}>
                {`Raw ${rawFields} / Canonical ${canonicalFields}`}
              </div>
            )}
            <div data-testid={`runtime-review-drawer-candidates-${block.id}`}>
              {`${t(DESIGNER_I18N.unified.runtime.candidatesLabel)} · ${t(
                DESIGNER_I18N.unified.runtime.decisionLabel,
              )}${candidateActions > 0 ? ` (${candidateActions})` : ''}`}
            </div>
          </div>
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-review-drawer-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Display / data block representative previews (stat-card / description /
// record-comments / embedded-list). Same architecture as the workbench previews
// above: read the BARE top-level config the platform renderers read, render a
// config-driven representative placeholder (no live data), an empty / not-
// configured state, and the shared display preview hint. Live data binding renders
// on the published /p/ page via the platform renderers
// (framework/meta/rendering/blocks/*). Token-backed utility classes only — no raw
// hex.
// ---------------------------------------------------------------------------

function RuntimeStatCardPreview({ block }: { block: DslBlockV3 }) {
  const t = useRuntimeText();
  // StatCardBlockRenderer: cfg = { ...block.props, ...block.statCard }. Mirror
  // that read order so the preview surfaces whatever the live renderer would.
  const statCard = getRawRecord(block, 'statCard');
  const props = isRecord(block.props) ? block.props : {};
  const cfg = { ...props, ...statCard };
  const unit = getStringProp(cfg.unit) || getStringProp(cfg.suffix);
  const trend = getStringProp(cfg.trend) || getStringProp(cfg.change);
  const trendDirection = getStringProp(cfg.trendDirection) || 'flat';
  const valueField = getStringProp(cfg.valueField);
  const dataSourceId = getStringProp(
    (block as unknown as Record<string, unknown>).dataSource,
  );
  // A representative card needs at least a title, an inline value, a bound value
  // field, or a data source to be meaningful.
  const inlineValue =
    cfg.value !== undefined && cfg.value !== null ? String(cfg.value) : '';
  const configured = Boolean(
    inlineValue || valueField || dataSourceId || getBlockLabel(block),
  );
  const trendClass =
    trendDirection === 'up'
      ? 'text-emerald-600'
      : trendDirection === 'down'
        ? 'text-rose-600'
        : 'text-slate-500';

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-stat-card-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {!configured ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-stat-card-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.statCardNotConfigured)}
        </div>
      ) : (
        <div
          className="rounded-md border border-slate-200 p-3 shadow-sm"
          data-testid={`runtime-stat-card-sample-${block.id}`}
        >
          <div className="flex items-baseline gap-2">
            <span
              className="text-2xl font-semibold text-slate-900"
              data-testid={`runtime-stat-card-value-${block.id}`}
            >
              {inlineValue || t(DESIGNER_I18N.unified.runtime.metricPlaceholderValue)}
            </span>
            {unit ? <span className="text-sm text-slate-500">{unit}</span> : null}
          </div>
          {trend ? (
            <div
              className={`mt-1 text-xs ${trendClass}`}
              data-testid={`runtime-stat-card-trend-${block.id}`}
            >
              {trend}
            </div>
          ) : null}
          {valueField || dataSourceId ? (
            <div
              className="mt-2 text-xs text-slate-400"
              data-testid={`runtime-stat-card-binding-${block.id}`}
            >
              {[dataSourceId, valueField].filter(Boolean).join(' · ')}
            </div>
          ) : null}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-stat-card-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeDescriptionPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  // DescriptionBlockRenderer reads block.content ?? props.content ?? props.text.
  // Mirror that bare+props read order. The live renderer sanitizes HTML; the
  // preview shows the resolved text (representative, not the sanitized HTML run).
  const raw = block as unknown as Record<string, unknown>;
  const props = isRecord(block.props) ? block.props : {};
  const contentSource = raw.content ?? props.content ?? props.text;
  const content = readLocalizedLabel(contentSource, locale);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-description-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {content ? (
        <div
          className="rounded-control border border-blue-200 bg-blue-50 p-3 text-sm text-slate-700"
          data-testid={`runtime-description-content-${block.id}`}
        >
          {content}
        </div>
      ) : (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-description-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.descriptionEmpty)}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-description-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeRecordCommentsPreview({ block }: { block: DslBlockV3 }) {
  const t = useRuntimeText();
  // RecordComments derives modelCode + recordPid from the surrounding detail page
  // + current record — it has NO block-level data config. The preview is therefore
  // a static representative scaffold (input + thread placeholder); the live thread
  // loads only on the published record detail page.
  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-record-comments-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      <div
        className="space-y-2"
        data-testid={`runtime-record-comments-sample-${block.id}`}
      >
        <div className="rounded-control border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-400">
          {t(DESIGNER_I18N.unified.runtime.recordCommentsPreview)}
        </div>
        <div className="flex items-start gap-2">
          <span className="rounded-pill mt-0.5 flex h-7 w-7 items-center justify-center bg-blue-100 text-xs font-medium text-blue-600">
            U
          </span>
          <div className="min-w-0 flex-1 rounded-md border border-slate-100 bg-white px-3 py-2 text-sm text-slate-500">
            {t(DESIGNER_I18N.unified.runtime.metricPlaceholderValue)}
          </div>
        </div>
      </div>
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-record-comments-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeEmbeddedListPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  // EmbeddedListBlockRenderer reads bare block.modelCode (or childModel),
  // block.parentField (or foreignKey), block.columns (or table.columns). The
  // preview shows the bound model + the column headers (representative); the live
  // list renders on the published page (records are parent-scoped at runtime).
  const raw = block as unknown as Record<string, unknown>;
  const modelCode =
    getStringProp(raw.modelCode) || getStringProp(raw.childModel);
  const parentField =
    getStringProp(raw.parentField) || getStringProp(raw.foreignKey);
  const columns = getRawArray(block, 'columns');
  const configured = Boolean(modelCode);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-embedded-list-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {!configured ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-embedded-list-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.embeddedListNotConfigured)}
        </div>
      ) : (
        <div
          className="overflow-hidden rounded-md border border-slate-200"
          data-testid={`runtime-embedded-list-sample-${block.id}`}
        >
          <div
            className="flex items-center justify-between border-b border-slate-100 bg-slate-50 px-3 py-2 text-xs text-slate-500"
            data-testid={`runtime-embedded-list-binding-${block.id}`}
          >
            <span className="font-mono font-medium text-slate-700">{modelCode}</span>
            {parentField ? <span>{parentField}</span> : null}
          </div>
          {columns.length > 0 ? (
            <div
              className="flex flex-wrap gap-2 px-3 py-2"
              data-testid={`runtime-embedded-list-columns-${block.id}`}
            >
              {columns.map((column, index) => {
                const key =
                  getStringProp(column.field) || getStringProp(column.key) || String(index);
                const label = readLocalizedLabel(column.label, locale) || key;
                return (
                  <span
                    key={key}
                    data-testid={`runtime-embedded-list-column-${key}`}
                    className="inline-flex items-center rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-600"
                  >
                    {label}
                  </span>
                );
              })}
            </div>
          ) : (
            <div className="px-3 py-2 text-xs text-slate-400">
              {t(DESIGNER_I18N.unified.runtime.noData)}
            </div>
          )}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-embedded-list-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.embeddedListPreview)}
      </div>
    </section>
  );
}

// ── E2 batch representative previews ────────────────────────────────────────
// Each mirrors the prop paths its platform renderer reads (verified against the
// renderer source). The designer canvas shows a config-driven placeholder; the
// full, data-bound component renders on the live /p/ page.

function RuntimeChartPreview({ block }: { block: DslBlockV3 }) {
  const t = useRuntimeText();
  // ChartBlockRenderer reads bare block.chartType / block.dataSource /
  // block.chartConfig ({ xField, yField, height }) / block.visualization.
  const raw = block as unknown as Record<string, unknown>;
  const chartType = getStringProp(raw.chartType) || 'bar';
  const dataSourceId = getStringProp(raw.dataSource);
  const config = getRawRecord(block, 'chartConfig');
  const xField = getStringProp(config.xField);
  const yField = getStringProp(config.yField);
  // A chart needs a real binding to be meaningful — a bare canvas title is not
  // enough (getBlockLabel falls back to the blockType, so it is excluded here).
  const configured = Boolean(dataSourceId || xField || yField);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-chart-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {!configured ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-chart-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.chartNotConfigured)}
        </div>
      ) : (
        <div
          className="rounded-md border border-slate-200 p-3 shadow-sm"
          data-testid={`runtime-chart-sample-${block.id}`}
        >
          <div className="mb-2 flex items-center gap-2">
            <span
              className="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700"
              data-testid={`runtime-chart-type-${block.id}`}
            >
              {chartType}
            </span>
            {dataSourceId ? (
              <span
                className="font-mono text-xs text-slate-500"
                data-testid={`runtime-chart-binding-${block.id}`}
              >
                {dataSourceId}
              </span>
            ) : null}
          </div>
          <div className="flex h-16 items-end gap-1.5" aria-hidden="true">
            {[40, 70, 55, 85, 60].map((h, i) => (
              <span
                key={i}
                className="flex-1 rounded-t bg-indigo-300"
                style={{ height: `${h}%` }}
              />
            ))}
          </div>
          {xField || yField ? (
            <div className="mt-2 text-xs text-slate-400">
              {[xField, yField].filter(Boolean).join(' · ')}
            </div>
          ) : null}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-chart-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeRichTextPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  // RichTextBlockRenderer reads bare block.content (string or LocalizedText). The
  // live renderer sanitizes + injects HTML; the preview shows the resolved text
  // (representative; no dangerouslySetInnerHTML in the designer canvas).
  const raw = block as unknown as Record<string, unknown>;
  const content = readLocalizedLabel(raw.content, locale);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-rich-text-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {content ? (
        <div
          className="prose prose-sm max-w-none rounded-md border border-slate-200 p-3 text-sm text-slate-700"
          data-testid={`runtime-rich-text-content-${block.id}`}
        >
          {content}
        </div>
      ) : (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-rich-text-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.richTextEmpty)}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-rich-text-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeDividerPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  // DividerBlockRenderer reads bare block.title (optional label divider).
  const raw = block as unknown as Record<string, unknown>;
  const label = readLocalizedLabel(raw.title, locale);

  return (
    <div
      className="py-2"
      data-testid={`runtime-divider-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      {label ? (
        <div className="flex items-center gap-3" role="separator">
          <span className="h-px flex-1 bg-slate-200" />
          <span
            className="text-xs font-medium uppercase tracking-wider text-slate-500"
            data-testid={`runtime-divider-label-${block.id}`}
          >
            {label}
          </span>
          <span className="h-px flex-1 bg-slate-200" />
        </div>
      ) : (
        <hr className="border-t border-slate-200" role="separator" />
      )}
    </div>
  );
}

function RuntimeToolbarPreview({
  block,
  variant,
}: {
  block: DslBlockV3;
  variant: 'toolbar' | 'form-buttons';
}) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  // Toolbar / FormButtons renderers both read bare block.buttons (ButtonConfig[]).
  const buttons = getRawArray(block, 'buttons');
  const testId = variant === 'toolbar' ? 'runtime-toolbar' : 'runtime-form-buttons';
  const justify = variant === 'form-buttons' ? 'justify-end' : 'justify-start';

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`${testId}-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {buttons.length > 0 ? (
        <div className={`flex flex-wrap gap-2 ${justify}`} data-testid={`${testId}-buttons-${block.id}`}>
          {buttons.map((button, index) => {
            const code = getStringProp(button.code) || String(index);
            const label =
              readLocalizedLabel(button.label, locale) ||
              readLocalizedLabel(button.content, locale) ||
              code;
            const primary =
              getBooleanProp(button.primary) || getStringProp(button.variant) === 'primary';
            const danger =
              getBooleanProp(button.danger) || getStringProp(button.variant) === 'danger';
            const tone = danger
              ? 'border-rose-200 bg-rose-50 text-rose-700'
              : primary
                ? 'border-indigo-200 bg-indigo-50 text-indigo-700'
                : 'border-slate-200 bg-white text-slate-600';
            return (
              <span
                key={code}
                data-testid={`${testId}-button-${code}`}
                className={`inline-flex items-center rounded-md border px-2.5 py-1 text-xs font-medium ${tone}`}
              >
                {label}
              </span>
            );
          })}
        </div>
      ) : (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`${testId}-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.toolbarNoButtons)}
        </div>
      )}
      <div className="mt-2 text-xs text-slate-400" data-testid={`${testId}-hint-${block.id}`}>
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeFiltersPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  // FiltersBlockRenderer reads bare block.fields (filter FieldConfig[]) + onSearch /
  // onReset handler refs (fired on the Search / Reset buttons).
  const fields = getRawArray(block, 'fields');

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-filters-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {fields.length > 0 ? (
        <div
          className="rounded-md border border-slate-200 bg-slate-50 p-3"
          data-testid={`runtime-filters-sample-${block.id}`}
        >
          <div className="flex flex-wrap gap-2">
            {fields.map((field, index) => {
              const key = getStringProp(field.field) || String(index);
              const label = readLocalizedLabel(field.label, locale) || key;
              return (
                <span
                  key={key}
                  data-testid={`runtime-filters-field-${key}`}
                  className="inline-flex items-center rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-600"
                >
                  {label}
                </span>
              );
            })}
          </div>
          <div className="mt-3 flex justify-end gap-2">
            <span className="rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs text-slate-500">
              {t(DESIGNER_I18N.unified.runtime.filtersReset)}
            </span>
            <span className="rounded-md bg-indigo-500 px-2.5 py-1 text-xs font-medium text-white">
              {t(DESIGNER_I18N.unified.runtime.filtersSearch)}
            </span>
          </div>
        </div>
      ) : (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-filters-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.filtersNoFields)}
        </div>
      )}
      <div className="mt-2 text-xs text-slate-400" data-testid={`runtime-filters-hint-${block.id}`}>
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeFormWizardPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  // FormWizardBlockRenderer reads bare block.steps ({ key, label, description?,
  // blocks[] }). The preview shows the step rail; child blocks render per step live.
  const steps = getRawArray(block, 'steps');

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-form-wizard-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {steps.length > 0 ? (
        <ol
          className="flex flex-wrap items-center gap-2"
          data-testid={`runtime-form-wizard-steps-${block.id}`}
        >
          {steps.map((step, index) => {
            const key = getStringProp(step.key) || String(index);
            const label = readLocalizedLabel(step.label, locale) || key;
            return (
              <li
                key={key}
                data-testid={`runtime-form-wizard-step-${key}`}
                className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium ${
                  index === 0
                    ? 'bg-indigo-500 text-white'
                    : 'bg-slate-100 text-slate-600'
                }`}
              >
                <span className="flex h-4 w-4 items-center justify-center rounded-full bg-white/30 text-[10px]">
                  {index + 1}
                </span>
                {label}
              </li>
            );
          })}
        </ol>
      ) : (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-form-wizard-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.formWizardNoSteps)}
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-form-wizard-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeTraceGraphPreview({ block }: { block: DslBlockV3 }) {
  const t = useRuntimeText();
  // TraceGraphBlockRenderer reads bare block.dataSource (string id) + block.mode
  // ('consumption' | 'genealogy'). The live @xyflow canvas renders on /p/; the
  // preview is a static node→node placeholder (avoids the zero-height canvas pitfall).
  const raw = block as unknown as Record<string, unknown>;
  const dataSourceId = getStringProp(raw.dataSource);
  const mode = getStringProp(raw.mode);
  // A trace graph is driven by its data source; a bare canvas title is not enough.
  const configured = Boolean(dataSourceId);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-trace-graph-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {!configured ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-trace-graph-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.traceGraphNotConfigured)}
        </div>
      ) : (
        <div
          className="rounded-md border border-slate-200 p-3"
          data-testid={`runtime-trace-graph-sample-${block.id}`}
        >
          <div className="flex items-center gap-2">
            <span className="rounded-md border border-sky-200 bg-sky-50 px-2.5 py-1 text-xs font-medium text-sky-700">
              {t(DESIGNER_I18N.unified.runtime.traceGraphNodeA)}
            </span>
            <span className="text-slate-300">→</span>
            <span className="rounded-md border border-emerald-200 bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-700">
              {t(DESIGNER_I18N.unified.runtime.traceGraphNodeB)}
            </span>
          </div>
          <div
            className="mt-2 flex flex-wrap gap-2 text-xs text-slate-400"
            data-testid={`runtime-trace-graph-binding-${block.id}`}
          >
            {mode ? (
              <span
                className="rounded bg-slate-100 px-1.5 py-0.5 font-medium text-slate-600"
                data-testid={`runtime-trace-graph-mode-${block.id}`}
              >
                {mode}
              </span>
            ) : null}
            {dataSourceId ? <span className="font-mono">{dataSourceId}</span> : null}
          </div>
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-trace-graph-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeSelectionInfoPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  // SelectionInfoBlockRenderer reads bare block.title + the bound state key
  // (block.selection.bind || block.bind || 'selectedRows').
  const raw = block as unknown as Record<string, unknown>;
  const selection = getRawRecord(block, 'selection');
  const bind =
    getStringProp(selection.bind) || getStringProp(raw.bind) || 'selectedRows';
  const title = readLocalizedLabel(raw.title, locale) || getBlockLabel(block);

  return (
    <section
      className="rounded-lg border border-blue-100 bg-blue-50 p-4"
      data-testid={`runtime-selection-info-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      {title ? (
        <div className="text-sm font-medium text-blue-900">{title}</div>
      ) : null}
      <div className="mt-1 text-2xl font-semibold text-blue-900">0</div>
      <div
        className="mt-1 text-xs text-blue-700"
        data-testid={`runtime-selection-info-bind-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.selectionInfoBoundTo)}
        <span className="ml-1 font-mono">{bind}</span>
      </div>
      <div
        className="mt-2 text-xs text-blue-400"
        data-testid={`runtime-selection-info-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function RuntimeGerberViewerPreview({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  // GerberViewerBlockRenderer reads bare block.title|label / block.dataSource /
  // block.inspection / block.inspectionUrl / block.lineContext /
  // block.lineInspectionField / block.empty. The live PCB canvas fetches gerber
  // artifacts (auth token); the preview is a representative board placeholder.
  const raw = block as unknown as Record<string, unknown>;
  const dataSourceId = getStringProp(raw.dataSource);
  const inspection = getStringProp(raw.inspection) || getStringProp(raw.inspectionUrl);
  const lineField = getStringProp(raw.lineInspectionField);
  // Driven by a data source / inspection binding; a bare canvas title is not enough.
  const configured = Boolean(dataSourceId || inspection);

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-gerber-viewer-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      {!configured ? (
        <div
          className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
          data-testid={`runtime-gerber-viewer-empty-${block.id}`}
        >
          {t(DESIGNER_I18N.unified.runtime.gerberViewerNotConfigured)}
        </div>
      ) : (
        <div
          className="rounded-md border border-slate-200 p-3"
          data-testid={`runtime-gerber-viewer-sample-${block.id}`}
        >
          <div className="flex h-20 items-center justify-center rounded border border-emerald-700 bg-emerald-900/90">
            <span className="rounded bg-emerald-700 px-2 py-0.5 text-[10px] font-medium text-emerald-50">
              PCB
            </span>
          </div>
          <div
            className="mt-2 flex flex-wrap gap-2 text-xs text-slate-400"
            data-testid={`runtime-gerber-viewer-binding-${block.id}`}
          >
            {dataSourceId ? <span className="font-mono">{dataSourceId}</span> : null}
            {lineField ? <span className="rounded bg-slate-100 px-1.5 py-0.5">{lineField}</span> : null}
          </div>
        </div>
      )}
      <div
        className="mt-2 text-xs text-slate-400"
        data-testid={`runtime-gerber-viewer-hint-${block.id}`}
      >
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </section>
  );
}

function useRuntimeHelperBlockData(
  block: DslBlockV3,
  runtimeServices: RuntimeExecutionServices | undefined,
  pageContext: RuntimePageContext,
  blockPath: string[],
) {
  const hasPermission = React.useContext(RuntimePermissionContext);
  const [runtimeData, setRuntimeData] = React.useState<RuntimeHelperBlockData | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [runtimeError, setRuntimeError] = React.useState<RuntimeExecutionIssue | null>(null);
  const dataSourceSignature = JSON.stringify(block.dataSource ?? {});
  const permissionCode = getRuntimePermissionCode(block);
  const permissionAllowed = permissionCode ? hasPermission(permissionCode) : true;
  const shouldLoadData = Boolean(
    runtimeServices?.loadHelperBlockData && hasExecutableHelperDataSource(block) && permissionAllowed,
  );

  React.useEffect(() => {
    if (!shouldLoadData || !runtimeServices?.loadHelperBlockData) {
      setRuntimeData(null);
      setRuntimeError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setRuntimeError(null);
    void runtimeServices
      .loadHelperBlockData(block, getRuntimeBlockContext(block, pageContext, blockPath))
      .then((data) => {
        if (!cancelled) setRuntimeData(data);
      })
      .catch((loadError: unknown) => {
        if (!cancelled) {
          setRuntimeData(null);
          setRuntimeError(normalizeRuntimeExecutionError(loadError));
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [block, dataSourceSignature, runtimeServices, shouldLoadData]);

  return { runtimeData, loading, runtimeError, permissionCode, permissionAllowed };
}

function RuntimeHelperDataStatus({
  blockId,
  data,
  loading,
  error,
  permissionCode,
  permissionAllowed,
}: {
  blockId: string;
  data: RuntimeHelperBlockData | null;
  loading: boolean;
  error: RuntimeExecutionIssue | null;
  permissionCode: string;
  permissionAllowed: boolean;
}) {
  const t = useRuntimeText();
  const permissionStatus = permissionCode ? (
    <div
      className={`mt-1 text-xs ${
        permissionAllowed ? 'text-slate-500' : 'font-medium text-amber-700'
      }`}
      data-testid={`runtime-helper-permission-${blockId}`}
      data-permission-code={permissionCode}
      data-permission-allowed={String(permissionAllowed)}
    >
      {permissionAllowed ? `Permission: ${permissionCode}` : `Requires permission: ${permissionCode}`}
    </div>
  ) : null;

  let dataStatus: React.ReactNode = null;
  if (loading) {
    dataStatus = (
      <div
        className="mt-1 text-xs text-slate-400"
        data-testid={`runtime-helper-loading-${blockId}`}
      >
        {t(DESIGNER_I18N.unified.runtime.loadingLiveData)}
      </div>
    );
  } else if (error) {
    dataStatus = (
      <div className="mt-1 text-xs text-red-600" data-testid={`runtime-helper-error-${blockId}`}>
        {error.message}
      </div>
    );
  } else if (data?.source) {
    dataStatus = (
      <div
        className="mt-1 text-xs text-slate-400"
        data-testid={`runtime-helper-source-${blockId}`}
      >
        {data.source}
      </div>
    );
  }

  if (!permissionStatus && !dataStatus) return null;

  return (
    <>
      {permissionStatus}
      {dataStatus}
    </>
  );
}

function RuntimeForm({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const hasPermission = React.useContext(RuntimePermissionContext);
  const [values, setValues] = React.useState<Record<string, unknown>>({});
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
    <RuntimeFormValueContext.Provider value={formContext}>
      <RuntimeContainer
        block={block}
        className="space-y-3"
        runtimeServices={runtimeServices}
        pageContext={pageContext}
        blockPath={blockPath}
      />
    </RuntimeFormValueContext.Provider>
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
  const formContext = React.useContext(RuntimeFormValueContext);
  if (!isRuntimeBlockVisible(block, formContext?.values)) return null;

  return (
    <section
      className="rounded-md border border-slate-100 bg-slate-50 p-3"
      data-testid={`runtime-block-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <RuntimeTitle block={block} />
      <div className="grid grid-cols-12 gap-3">
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

function RuntimeField({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
  const listContext = React.useContext(RuntimeListSelectionContext);
  const hasPermission = React.useContext(RuntimePermissionContext);
  const modelFields = React.useContext(RuntimeModelFieldsContext);
  const t = useRuntimeText();
  if (!isRuntimeBlockVisible(block, formContext?.values)) return null;

  const permissionCode = getRuntimePermissionCode(block);
  const permissionAllowed = isRuntimeBlockPermissionAllowed(block, hasPermission);
  if (!permissionAllowed) {
    return (
      <div
        className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm"
        data-permission-allowed="false"
        data-permission-code={permissionCode}
        data-testid={`runtime-field-${block.id}`}
        style={getSpanGridStyle(block)}
      >
        <RuntimePermissionNotice
          permissionCode={permissionCode}
          testId={`runtime-field-permission-${block.id}`}
        />
      </div>
    );
  }

  // True WYSIWYG: when the page's model metadata is available (workbench preview),
  // render the real platform control for `field` blocks instead of the generic input.
  const previewModelField =
    block.blockType === 'field' && block.field
      ? modelFields.find((candidate) => candidate.code === block.field)
      : undefined;
  if (previewModelField && !isDesignerRuntimeOnlyComponent(block)) {
    return <RuntimePlatformField block={block} modelField={previewModelField} />;
  }

  const fieldKey = block.field || block.id;
  const isFormField = block.blockType === 'field' && Boolean(formContext);
  const isFilterField = block.blockType === 'filter-field' && Boolean(listContext);
  const component = normalizeRuntimeFieldComponent(block.props?.component);
  const controlId = getRuntimeFieldControlId(block, component);
  const value = formContext?.values[fieldKey];
  const filterValue = listContext?.filterValues[fieldKey];
  const stringValue = typeof value === 'string' || typeof value === 'number' ? String(value) : '';
  const filterStringValue =
    typeof filterValue === 'string' || typeof filterValue === 'number' ? String(filterValue) : '';
  const placeholder = getStringProp(block.props?.placeholder);
  const helpText = getStringProp(block.props?.helpText);
  const readOnly = Boolean(block.props?.readOnly);
  const error = formContext?.errors[fieldKey] ?? '';

  return (
    <div
      className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm"
      data-testid={`runtime-field-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <label className="block font-medium text-slate-700" htmlFor={controlId}>
        {getBlockLabel(block)}
      </label>
      {block.props?.required ? <span className="ml-1 text-red-500">*</span> : null}
      {isFormField
        ? renderRuntimeFieldControl({
            block,
            component,
            fieldKey,
            value,
            stringValue,
            placeholder,
            readOnly,
            formContext,
            runtimeServices,
            pageContext,
            blockPath,
            t,
          })
        : isFilterField
          ? renderRuntimeFilterControl({
              block,
              blockPath,
              component,
              fieldKey,
              filterValue,
              stringValue: filterStringValue,
              placeholder,
              listContext,
              pageContext,
              runtimeServices,
              t,
            })
        : null}
      {helpText ? (
        <div className="mt-1 text-xs text-slate-500" data-testid={`runtime-field-help-${block.id}`}>
          {helpText}
        </div>
      ) : null}
      {error ? (
        <div className="mt-1 text-xs text-red-600" data-testid={`runtime-field-error-${block.id}`}>
          {error}
        </div>
      ) : null}
    </div>
  );
}

/**
 * WYSIWYG form-field preview: renders the exact same platform control the live
 * `/p/` page uses ({@link ControlledFieldRenderer}), driven by the field's resolved
 * model metadata (`renderComponent`, label, dictCode, extension props). This replaces
 * the legacy generic-input representative preview for `field` blocks whose model
 * metadata is available.
 */
function RuntimePlatformField({
  block,
  modelField,
}: {
  block: DslBlockV3;
  modelField: ModelFieldDefinition;
}) {
  const locale = React.useContext(RuntimeLocaleContext);
  const formContext = React.useContext(RuntimeFormValueContext);
  const hasPermission = React.useContext(RuntimePermissionContext);
  const [localValue, setLocalValue] = React.useState<unknown>(undefined);

  const fieldConfig = React.useMemo(
    () => buildPreviewFieldConfig(block, modelField),
    [block, modelField],
  );
  const expressionContext = React.useMemo(() => createExpressionContext({ locale }), [locale]);

  const permissionCode = getRuntimePermissionCode(block);
  const permissionAllowed = isRuntimeBlockPermissionAllowed(block, hasPermission);
  if (!permissionAllowed) {
    return (
      <div
        className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm"
        data-permission-allowed="false"
        data-permission-code={permissionCode}
        data-testid={`runtime-field-${block.id}`}
        style={getSpanGridStyle(block)}
      >
        <RuntimePermissionNotice
          permissionCode={permissionCode}
          testId={`runtime-field-permission-${block.id}`}
        />
      </div>
    );
  }

  const fieldKey = block.field || block.id;
  const value = formContext ? formContext.values[fieldKey] : localValue;
  const rawError = formContext?.errors[fieldKey];
  const handleChange = (next: unknown) => {
    if (formContext) {
      formContext.setValue(fieldKey, next);
    } else {
      setLocalValue(next);
    }
  };

  return (
    <div
      data-testid={`runtime-field-${block.id}`}
      data-field-component={fieldConfig.component ?? undefined}
      data-wysiwyg="platform"
      style={getSpanGridStyle(block)}
    >
      <ControlledFieldRenderer
        field={fieldConfig}
        value={value}
        onChange={handleChange}
        context={expressionContext}
        error={typeof rawError === 'string' ? rawError : undefined}
      />
    </div>
  );
}

function renderRuntimeFieldControl({
  block,
  component,
  fieldKey,
  value,
  stringValue,
  placeholder,
  readOnly,
  formContext,
  runtimeServices,
  pageContext,
  blockPath,
  t,
}: {
  block: DslBlockV3;
  component: RuntimeFieldComponent;
  fieldKey: string;
  value: unknown;
  stringValue: string;
  placeholder: string;
  readOnly: boolean;
  formContext: RuntimeFormContextValue | null;
  runtimeServices?: RuntimeExecutionServices;
  pageContext: RuntimePageContext;
  blockPath: string[];
  t: (entry: Record<string, string>) => string;
}) {
  const baseClass =
    'mt-2 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm text-slate-900 outline-none focus:border-blue-400';
  const controlId = getRuntimeFieldControlId(block, component);

  if (component === 'textarea') {
    return (
      <textarea
        id={controlId}
        className={`${baseClass} min-h-20 resize-y`}
        data-testid={`runtime-textarea-${block.id}`}
        name={fieldKey}
        placeholder={placeholder}
        readOnly={readOnly}
        value={stringValue}
        onChange={(event) => formContext?.setValue(fieldKey, event.target.value)}
      />
    );
  }

  if (component === 'select') {
    const options = getRuntimeSelectOptions(block.props?.options);
    return (
      <select
        id={controlId}
        className={baseClass}
        data-testid={`runtime-select-${block.id}`}
        name={fieldKey}
        disabled={readOnly}
        value={stringValue}
        onChange={(event) => formContext?.setValue(fieldKey, event.target.value)}
      >
        <option value="">{placeholder || t(DESIGNER_I18N.unified.runtime.selectPlaceholder)}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    );
  }

  if (component === 'picker') {
    return (
      <RuntimePickerControl
        baseClass={baseClass}
        block={block}
        blockPath={blockPath}
        controlId={controlId}
        fieldKey={fieldKey}
        formContext={formContext}
        pageContext={pageContext}
        placeholder={placeholder}
        readOnly={readOnly}
        runtimeServices={runtimeServices}
        stringValue={stringValue}
      />
    );
  }

  if (component === 'radio') {
    const options = getRuntimeSelectOptions(block.props?.options);
    return (
      <div
        id={controlId}
        className="mt-2 flex flex-wrap gap-3"
        data-testid={`runtime-radio-${block.id}`}
        role="radiogroup"
      >
        {options.map((option) => {
          const optionId = `${controlId}-${option.value}`;
          return (
            <label
              key={option.value}
              className="inline-flex items-center gap-1.5 text-sm text-slate-700"
              htmlFor={optionId}
            >
              <input
                id={optionId}
                type="radio"
                className="h-4 w-4 border-slate-300 text-blue-600 focus:ring-blue-400"
                data-testid={`runtime-radio-${block.id}-${option.value}`}
                name={fieldKey}
                disabled={readOnly}
                checked={stringValue === option.value}
                value={option.value}
                onChange={(event) => formContext?.setValue(fieldKey, event.target.value)}
              />
              {option.label}
            </label>
          );
        })}
        {options.length ? null : (
          <div className="text-xs text-slate-500" data-testid={`runtime-radio-empty-${block.id}`}>
            {t(DESIGNER_I18N.unified.runtime.noOptionsConfigured)}
          </div>
        )}
      </div>
    );
  }

  if (component === 'rich-text') {
    return (
      <div className="mt-2 overflow-hidden rounded-md border border-slate-200 bg-white">
        <div
          className="flex gap-1 border-b border-slate-100 bg-slate-50 px-2 py-1 text-xs font-semibold text-slate-500"
          data-testid={`runtime-rich-text-toolbar-${block.id}`}
        >
          <span>B</span>
          <span>I</span>
          <span>{t(DESIGNER_I18N.unified.runtime.link)}</span>
        </div>
        <textarea
          id={controlId}
          className="min-h-28 w-full resize-y border-0 px-2 py-1.5 text-sm text-slate-900 outline-none focus:ring-0"
          data-testid={`runtime-rich-text-${block.id}`}
          name={fieldKey}
          placeholder={placeholder}
          readOnly={readOnly}
          value={stringValue}
          onChange={(event) => formContext?.setValue(fieldKey, event.target.value)}
        />
      </div>
    );
  }

  if (component === 'checkbox' || component === 'switch') {
    return (
      <input
        id={controlId}
        type="checkbox"
        role={component === 'switch' ? 'switch' : undefined}
        className="mt-2 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-400"
        data-testid={`runtime-${component}-${block.id}`}
        name={fieldKey}
        disabled={readOnly}
        checked={Boolean(value)}
        onChange={(event) => formContext?.setValue(fieldKey, event.target.checked)}
      />
    );
  }

  if (component === 'upload') {
    const accept = getStringProp(block.props?.accept);
    const multiple = Boolean(block.props?.multiple);
    const maxFiles = getRuntimeNumber(block.props?.maxFiles);
    const selectedFiles = Array.isArray(value)
      ? value.map((item) => String(item)).filter(Boolean)
      : [];
    return (
      <div className="mt-2 space-y-2">
        <input
          id={controlId}
          type="file"
          className={baseClass}
          data-testid={`runtime-upload-${block.id}`}
          name={fieldKey}
          accept={accept || undefined}
          multiple={multiple}
          disabled={readOnly}
          onChange={(event) => {
            const files = Array.from(event.target.files ?? []);
            const effectiveLimit = maxFiles && maxFiles > 0 ? maxFiles : files.length;
            const selected = files.slice(0, effectiveLimit).map((file) => file.name);
            formContext?.setValue(fieldKey, selected);
          }}
        />
        {selectedFiles.length ? (
          <ul
            className="space-y-1 text-xs text-slate-600"
            data-testid={`runtime-upload-files-${block.id}`}
          >
            {selectedFiles.map((fileName) => (
              <li key={fileName} className="rounded bg-slate-50 px-2 py-1">
                {fileName}
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    );
  }

  const inputType = component === 'date' ? 'date' : component === 'number' ? 'number' : 'text';
  return (
    <input
      id={controlId}
      type={inputType}
      className={baseClass}
      data-testid={`runtime-input-${block.id}`}
      name={fieldKey}
      placeholder={placeholder}
      readOnly={readOnly}
      value={stringValue}
      onChange={(event) => formContext?.setValue(fieldKey, event.target.value)}
    />
  );
}

function renderRuntimeFilterControl({
  block,
  blockPath,
  component,
  fieldKey,
  filterValue,
  stringValue,
  placeholder,
  listContext,
  pageContext,
  runtimeServices,
  t,
}: {
  block: DslBlockV3;
  blockPath: string[];
  component: RuntimeFieldComponent;
  fieldKey: string;
  filterValue: unknown;
  stringValue: string;
  placeholder: string;
  listContext: RuntimeSelectionContextValue | null;
  pageContext: RuntimePageContext;
  runtimeServices?: RuntimeExecutionServices;
  t: (entry: Record<string, string>) => string;
}) {
  const baseClass =
    'mt-2 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm text-slate-900 outline-none focus:border-blue-400';
  const setFilterValue = (value: unknown) => listContext?.setFilterValue(fieldKey, value);

  if (component === 'picker') {
    return (
      <RuntimePickerControl
        baseClass={baseClass}
        block={block}
        blockPath={blockPath}
        controlId={getRuntimeFieldControlId(block, component)}
        fieldKey={fieldKey}
        formContext={null}
        onValueChange={setFilterValue}
        pageContext={pageContext}
        placeholder={placeholder || t(DESIGNER_I18N.unified.runtime.allRecords)}
        readOnly={false}
        runtimeServices={runtimeServices}
        stringValue={stringValue}
      />
    );
  }

  if (component === 'select') {
    const options = getRuntimeSelectOptions(block.props?.options);
    return (
      <select
        className={baseClass}
        data-testid={`runtime-filter-select-${block.id}`}
        name={fieldKey}
        value={stringValue}
        onChange={(event) => setFilterValue(event.target.value)}
      >
        <option value="">{placeholder || t(DESIGNER_I18N.unified.runtime.all)}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    );
  }

  if (component === 'checkbox' || component === 'switch') {
    return (
      <input
        type="checkbox"
        role={component === 'switch' ? 'switch' : undefined}
        className="mt-2 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-400"
        data-testid={`runtime-filter-${component}-${block.id}`}
        name={fieldKey}
        checked={Boolean(filterValue)}
        onChange={(event) => setFilterValue(event.target.checked)}
      />
    );
  }

  const inputType = component === 'date' ? 'date' : component === 'number' ? 'number' : 'text';
  return (
    <input
      type={inputType}
      className={baseClass}
      data-testid={`runtime-filter-input-${block.id}`}
      name={fieldKey}
      placeholder={placeholder}
      value={stringValue}
      onChange={(event) => setFilterValue(event.target.value)}
    />
  );
}

function RuntimePickerControl({
  baseClass,
  block,
  blockPath,
  controlId,
  fieldKey,
  formContext,
  onValueChange,
  pageContext,
  placeholder,
  readOnly,
  runtimeServices,
  stringValue,
}: {
  baseClass: string;
  block: DslBlockV3;
  blockPath: string[];
  controlId: string;
  fieldKey: string;
  formContext: RuntimeFormContextValue | null;
  onValueChange?: (value: string) => void;
  pageContext: RuntimePageContext;
  placeholder: string;
  readOnly: boolean;
  runtimeServices?: RuntimeExecutionServices;
  stringValue: string;
}) {
  const t = useRuntimeText();
  const staticOptions = getRuntimeSelectOptions(block.props?.options);
  const [dynamicOptions, setDynamicOptions] = React.useState<RuntimeSelectOption[]>([]);
  const [searchText, setSearchText] = React.useState('');
  const [loading, setLoading] = React.useState(false);
  const [runtimeError, setRuntimeError] = React.useState<RuntimeExecutionIssue | null>(null);
  const pickerSignature = JSON.stringify({
    dataSource: block.dataSource ?? null,
    pickerDataSource: block.props?.pickerDataSource,
    pickerSource: block.props?.pickerSource,
    pickerQueryCode: block.props?.pickerQueryCode,
    valueField: block.props?.valueField,
    displayField: block.props?.displayField,
    pageSize: block.props?.pageSize,
    limit: block.props?.limit,
    parameters: block.props?.parameters,
    pickerParameters: block.props?.pickerParameters,
    searchable: block.props?.searchable,
    searchField: block.props?.searchField,
    searchParameter: block.props?.searchParameter,
  });
  const shouldLoadOptions = Boolean(
    runtimeServices?.loadPickerOptions && hasExecutablePickerDataSource(block),
  );
  const isSearchablePicker = shouldLoadOptions && getBooleanProp(block.props?.searchable);

  React.useEffect(() => {
    if (!shouldLoadOptions || !runtimeServices?.loadPickerOptions) {
      setDynamicOptions([]);
      setRuntimeError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setRuntimeError(null);
    void runtimeServices
      .loadPickerOptions(
        block,
        getRuntimeBlockContext(block, pageContext, blockPath, {
          pickerSearch: isSearchablePicker ? searchText.trim() || undefined : undefined,
        }),
      )
      .then((options) => {
        if (!cancelled) setDynamicOptions(normalizeRuntimePickerOptions(options));
      })
      .catch((loadError: unknown) => {
        if (!cancelled) {
          setDynamicOptions([]);
          setRuntimeError(
            normalizeRuntimeExecutionError(
              loadError,
              t(DESIGNER_I18N.unified.runtime.pickerOptionsFailed),
            ),
          );
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [
    block,
    blockPath,
    isSearchablePicker,
    pageContext,
    pickerSignature,
    runtimeServices,
    searchText,
    shouldLoadOptions,
  ]);

  const options = shouldLoadOptions ? dynamicOptions : staticOptions;
  const meta = getRuntimePickerMeta(block);
  const searchPlaceholder =
    getStringProp(block.props?.searchPlaceholder) || t(DESIGNER_I18N.unified.runtime.searchRecords);

  return (
    <div className="mt-2 space-y-1">
      {isSearchablePicker ? (
        <input
          type="search"
          className={baseClass}
          data-testid={`runtime-picker-search-${block.id}`}
          placeholder={searchPlaceholder}
          value={searchText}
          disabled={readOnly}
          onChange={(event) => setSearchText(event.target.value)}
        />
      ) : null}
      <select
        id={controlId}
        className={baseClass}
        data-testid={`runtime-picker-${block.id}`}
        name={fieldKey}
        disabled={readOnly || loading}
        value={stringValue}
        onChange={(event) => {
          if (onValueChange) {
            onValueChange(event.target.value);
            return;
          }
          formContext?.setValue(fieldKey, event.target.value);
        }}
      >
        <option value="">
          {loading
            ? t(DESIGNER_I18N.unified.runtime.loadingRecords)
            : placeholder || t(DESIGNER_I18N.unified.runtime.selectRecord)}
        </option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {meta ? (
        <div className="text-xs text-slate-500" data-testid={`runtime-picker-meta-${block.id}`}>
          {meta}
        </div>
      ) : null}
      {loading ? (
        <div className="text-xs text-blue-600" data-testid={`runtime-picker-loading-${block.id}`}>
          Loading records
        </div>
      ) : null}
      {runtimeError ? (
        <div
          className="text-xs font-medium text-red-600"
          data-testid={`runtime-picker-error-${block.id}`}
          data-error-kind={runtimeError.kind}
          data-error-code={runtimeError.code || undefined}
        >
          {runtimeError.message}
        </div>
      ) : null}
    </div>
  );
}

type RuntimeFieldComponent =
  | 'input'
  | 'textarea'
  | 'select'
  | 'date'
  | 'number'
  | 'checkbox'
  | 'switch'
  | 'radio'
  | 'upload'
  | 'picker'
  | 'rich-text';

/**
 * `field` blocks whose configured component is executed by the *designer runtime* and
 * therefore cannot be represented by a platform `FieldConfig`, so they stay on the
 * designer's own renderer even when model metadata is available (true-WYSIWYG path).
 *
 * Only `picker` qualifies. The designer's picker is a data-source component: its option
 * source is authored as `pickerDataSource` (`model` | `named-query`) + `pickerSource` /
 * `pickerQueryCode` / `valueField` / `displayField` / `pageSize` and executed through
 * `runtimeServices.loadPickerOptions` → `/api/query-builder/execute`, including
 * server-side `searchable` filtering. A platform `FieldConfig` can only bind a dict, a
 * model reference, or a static option list, so routing a picker through the platform
 * control would silently drop the authored option source (the "shared renderer silently
 * ignores DSL config" class of bug) — on top of the platform registry having no generic
 * `picker` at all, which is what produced "Unknown component: picker".
 *
 * The edit canvas has no designer runtime to execute that option source, so there
 * `buildPreviewFieldConfig` still translates `picker` into the closest real platform
 * picker (`resolvePickerPlatformComponent`) instead of rendering an error box.
 */
function isDesignerRuntimeOnlyComponent(block: DslBlockV3): boolean {
  return normalizeRuntimeFieldComponent(block.props?.component) === 'picker';
}

function normalizeRuntimeFieldComponent(value: unknown): RuntimeFieldComponent {
  const component = getStringProp(value).toLowerCase();
  if (
    component === 'textarea' ||
    component === 'select' ||
    component === 'date' ||
    component === 'number' ||
    component === 'checkbox' ||
    component === 'switch' ||
    component === 'radio' ||
    component === 'upload' ||
    component === 'picker' ||
    component === 'rich-text'
  ) {
    return component;
  }
  if (component === 'money' || component === 'moneyinput') return 'number';
  if (component === 'richtext' || component === 'rich_text') return 'rich-text';
  return 'input';
}

function getRuntimeFieldControlId(block: DslBlockV3, component: RuntimeFieldComponent): string {
  if (component === 'textarea') return `runtime-textarea-${block.id}`;
  if (component === 'select') return `runtime-select-${block.id}`;
  if (component === 'checkbox') return `runtime-checkbox-${block.id}`;
  if (component === 'switch') return `runtime-switch-${block.id}`;
  if (component === 'radio') return `runtime-radio-${block.id}`;
  if (component === 'upload') return `runtime-upload-${block.id}`;
  if (component === 'picker') return `runtime-picker-${block.id}`;
  if (component === 'rich-text') return `runtime-rich-text-${block.id}`;
  return `runtime-input-${block.id}`;
}

interface RuntimeSelectOption {
  label: string;
  value: string;
}

function normalizeRuntimePickerOptions(options: RuntimePickerOption[]): RuntimeSelectOption[] {
  return options.flatMap((option) => {
    const value = getStringProp(option.value);
    const label = getStringProp(option.label) || value;
    return value ? [{ label, value }] : [];
  });
}

function getRuntimeSelectOptions(value: unknown): RuntimeSelectOption[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean') {
      const optionValue = String(item);
      return [{ label: optionValue, value: optionValue }];
    }
    if (!item || typeof item !== 'object') return [];

    const option = item as Record<string, unknown>;
    const optionValue = getStringProp(option.value || option.code || option.key);
    const optionLabel = getStringProp(option.label || option.name || option.title) || optionValue;
    return optionValue ? [{ label: optionLabel, value: optionValue }] : [];
  });
}

function hasExecutablePickerDataSource(block: DslBlockV3): boolean {
  return Boolean(getRuntimePickerDataSourceMode(block));
}

function getRuntimePickerMeta(block: DslBlockV3): string {
  const mode = getRuntimePickerDataSourceMode(block);
  const source =
    mode === 'named-query'
      ? getStringProp(block.props?.pickerQueryCode) || getStringProp(block.props?.pickerSource)
      : getStringProp(block.props?.pickerSource);
  const displayField = getStringProp(block.props?.displayField);
  const valueField = getStringProp(block.props?.valueField);
  return [mode, source, displayField, valueField].filter(Boolean).join(' / ');
}

function getRuntimePickerDataSourceMode(block: DslBlockV3): string {
  const source = getStringProp(block.props?.pickerDataSource || block.dataSource?.type)
    .replace(/[_\s]/g, '-')
    .toLowerCase();
  if (source === 'model' || source === 'query-builder' || source === 'dynamic-model') {
    return 'model';
  }
  if (source === 'namedquery' || source === 'named-query' || source === 'nq') {
    return 'named-query';
  }
  return '';
}

function collectRuntimeFormFields(block: DslBlockV3): DslBlockV3[] {
  const childFields = block.blocks?.flatMap(collectRuntimeFormFields) ?? [];
  return block.blockType === 'field' ? [block, ...childFields] : childFields;
}

function collectRuntimeFilterFields(
  block: DslBlockV3,
  hasPermission?: (permissionCode: string) => boolean,
): DslBlockV3[] {
  if (hasPermission && !isRuntimeBlockPermissionAllowed(block, hasPermission)) return [];
  const childFields =
    block.blocks?.flatMap((child) => collectRuntimeFilterFields(child, hasPermission)) ?? [];
  return block.blockType === 'filter-field' ? [block, ...childFields] : childFields;
}

function collectRuntimeVisibleFormFields(
  block: DslBlockV3,
  values: Record<string, unknown>,
  hasPermission?: (permissionCode: string) => boolean,
): DslBlockV3[] {
  if (!isRuntimeBlockVisible(block, values)) return [];
  if (hasPermission && !isRuntimeBlockPermissionAllowed(block, hasPermission)) return [];
  if (block.blockType === 'repeater' || block.blockType === 'subform') return [];
  const childFields =
    block.blocks?.flatMap((child) =>
      collectRuntimeVisibleFormFields(child, values, hasPermission),
    ) ?? [];
  return block.blockType === 'field' ? [block, ...childFields] : childFields;
}

function collectRuntimeNestedFormValidationErrors(
  block: DslBlockV3,
  values: Record<string, unknown>,
  hasPermission?: (permissionCode: string) => boolean,
): Array<[string, string]> {
  if (!isRuntimeBlockVisible(block, values)) return [];
  if (hasPermission && !isRuntimeBlockPermissionAllowed(block, hasPermission)) return [];

  const ownErrors =
    block.blockType === 'repeater' || block.blockType === 'subform'
      ? collectRuntimeRowContainerValidationErrors(block, values, hasPermission)
      : [];
  const childErrors =
    block.blocks?.flatMap((child) =>
      collectRuntimeNestedFormValidationErrors(child, values, hasPermission),
    ) ?? [];
  return [...ownErrors, ...childErrors];
}

function collectRuntimeRowContainerValidationErrors(
  block: DslBlockV3,
  values: Record<string, unknown>,
  hasPermission?: (permissionCode: string) => boolean,
): Array<[string, string]> {
  const containerKey = block.field || block.id;
  const rowsValue = values[containerKey];
  const rows = Array.isArray(rowsValue)
    ? rowsValue.filter(isRecord).map((row) => ({ ...row }))
    : getRuntimeRepeaterRows(block);

  return rows.flatMap((row, rowIndex) => {
    const fieldBlocks =
      block.blockType === 'repeater'
        ? getRuntimeRepeaterFieldBlocks(block).filter((fieldBlock) =>
            isRuntimeRowFieldValidationAllowed(fieldBlock, row, hasPermission),
          )
        : block.blocks?.flatMap((child) =>
            collectRuntimeVisibleSubformRowFields(child, row, hasPermission),
          ) ?? [];

    return fieldBlocks.flatMap((fieldBlock) => {
      const fieldKey = getRuntimeRepeaterFieldKey(fieldBlock);
      const error = validateRuntimeFormField(fieldBlock, row[fieldKey]);
      return error
        ? [
            [getRuntimeNestedFieldErrorKey(containerKey, rowIndex, fieldKey), error] as [
              string,
              string,
            ],
          ]
        : [];
    });
  });
}

function collectRuntimeVisibleSubformRowFields(
  block: DslBlockV3,
  row: Record<string, unknown>,
  hasPermission?: (permissionCode: string) => boolean,
): DslBlockV3[] {
  if (!isRuntimeBlockVisible(block, row)) return [];
  if (hasPermission && !isRuntimeBlockPermissionAllowed(block, hasPermission)) return [];
  if (block.blockType === 'field') return [block];
  if (block.blockType === 'repeater' || block.blockType === 'subform') return [];
  return (
    block.blocks?.flatMap((child) =>
      collectRuntimeVisibleSubformRowFields(child, row, hasPermission),
    ) ?? []
  );
}

function isRuntimeRowFieldValidationAllowed(
  block: DslBlockV3,
  row: Record<string, unknown>,
  hasPermission?: (permissionCode: string) => boolean,
): boolean {
  return (
    isRuntimeBlockVisible(block, row) &&
    (!hasPermission || isRuntimeBlockPermissionAllowed(block, hasPermission))
  );
}

function getRuntimeNestedFieldErrorKey(
  containerKey: string,
  rowIndex: number,
  fieldKey: string,
): string {
  return `${containerKey}.${rowIndex}.${fieldKey}`;
}

function validateRuntimeFormField(block: DslBlockV3, value: unknown): string | null {
  if (block.props?.required && isRuntimeFieldValueEmpty(value)) return 'Required';

  for (const rule of getRuntimeValidationRules(block.props?.validationRules)) {
    const error = validateRuntimeRule(rule, value);
    if (error) return error;
  }

  return null;
}

interface RuntimeValidationRule {
  type: string;
  value?: unknown;
  message?: string;
}

function getRuntimeValidationRules(value: unknown): RuntimeValidationRule[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!item || typeof item !== 'object') return [];
    const rule = item as Record<string, unknown>;
    const type = getStringProp(rule.type);
    return type ? [{ type, value: rule.value, message: getStringProp(rule.message) }] : [];
  });
}

function validateRuntimeRule(rule: RuntimeValidationRule, value: unknown): string | null {
  const type = rule.type.toLowerCase();
  if (type === 'required') {
    return isRuntimeFieldValueEmpty(value) ? rule.message || 'Required' : null;
  }
  if (type === 'minlength' || type === 'min_length') {
    const minimumLength = getRuntimeNumber(rule.value);
    if (minimumLength === null || isRuntimeFieldValueEmpty(value)) return null;
    return String(value).length < minimumLength
      ? rule.message || `Minimum length is ${minimumLength}`
      : null;
  }
  if (type === 'maxlength' || type === 'max_length') {
    const maximumLength = getRuntimeNumber(rule.value);
    if (maximumLength === null || isRuntimeFieldValueEmpty(value)) return null;
    return String(value).length > maximumLength
      ? rule.message || `Maximum length is ${maximumLength}`
      : null;
  }
  if (type === 'pattern' || type === 'regex') {
    const pattern = getStringProp(rule.value);
    if (!pattern || isRuntimeFieldValueEmpty(value)) return null;
    try {
      return new RegExp(pattern).test(String(value)) ? null : rule.message || 'Invalid format';
    } catch {
      return rule.message || 'Invalid format';
    }
  }
  return null;
}

function isRuntimeBlockVisible(
  block: DslBlockV3,
  values: Record<string, unknown> | undefined,
): boolean {
  const rule = block.props?.visibleWhen;
  if (rule === undefined || rule === null) return true;
  if (!values) return true;
  return evaluateRuntimeVisibleWhen(rule, values);
}

function isRuntimeBlockDisabled(
  block: DslBlockV3,
  values: Record<string, unknown> | undefined,
): boolean {
  const rule = block.props?.disabledWhen;
  if (rule === undefined || rule === null) return false;
  if (!values) return false;
  return evaluateRuntimeVisibleWhen(rule, values);
}

function evaluateRuntimeVisibleWhen(rule: unknown, values: Record<string, unknown>): boolean {
  if (Array.isArray(rule)) return rule.every((item) => evaluateRuntimeVisibleWhen(item, values));
  if (!isRecord(rule)) return Boolean(rule);

  if (Array.isArray(rule.all)) {
    return rule.all.every((item) => evaluateRuntimeVisibleWhen(item, values));
  }
  if (Array.isArray(rule.any)) {
    return rule.any.some((item) => evaluateRuntimeVisibleWhen(item, values));
  }

  const field = getStringProp(rule.field);
  if (!field) return true;

  const actualValue = getRuntimeConditionValue(values, field);
  const expectedValue = rule.value;
  const operator = normalizeRuntimeVisibleOperator(rule.operator);

  if (operator === 'empty') return isRuntimeFieldValueEmpty(actualValue);
  if (operator === 'notempty') return !isRuntimeFieldValueEmpty(actualValue);
  if (operator === 'notequals') return !runtimeValuesEqual(actualValue, expectedValue);
  if (operator === 'contains') return runtimeValueContains(actualValue, expectedValue);
  if (operator === 'in') return runtimeValueContains(expectedValue, actualValue);
  if (operator === 'notin') return !runtimeValueContains(expectedValue, actualValue);
  if (operator === 'gt') return compareRuntimeNumbers(actualValue, expectedValue) > 0;
  if (operator === 'gte') return compareRuntimeNumbers(actualValue, expectedValue) >= 0;
  if (operator === 'lt') return compareRuntimeNumbers(actualValue, expectedValue) < 0;
  if (operator === 'lte') return compareRuntimeNumbers(actualValue, expectedValue) <= 0;

  return runtimeValuesEqual(actualValue, expectedValue);
}

function getRuntimeConditionValue(values: Record<string, unknown>, field: string): unknown {
  if (Object.prototype.hasOwnProperty.call(values, field)) return values[field];
  return field.split('.').reduce<unknown>((current, part) => {
    if (!isRecord(current)) return undefined;
    return current[part];
  }, values);
}

function normalizeRuntimeVisibleOperator(value: unknown): string {
  const operator = getStringProp(value).replace(/[_\s-]/g, '').toLowerCase();
  if (!operator) return 'equals';
  if (operator === 'neq' || operator === 'ne' || operator === 'notequal') return 'notequals';
  if (operator === 'notempty' || operator === 'exists') return 'notempty';
  if (operator === 'isempty' || operator === 'blank') return 'empty';
  if (operator === 'greaterthan') return 'gt';
  if (operator === 'greaterthanorequal') return 'gte';
  if (operator === 'lessthan') return 'lt';
  if (operator === 'lessthanorequal') return 'lte';
  return operator;
}

function runtimeValuesEqual(actualValue: unknown, expectedValue: unknown): boolean {
  if (Array.isArray(actualValue)) {
    return actualValue.some((item) => runtimeValuesEqual(item, expectedValue));
  }
  if (Array.isArray(expectedValue)) {
    return expectedValue.some((item) => runtimeValuesEqual(actualValue, item));
  }
  return String(actualValue ?? '') === String(expectedValue ?? '');
}

function runtimeValueContains(container: unknown, item: unknown): boolean {
  if (Array.isArray(container)) {
    return container.some((value) => runtimeValuesEqual(value, item));
  }
  return String(container ?? '').includes(String(item ?? ''));
}

function compareRuntimeNumbers(actualValue: unknown, expectedValue: unknown): number {
  const actualNumber = getRuntimeNumber(actualValue);
  const expectedNumber = getRuntimeNumber(expectedValue);
  if (actualNumber === null || expectedNumber === null) return Number.NaN;
  return actualNumber - expectedNumber;
}

function getRuntimeNumber(value: unknown): number | null {
  const nextValue = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(nextValue) ? nextValue : null;
}

function isRuntimeFieldValueEmpty(value: unknown): boolean {
  if (value === undefined || value === null) return true;
  if (typeof value === 'string') return value.trim() === '';
  if (typeof value === 'boolean') return !value;
  if (Array.isArray(value)) return value.length === 0;
  return false;
}

function RuntimeRepeater({ block }: RuntimeBlockProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
  const isVisible = isRuntimeBlockVisible(block, formContext?.values);

  const repeaterKey = block.field || block.id;
  const fieldBlocks = getRuntimeRepeaterFieldBlocks(block);
  const [rows, setRows] = React.useState<Array<Record<string, unknown>>>(() =>
    getRuntimeRepeaterRows(block),
  );
  const hasSeededFormValue = React.useRef(false);

  React.useEffect(() => {
    if (!isVisible || !formContext || hasSeededFormValue.current) return;
    hasSeededFormValue.current = true;
    formContext.setValue(repeaterKey, rows);
  }, [formContext, isVisible, repeaterKey, rows]);

  const updateRows = React.useCallback(
    (resolveNextRows: (currentRows: Array<Record<string, unknown>>) => Array<Record<string, unknown>>) => {
      setRows((currentRows) => {
        const nextRows = resolveNextRows(currentRows).map((row) => ({ ...row }));
        formContext?.setValue(repeaterKey, nextRows);
        return nextRows;
      });
    },
    [formContext, repeaterKey],
  );

  const updateRowField = React.useCallback(
    (rowIndex: number, fieldBlock: DslBlockV3, value: unknown) => {
      const fieldKey = getRuntimeRepeaterFieldKey(fieldBlock);
      updateRows((currentRows) =>
        currentRows.map((row, index) =>
          index === rowIndex
            ? {
                ...row,
                [fieldKey]: value,
              }
            : row,
        ),
      );
    },
    [updateRows],
  );

  if (!isVisible) return null;

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-repeater-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 pb-3">
        <div className="min-w-0">
          <div className="text-sm font-semibold text-slate-900">{getBlockLabel(block)}</div>
          <div className="text-xs text-slate-500">{rows.length} row(s)</div>
        </div>
        <button
          type="button"
          className="rounded-md border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          data-testid={`runtime-repeater-add-${block.id}`}
          onClick={() => updateRows((currentRows) => [...currentRows, {}])}
        >
          Add row
        </button>
      </div>
      <div className="mt-3 space-y-3">
        {rows.map((row, rowIndex) => (
          <div
            key={rowIndex}
            className="rounded-md border border-slate-100 bg-slate-50 p-3"
            data-testid={`runtime-repeater-row-${block.id}-${rowIndex}`}
          >
            <div className="mb-2 flex items-center justify-between gap-3">
              <span className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                Row {rowIndex + 1}
              </span>
              <button
                type="button"
                className="rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-500 hover:text-red-600"
                data-testid={`runtime-repeater-remove-${block.id}-${rowIndex}`}
                onClick={() =>
                  updateRows((currentRows) => currentRows.filter((_, index) => index !== rowIndex))
                }
              >
                Remove
              </button>
            </div>
            <div className="grid grid-cols-12 gap-3">
              {fieldBlocks.map((fieldBlock) => (
                <RuntimeRepeaterField
                  key={fieldBlock.id}
                  block={block}
                  fieldBlock={fieldBlock}
                  row={row}
                  rowIndex={rowIndex}
                  error={
                    formContext?.errors[
                      getRuntimeNestedFieldErrorKey(
                        repeaterKey,
                        rowIndex,
                        getRuntimeRepeaterFieldKey(fieldBlock),
                      )
                    ]
                  }
                  onChange={updateRowField}
                />
              ))}
            </div>
          </div>
        ))}
        {rows.length ? null : (
          <div
            className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
            data-testid={`runtime-repeater-empty-${block.id}`}
          >
            No rows
          </div>
        )}
      </div>
    </section>
  );
}

function RuntimeRepeaterField({
  block,
  fieldBlock,
  row,
  rowIndex,
  onChange,
  error,
  controlIdPrefix = 'runtime-repeater-control',
  testIdPrefix = 'runtime-repeater-input',
}: {
  block: DslBlockV3;
  fieldBlock: DslBlockV3;
  row: Record<string, unknown>;
  rowIndex: number;
  onChange: (rowIndex: number, fieldBlock: DslBlockV3, value: unknown) => void;
  error?: string;
  controlIdPrefix?: string;
  testIdPrefix?: string;
}) {
  const t = useRuntimeText();
  const fieldKey = getRuntimeRepeaterFieldKey(fieldBlock);
  const component = normalizeRuntimeFieldComponent(fieldBlock.props?.component);
  const value = row[fieldKey];
  const stringValue = typeof value === 'string' || typeof value === 'number' ? String(value) : '';
  const readOnly = Boolean(fieldBlock.props?.readOnly);
  const controlId = `${controlIdPrefix}-${block.id}-${rowIndex}-${fieldBlock.id}`;
  const inputTestId = `${testIdPrefix}-${block.id}-${rowIndex}-${fieldBlock.id}`;
  const baseClass =
    'mt-1.5 w-full rounded-md border border-slate-200 bg-white px-2 py-1.5 text-sm text-slate-900 outline-none focus:border-blue-400';

  return (
    <label
      className="rounded-md border border-slate-100 bg-white px-3 py-2 text-sm"
      htmlFor={controlId}
      style={getSpanGridStyle(fieldBlock)}
    >
      <span className="block text-xs font-medium text-slate-600">{getBlockLabel(fieldBlock)}</span>
      {component === 'textarea' || component === 'rich-text' ? (
        <textarea
          id={controlId}
          className={`${baseClass} min-h-16 resize-y`}
          data-testid={inputTestId}
          readOnly={readOnly}
          value={stringValue}
          onChange={(event) => onChange(rowIndex, fieldBlock, event.target.value)}
        />
      ) : component === 'select' || component === 'picker' ? (
        <select
          id={controlId}
          className={baseClass}
          data-testid={inputTestId}
          disabled={readOnly}
          value={stringValue}
          onChange={(event) => onChange(rowIndex, fieldBlock, event.target.value)}
        >
          <option value="">{t(DESIGNER_I18N.unified.runtime.selectPlaceholder)}</option>
          {getRuntimeSelectOptions(fieldBlock.props?.options).map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      ) : component === 'checkbox' || component === 'switch' ? (
        <input
          id={controlId}
          type="checkbox"
          role={component === 'switch' ? 'switch' : undefined}
          className="mt-2 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-400"
          data-testid={inputTestId}
          disabled={readOnly}
          checked={Boolean(value)}
          onChange={(event) => onChange(rowIndex, fieldBlock, event.target.checked)}
        />
      ) : component === 'radio' ? (
        <div className="mt-2 flex flex-wrap gap-3" data-testid={inputTestId} role="radiogroup">
          {getRuntimeSelectOptions(fieldBlock.props?.options).map((option) => (
            <span key={option.value} className="inline-flex items-center gap-1.5">
              <input
                id={`${controlId}-${option.value}`}
                type="radio"
                name={`${block.id}-${rowIndex}-${fieldKey}`}
                disabled={readOnly}
                checked={stringValue === option.value}
                value={option.value}
                onChange={(event) => onChange(rowIndex, fieldBlock, event.target.value)}
              />
              {option.label}
            </span>
          ))}
        </div>
      ) : (
        <input
          id={controlId}
          type={component === 'date' ? 'date' : component === 'number' ? 'number' : 'text'}
          className={baseClass}
          data-testid={inputTestId}
          readOnly={readOnly}
          value={stringValue}
          onChange={(event) => onChange(rowIndex, fieldBlock, event.target.value)}
        />
      )}
      {error ? (
        <span
          className="mt-1 block text-xs font-medium text-red-600"
          data-testid={`${testIdPrefix}-error-${block.id}-${rowIndex}-${fieldBlock.id}`}
        >
          {error}
        </span>
      ) : null}
    </label>
  );
}

function RuntimeSubform({ block }: RuntimeBlockProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
  const isVisible = isRuntimeBlockVisible(block, formContext?.values);

  const subformKey = block.field || block.id;
  const [rows, setRows] = React.useState<Array<Record<string, unknown>>>(() =>
    getRuntimeRepeaterRows(block),
  );
  const hasSeededFormValue = React.useRef(false);

  React.useEffect(() => {
    if (!isVisible || !formContext || hasSeededFormValue.current) return;
    hasSeededFormValue.current = true;
    formContext.setValue(subformKey, rows);
  }, [formContext, isVisible, rows, subformKey]);

  const updateRows = React.useCallback(
    (
      resolveNextRows: (
        currentRows: Array<Record<string, unknown>>,
      ) => Array<Record<string, unknown>>,
    ) => {
      setRows((currentRows) => {
        const nextRows = resolveNextRows(currentRows).map((row) => ({ ...row }));
        formContext?.setValue(subformKey, nextRows);
        return nextRows;
      });
    },
    [formContext, subformKey],
  );

  const updateRowField = React.useCallback(
    (rowIndex: number, fieldBlock: DslBlockV3, value: unknown) => {
      const fieldKey = getRuntimeRepeaterFieldKey(fieldBlock);
      updateRows((currentRows) =>
        currentRows.map((row, index) =>
          index === rowIndex
            ? {
                ...row,
                [fieldKey]: value,
              }
            : row,
        ),
      );
    },
    [updateRows],
  );

  if (!isVisible) return null;

  return (
    <section
      className="rounded-lg border border-slate-200 bg-white p-4"
      data-testid={`runtime-subform-${block.id}`}
      style={getSpanGridStyle(block)}
    >
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 pb-3">
        <div className="min-w-0">
          <div className="text-sm font-semibold text-slate-900">{getBlockLabel(block)}</div>
          <div className="text-xs text-slate-500">{rows.length} row editor(s)</div>
        </div>
        <button
          type="button"
          className="rounded-md border border-slate-200 px-2.5 py-1.5 text-xs font-medium text-slate-700 hover:bg-slate-50"
          data-testid={`runtime-subform-add-${block.id}`}
          onClick={() => updateRows((currentRows) => [...currentRows, {}])}
        >
          Add row
        </button>
      </div>
      <div className="mt-3 space-y-3">
        {rows.map((row, rowIndex) => (
          <div
            key={rowIndex}
            className="rounded-md border border-slate-100 bg-slate-50 p-3"
            data-testid={`runtime-subform-row-${block.id}-${rowIndex}`}
          >
            <div className="mb-2 flex items-center justify-between gap-3">
              <span className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                Row {rowIndex + 1}
              </span>
              <button
                type="button"
                className="rounded border border-slate-200 bg-white px-2 py-1 text-xs text-slate-500 hover:text-red-600"
                data-testid={`runtime-subform-remove-${block.id}-${rowIndex}`}
                onClick={() =>
                  updateRows((currentRows) => currentRows.filter((_, index) => index !== rowIndex))
                }
              >
                Remove
              </button>
            </div>
            <div className="space-y-3">
              {block.blocks?.map((child) => (
                <RuntimeSubformRowBlock
                  key={child.id}
                  parentBlock={block}
                  block={child}
                  row={row}
                  rowIndex={rowIndex}
                  errors={formContext?.errors ?? {}}
                  onChange={updateRowField}
                />
              ))}
            </div>
          </div>
        ))}
        {rows.length ? null : (
          <div
            className="rounded-md border border-dashed border-slate-200 px-3 py-4 text-sm text-slate-400"
            data-testid={`runtime-subform-empty-${block.id}`}
          >
            No rows
          </div>
        )}
      </div>
    </section>
  );
}

function RuntimeSubformRowBlock({
  parentBlock,
  block,
  row,
  rowIndex,
  errors,
  onChange,
}: {
  parentBlock: DslBlockV3;
  block: DslBlockV3;
  row: Record<string, unknown>;
  rowIndex: number;
  errors: Record<string, string>;
  onChange: (rowIndex: number, fieldBlock: DslBlockV3, value: unknown) => void;
}) {
  if (block.blockType === 'field') {
    const subformKey = parentBlock.field || parentBlock.id;
    const fieldKey = getRuntimeRepeaterFieldKey(block);
    return (
      <RuntimeRepeaterField
        block={parentBlock}
        fieldBlock={block}
        row={row}
        rowIndex={rowIndex}
        error={errors[getRuntimeNestedFieldErrorKey(subformKey, rowIndex, fieldKey)]}
        onChange={onChange}
        controlIdPrefix="runtime-subform-control"
        testIdPrefix="runtime-subform-input"
      />
    );
  }

  if (block.blockType === 'form-section' || block.blockType === 'detail-section') {
    return (
      <section
        className="rounded-md border border-slate-200 bg-white p-3"
        data-testid={`runtime-subform-section-${parentBlock.id}-${rowIndex}-${block.id}`}
        style={getSpanGridStyle(block)}
      >
        <RuntimeTitle block={block} />
        <div className="grid grid-cols-12 gap-3">
          {block.blocks?.map((child) => (
            <RuntimeSubformRowBlock
              key={child.id}
              parentBlock={parentBlock}
              block={child}
              row={row}
              rowIndex={rowIndex}
              errors={errors}
              onChange={onChange}
            />
          ))}
        </div>
      </section>
    );
  }

  if (block.blocks?.length) {
    return (
      <section
        className="rounded-md border border-slate-200 bg-white p-3"
        data-testid={`runtime-subform-block-${parentBlock.id}-${rowIndex}-${block.id}`}
      >
        <RuntimeTitle block={block} />
        <div className="grid grid-cols-12 gap-3">
          {block.blocks.map((child) => (
            <RuntimeSubformRowBlock
              key={child.id}
              parentBlock={parentBlock}
              block={child}
              row={row}
              rowIndex={rowIndex}
              errors={errors}
              onChange={onChange}
            />
          ))}
        </div>
      </section>
    );
  }

  return null;
}

function getRuntimeRepeaterRows(block: DslBlockV3): Array<Record<string, unknown>> {
  const rows = Array.isArray(block.props?.rows)
    ? block.props.rows
    : Array.isArray(block.dataSource?.rows)
      ? block.dataSource.rows
      : [{}];
  const normalizedRows = rows.filter(isRecord).map((row) => ({ ...row }));
  return normalizedRows.length ? normalizedRows : [{}];
}

function getRuntimeRepeaterFieldBlocks(block: DslBlockV3): DslBlockV3[] {
  return block.blocks?.filter((child) => child.blockType === 'field') ?? [];
}

function getRuntimeRepeaterFieldKey(block: DslBlockV3): string {
  return block.field || block.id;
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
          {getBlockLabel(block)}
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

function getRuntimeTableColumnBlocks(block: DslBlockV3): DslBlockV3[] {
  return block.blocks?.filter((child) => child.blockType === 'column') ?? [];
}

function getRuntimeTableRowActionBlocks(block: DslBlockV3): DslBlockV3[] {
  return (
    block.blocks?.filter(
      (child) => child.blockType === 'action' || child.region === 'row-actions',
    ) ?? []
  );
}

function RuntimeColumn({ block }: { block: DslBlockV3 }) {
  const hasPermission = React.useContext(RuntimePermissionContext);
  if (!isRuntimeBlockPermissionAllowed(block, hasPermission)) return null;

  return (
    <div
      className="border-r border-slate-200 px-3 py-2 text-xs font-semibold uppercase tracking-wide text-slate-500 last:border-r-0"
      data-testid={`runtime-column-${block.id}`}
    >
      {getBlockLabel(block)}
    </div>
  );
}

function getRuntimeTableRows(block: DslBlockV3): Array<Record<string, unknown>> {
  const rows = Array.isArray(block.props?.rows)
    ? block.props.rows
    : Array.isArray(block.dataSource?.rows)
      ? block.dataSource.rows
      : [];
  return rows.filter(isRecord);
}

function applyRuntimeListFilters(
  rows: Array<Record<string, unknown>>,
  listContext: RuntimeSelectionContextValue | null,
): Array<Record<string, unknown>> {
  if (!listContext?.filterBlocks.length) return rows;
  const activeFilters = listContext.filterBlocks.flatMap((filterBlock) => {
    const field = filterBlock.field || filterBlock.id;
    const value = listContext.filterValues[field];
    return isRuntimeFieldValueEmpty(value) ? [] : [{ filterBlock, field, value }];
  });
  if (!activeFilters.length) return rows;

  return rows.filter((row) =>
    activeFilters.every(({ filterBlock, field, value }) =>
      matchesRuntimeListFilter(row[field], value, getStringProp(filterBlock.props?.operator)),
    ),
  );
}

function matchesRuntimeListFilter(
  rawCellValue: unknown,
  rawFilterValue: unknown,
  operator: string,
): boolean {
  const cellValue = rawCellValue == null ? '' : String(rawCellValue);
  const filterValue = rawFilterValue == null ? '' : String(rawFilterValue);
  const normalizedOperator = operator || 'contains';

  if (normalizedOperator === 'equals') return cellValue === filterValue;
  if (normalizedOperator === 'gt') return Number(cellValue) > Number(filterValue);
  if (normalizedOperator === 'lt') return Number(cellValue) < Number(filterValue);
  if (normalizedOperator === 'between') {
    const [min, max] = filterValue.split(',').map((item) => Number(item.trim()));
    const numericValue = Number(cellValue);
    return Number.isFinite(min) && Number.isFinite(max) && numericValue >= min && numericValue <= max;
  }
  return cellValue.toLowerCase().includes(filterValue.toLowerCase());
}

function getRuntimeTableColumns(
  block: DslBlockV3,
  rows: Array<Record<string, unknown>>,
  columnBlocks = getRuntimeTableColumnBlocks(block),
  hasConfiguredColumns = Boolean(block.blocks?.some((child) => child.blockType === 'column')),
): string[] {
  const configuredColumns = columnBlocks.map((child) => child.field || child.id).filter(Boolean);
  if (hasConfiguredColumns) return configuredColumns;
  return Object.keys(rows[0] ?? {});
}

function getRuntimeRowId(row: Record<string, unknown>, index: number): string {
                            const id = row.pid ?? row.key ?? row._id ?? index;
  return String(id);
}

function formatRuntimeCell(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
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

interface RuntimeActionTestIds {
  wrapper: string;
  button: string;
  permission: string;
  confirm: string;
  status: string;
  error: string;
  errorHint: string;
  overlay: string;
}

interface RuntimeActionProps extends RuntimeBlockProps {
  currentRow?: Record<string, unknown>;
  currentRowId?: string;
  testIds?: Partial<RuntimeActionTestIds>;
}

function RuntimeAction({
  block,
  runtimeServices,
  pageContext,
  blockPath,
  currentRow,
  currentRowId,
  testIds,
}: RuntimeActionProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
  const selectionContext = React.useContext(RuntimeListSelectionContext);
  const hasPermission = React.useContext(RuntimePermissionContext);
  const locale = React.useContext(RuntimeLocaleContext);
  const t = useRuntimeText();
  const [pendingConfirm, setPendingConfirm] = React.useState(false);
  const [status, setStatus] = React.useState('');
  const [error, setError] = React.useState<RuntimeExecutionIssue | null>(null);
  const [executing, setExecuting] = React.useState(false);
  const [overlayKind, setOverlayKind] = React.useState<'modal' | 'drawer' | null>(null);
  const actionType = block.actionType || 'command';
  const href = getStringProp(block.props?.to);
  const permissionCode = getRuntimePermissionCode(block);
  const permissionAllowed = permissionCode ? hasPermission(permissionCode) : true;
  const conditionValues = getRuntimeActionConditionValues({
    formValues: formContext?.values,
    currentRow,
    currentRowId,
  });
  const visibleByCondition = isRuntimeBlockVisible(block, conditionValues);
  const disabledByCondition = isRuntimeBlockDisabled(block, conditionValues);
  const liveExecution = Boolean(
    runtimeServices?.executeAction &&
      shouldExecuteLiveAction(block) &&
      (actionType === 'command' || actionType === 'workflow'),
  );

  const executeAction = () => {
    if (!permissionAllowed || disabledByCondition || !visibleByCondition) return;

    if (formContext && shouldValidateFormBeforeAction(block) && !formContext.validate()) {
      setPendingConfirm(false);
      setStatus('');
      setError(null);
      setOverlayKind(null);
      return;
    }

    if (Boolean(block.props?.confirm) && !pendingConfirm) {
      setPendingConfirm(true);
      setStatus('');
      setError(null);
      setOverlayKind(null);
      return;
    }

    setPendingConfirm(false);
    setError(null);
    if (actionType === 'modal' || actionType === 'drawer') {
      setOverlayKind(actionType);
      setStatus(getActionStatus(block));
      return;
    }

    setOverlayKind(null);
    if (liveExecution && runtimeServices?.executeAction) {
      setStatus('');
      setExecuting(true);
      void runtimeServices
        .executeAction(
          block,
          getRuntimeBlockContext(block, pageContext, blockPath, {
            formValues: formContext?.values,
            selectedRows: selectionContext?.selectedRows,
            selectedRowIds: selectionContext?.selectedRowIds,
            currentRow,
            currentRowId,
            permissionCode: permissionCode || undefined,
          }),
        )
        .then((result) => {
          setStatus(result.status);
        })
        .catch((executionError: unknown) => {
          setError(normalizeRuntimeExecutionError(executionError));
        })
        .finally(() => {
          setExecuting(false);
        });
      return;
    }

    setStatus(getActionStatus(block));
  };
  const actionTestIds = getRuntimeActionTestIds(block, testIds);
  if (!visibleByCondition) return null;

  const disabled = executing || !permissionAllowed || disabledByCondition;

  return (
    <div className="min-w-0" data-testid={actionTestIds.wrapper}>
      <button
        type="button"
        className={`rounded-md px-3 py-1.5 text-sm font-medium ${
          disabled
            ? 'cursor-not-allowed bg-slate-300 text-slate-500'
            : pendingConfirm
              ? 'bg-amber-600 text-white'
              : 'bg-blue-600 text-white'
        }`}
        data-testid={actionTestIds.button}
        data-action-type={actionType}
        data-live-execution={liveExecution ? 'true' : undefined}
        data-href={href || undefined}
        data-permission-code={permissionCode || undefined}
        data-permission-allowed={permissionCode ? String(permissionAllowed) : undefined}
        data-condition-disabled={disabledByCondition ? 'true' : undefined}
        disabled={disabled}
        onClick={executeAction}
      >
        {pendingConfirm
          ? t(DESIGNER_I18N.unified.runtime.confirm)
          : executing
          ? t(DESIGNER_I18N.unified.runtime.running)
          : getBlockLabel(block, locale)}
      </button>
      {permissionCode ? (
        <div
          className={`mt-1 max-w-56 truncate text-xs ${
            permissionAllowed ? 'text-slate-500' : 'font-medium text-amber-700'
          }`}
          data-testid={actionTestIds.permission}
          data-permission-allowed={String(permissionAllowed)}
        >
          {permissionAllowed
            ? `Permission: ${permissionCode}`
            : `Requires permission: ${permissionCode}`}
        </div>
      ) : null}
      {pendingConfirm ? (
        <div
          className="mt-1 text-xs font-medium text-amber-700"
          data-testid={actionTestIds.confirm}
        >
          Click again to confirm
        </div>
      ) : null}
      {status ? (
        <div
          className="mt-1 max-w-56 truncate text-xs text-slate-500"
          data-testid={actionTestIds.status}
        >
          {status}
        </div>
      ) : null}
      {error ? (
        <div
          className="mt-1 max-w-56 truncate text-xs font-medium text-red-600"
          data-testid={actionTestIds.error}
          data-error-kind={error.kind}
          data-error-code={error.code || undefined}
        >
          {error.message}
          {error.hint ? (
            <div
              className="font-normal text-red-500"
              data-testid={actionTestIds.errorHint}
            >
              {error.hint}
            </div>
          ) : null}
        </div>
      ) : null}
      {overlayKind ? (
        <RuntimeActionOverlay block={block} kind={overlayKind} testId={actionTestIds.overlay} />
      ) : null}
    </div>
  );
}

function getRuntimeActionTestIds(
  block: DslBlockV3,
  overrides?: Partial<RuntimeActionTestIds>,
): RuntimeActionTestIds {
  return {
    wrapper: `runtime-action-wrapper-${block.id}`,
    button: `runtime-action-${block.id}`,
    permission: `runtime-action-permission-${block.id}`,
    confirm: `runtime-action-confirm-${block.id}`,
    status: `runtime-action-status-${block.id}`,
    error: `runtime-action-error-${block.id}`,
    errorHint: `runtime-action-error-hint-${block.id}`,
    overlay: `runtime-action-overlay-${block.id}`,
    ...overrides,
  };
}

function RuntimeActionOverlay({
  block,
  kind,
  testId,
}: {
  block: DslBlockV3;
  kind: 'modal' | 'drawer';
  testId: string;
}) {
  const title = getStringProp(block.props?.title) || getBlockLabel(block);
  const pageKey = getStringProp(block.props?.pageKey);

  return (
    <div
      className={`mt-2 rounded-md border bg-white p-3 text-xs text-slate-600 shadow-sm ${
        kind === 'drawer' ? 'border-blue-200' : 'border-slate-200'
      }`}
      data-testid={testId}
      data-overlay-kind={kind}
    >
      <div className="font-semibold text-slate-900">{title}</div>
      {pageKey ? <div className="mt-1 font-mono text-slate-500">{pageKey}</div> : null}
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

function shouldValidateFormBeforeAction(block: DslBlockV3): boolean {
  if (block.props?.validateForm === false) return false;
  const actionType = block.actionType || 'command';
  return actionType === 'submit' || actionType === 'create' || actionType === 'command' || actionType === 'workflow';
}

function RuntimeWidget({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const [runtimeData, setRuntimeData] = React.useState<RuntimeWidgetData | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [runtimeError, setRuntimeError] = React.useState<RuntimeExecutionIssue | null>(null);
  const dataSourceSignature = JSON.stringify(block.dataSource ?? {});
  const shouldLoadData = Boolean(
    runtimeServices?.loadWidgetData && hasExecutableWidgetDataSource(block),
  );

  React.useEffect(() => {
    if (!shouldLoadData || !runtimeServices?.loadWidgetData) {
      setRuntimeData(null);
      setRuntimeError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setRuntimeError(null);
    void runtimeServices
      .loadWidgetData(block, getRuntimeBlockContext(block, pageContext, blockPath))
      .then((data) => {
        if (!cancelled) setRuntimeData(data);
      })
      .catch((loadError: unknown) => {
        if (!cancelled) {
          setRuntimeData(null);
          setRuntimeError(normalizeRuntimeExecutionError(loadError));
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [block, dataSourceSignature, runtimeServices, shouldLoadData]);

  const effectiveBlock = mergeWidgetData(block, runtimeData);
  const subtitle = getStringProp(block.props?.subtitle);
  // Body widgets (charts / table / markdown / progress) own their visual via
  // RuntimeWidgetBody. Progress in particular consumes props.value as a percentage,
  // so the number-card single-value box must not steal it; suppress it here.
  const value = isWidgetBodyType(block.widgetType)
    ? ''
    : getStringProp(effectiveBlock.props?.value);
  const emptyText = getStringProp(effectiveBlock.props?.emptyText);
  const staticErrorText = getStringProp(effectiveBlock.props?.errorText);
  const errorText = runtimeError?.message || staticErrorText;
  const drillDownTo = getStringProp(block.props?.drillDownTo);
  const model = getStringProp(block.dataSource?.model);
  const metric = getStringProp(block.dataSource?.metric);
  const source = runtimeData?.source;
  const meta = [source, model, metric].filter(Boolean).join(' / ');

  return (
    <div
      className="flex min-h-0 flex-col rounded-md border border-slate-200 bg-slate-50 p-3"
      data-testid={`runtime-widget-${block.id}`}
      style={getWidgetGridStyle(block)}
    >
      <div className="flex items-center justify-between gap-3">
        <div className="text-xs font-medium uppercase tracking-wide text-slate-500">
          {block.widgetType}
        </div>
        {meta ? (
          <div
            className="truncate text-xs text-slate-400"
            data-testid={`runtime-widget-meta-${block.id}`}
          >
            {meta}
          </div>
        ) : null}
      </div>
      <div className="mt-1 truncate text-lg font-semibold text-slate-900">
        {getBlockLabel(block)}
      </div>
      {subtitle ? (
        <div
          className="mt-0.5 truncate text-xs text-slate-500"
          data-testid={`runtime-widget-subtitle-${block.id}`}
        >
          {subtitle}
        </div>
      ) : null}
      {loading ? (
        <div
          className="mt-auto rounded-md border border-blue-200 bg-blue-50 px-3 py-2 text-xs font-medium text-blue-700"
          data-testid={`runtime-widget-loading-${block.id}`}
        >
          Loading data
        </div>
      ) : value ? (
        <div
          className="mt-auto pt-3 text-2xl font-semibold text-slate-950"
          data-testid={`runtime-widget-value-${block.id}`}
        >
          {value}
        </div>
      ) : errorText ? (
        <div
          className="mt-auto rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs font-medium text-red-700"
          data-testid={`runtime-widget-error-${block.id}`}
          data-error-kind={runtimeError?.kind || (staticErrorText ? 'static' : undefined)}
          data-error-code={runtimeError?.code || undefined}
        >
          {errorText}
          {runtimeError?.hint ? (
            <div
              className="mt-1 font-normal text-red-500"
              data-testid={`runtime-widget-error-hint-${block.id}`}
            >
              {runtimeError.hint}
            </div>
          ) : null}
        </div>
      ) : emptyText ? (
        <div
          className="mt-auto rounded-md border border-dashed border-slate-300 px-3 py-2 text-xs text-slate-500"
          data-testid={`runtime-widget-empty-${block.id}`}
        >
          {emptyText}
        </div>
      ) : (
        <RuntimeWidgetBody block={effectiveBlock} />
      )}
      {drillDownTo ? (
        <div
          className="mt-2 truncate text-xs font-medium text-blue-600"
          data-testid={`runtime-widget-drilldown-${block.id}`}
        >
          {drillDownTo}
        </div>
      ) : null}
    </div>
  );
}

// Widget types whose visual is rendered by RuntimeWidgetBody (a chart / table /
// markdown / progress bar) rather than the number-card value box. The RuntimeWidget
// wrapper consults this set so a body widget that happens to carry a `props.value`
// (e.g. progress reads props.value as its percentage) is not short-circuited into
// the number-card single-value display.
const WIDGET_BODY_TYPES = new Set([
  'bar-chart',
  'line-chart',
  'pie-chart',
  'area-chart',
  'progress',
  'table',
  'markdown',
]);

/**
 * Whether a widgetType owns its own visual body (chart / table / markdown /
 * progress) rather than the single-value number-card box. The 7 explicit body
 * types keep their hand-written representative previews; E1 extends this to ANY
 * SharedChartFactory chart type (radar / gauge / funnel / heatmap / …) so the
 * designer preview matches the live WidgetRenderer (which already supports all
 * types via getChartComponent). number-card is excluded — it IS the value box.
 */
function isChartWidgetType(widgetType: string): boolean {
  const normalized = normalizeChartType(widgetType);
  if (normalized === 'number-card') return false;
  return Boolean(getChartComponent(normalized));
}

function isWidgetBodyType(widgetType: string | undefined): boolean {
  if (typeof widgetType !== 'string') return false;
  return WIDGET_BODY_TYPES.has(widgetType) || isChartWidgetType(widgetType);
}

function RuntimeWidgetBody({ block }: { block: DslBlockV3 }) {
  if (block.widgetType === 'bar-chart') {
    return <RuntimeBarChart block={block} />;
  }
  if (block.widgetType === 'line-chart') {
    return <RuntimeLineChart block={block} />;
  }
  if (block.widgetType === 'pie-chart') {
    return <RuntimePieChart block={block} />;
  }
  if (block.widgetType === 'area-chart') {
    return <RuntimeAreaChart block={block} />;
  }
  if (block.widgetType === 'progress') {
    return <RuntimeProgressWidget block={block} />;
  }
  if (block.widgetType === 'table') {
    return <RuntimeWidgetTable block={block} />;
  }
  if (block.widgetType === 'markdown') {
    return <RuntimeMarkdownWidget block={block} />;
  }
  // E1 — any other SharedChartFactory chart type (radar / scatter / gauge /
  // funnel / heatmap / treemap / gantt / pareto / combo / …) gets a representative
  // generic chart preview instead of falling back to the number-card box. The live
  // page renders the real chart (WidgetRenderer → SharedChartFactory).
  if (block.widgetType && isChartWidgetType(block.widgetType)) {
    return <RuntimeGenericChartPreview block={block} />;
  }
  return null;
}

function RuntimeGenericChartPreview({ block }: { block: DslBlockV3 }) {
  const t = useRuntimeText();
  const chartType = normalizeChartType(block.widgetType ?? '');
  const dataSourceId = getStringProp(block.dataSource?.model) || getStringProp(block.dataSource?.metric);
  return (
    <div
      className="flex min-h-0 flex-1 flex-col rounded-md border border-slate-200 bg-white p-3"
      data-testid={`runtime-widget-chart-${block.id}`}
      data-chart-type={chartType}
    >
      <div className="mb-2 flex items-center gap-2">
        <span className="inline-flex items-center rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700">
          {chartType}
        </span>
        {dataSourceId ? (
          <span className="font-mono text-xs text-slate-500">{dataSourceId}</span>
        ) : null}
      </div>
      {/* Representative chart silhouette (the live page renders the real chart). */}
      <div className="flex h-16 items-end gap-1.5" aria-hidden="true">
        {[45, 70, 55, 85, 60, 75].map((h, i) => (
          <span key={i} className="flex-1 rounded-t bg-indigo-300" style={{ height: `${h}%` }} />
        ))}
      </div>
      <div className="mt-2 text-xs text-slate-400">
        {t(DESIGNER_I18N.unified.runtime.workbenchPreviewHint)}
      </div>
    </div>
  );
}

function RuntimeBarChart({ block }: { block: DslBlockV3 }) {
  const series = getSeries(block.props?.series);
  if (!series.length) return <RuntimeWidgetEmpty block={block} />;
  const maxValue = Math.max(...series.map((item) => item.value), 1);

  return (
    <div className="mt-auto flex items-end gap-2 pt-3">
      {series.map((item, index) => (
        <div
          key={`${item.label}-${index}`}
          className="flex min-w-0 flex-1 flex-col items-center gap-1"
          data-testid={`runtime-widget-bar-${block.id}-${index}`}
          data-value={String(item.value)}
        >
          <div
            className="w-full rounded-t bg-blue-500"
            style={{ height: `${Math.max(12, Math.round((item.value / maxValue) * 54))}px` }}
          />
          <div className="w-full truncate text-center text-[10px] text-slate-500">{item.label}</div>
        </div>
      ))}
    </div>
  );
}

function RuntimeLineChart({ block }: { block: DslBlockV3 }) {
  const series = getSeries(block.props?.series);
  if (!series.length) return <RuntimeWidgetEmpty block={block} />;
  const points = series.map((item) => item.value);
  const maxValue = Math.max(...points, 1);
  const width = 120;
  const height = 56;
  const polyline = points
    .map((value, index) => {
      const x = points.length === 1 ? 0 : (index / (points.length - 1)) * width;
      const y = height - (value / maxValue) * height;
      return `${x},${y}`;
    })
    .join(' ');

  return (
    <svg
      className="mt-auto h-16 w-full overflow-visible"
      viewBox={`0 0 ${width} ${height}`}
      data-testid={`runtime-widget-line-${block.id}`}
      data-points={points.join(',')}
      role="img"
      aria-label={getBlockLabel(block)}
    >
      <polyline fill="none" stroke="#2563eb" strokeWidth="3" points={polyline} />
    </svg>
  );
}

// Categorical palette for the pie slices. These map 1:1 to the slate/blue family
// already used by the bar/line mini-renderers so the widget stays on-token; pure
// SVG fills (no chart library) keep the runtime preview dependency-free.
const PIE_SLICE_COLORS = [
  'fill-blue-500',
  'fill-emerald-500',
  'fill-amber-500',
  'fill-violet-500',
  'fill-rose-500',
  'fill-cyan-500',
];

function polarToCartesian(cx: number, cy: number, r: number, angle: number): [number, number] {
  const rad = ((angle - 90) * Math.PI) / 180;
  return [cx + r * Math.cos(rad), cy + r * Math.sin(rad)];
}

function RuntimePieChart({ block }: { block: DslBlockV3 }) {
  const series = getSeries(block.props?.series);
  const total = series.reduce((sum, item) => sum + Math.max(item.value, 0), 0);
  if (!series.length || total <= 0) return <RuntimeWidgetEmpty block={block} />;

  const size = 64;
  const center = size / 2;
  const radius = center;
  let cursor = 0;

  return (
    <svg
      className="mt-auto h-16 w-full overflow-visible"
      viewBox={`0 0 ${size} ${size}`}
      data-testid={`runtime-widget-pie-${block.id}`}
      data-total={String(total)}
      data-slices={String(series.length)}
      role="img"
      aria-label={getBlockLabel(block)}
    >
      {series.map((item, index) => {
        const fraction = Math.max(item.value, 0) / total;
        const startAngle = cursor * 360;
        cursor += fraction;
        const endAngle = cursor * 360;
        // A single-slice pie (fraction === 1) cannot be drawn with one arc path
        // (start point === end point), so render it as a full circle instead.
        if (fraction >= 1) {
          return (
            <circle
              key={`${item.label}-${index}`}
              cx={center}
              cy={center}
              r={radius}
              className={PIE_SLICE_COLORS[index % PIE_SLICE_COLORS.length]}
              data-testid={`runtime-widget-pie-slice-${block.id}-${index}`}
              data-value={String(item.value)}
            />
          );
        }
        const [startX, startY] = polarToCartesian(center, center, radius, startAngle);
        const [endX, endY] = polarToCartesian(center, center, radius, endAngle);
        const largeArc = endAngle - startAngle > 180 ? 1 : 0;
        const path = `M ${center} ${center} L ${startX} ${startY} A ${radius} ${radius} 0 ${largeArc} 1 ${endX} ${endY} Z`;
        return (
          <path
            key={`${item.label}-${index}`}
            d={path}
            className={PIE_SLICE_COLORS[index % PIE_SLICE_COLORS.length]}
            data-testid={`runtime-widget-pie-slice-${block.id}-${index}`}
            data-value={String(item.value)}
          />
        );
      })}
    </svg>
  );
}

function RuntimeAreaChart({ block }: { block: DslBlockV3 }) {
  const series = getSeries(block.props?.series);
  if (!series.length) return <RuntimeWidgetEmpty block={block} />;
  const points = series.map((item) => item.value);
  const maxValue = Math.max(...points, 1);
  const width = 120;
  const height = 56;
  const coords = points.map((value, index) => {
    const x = points.length === 1 ? width / 2 : (index / (points.length - 1)) * width;
    const y = height - (value / maxValue) * height;
    return [x, y] as const;
  });
  const line = coords.map(([x, y]) => `${x},${y}`).join(' ');
  // Close the path back along the baseline to fill the area beneath the line.
  const firstX = coords[0][0];
  const lastX = coords[coords.length - 1][0];
  const areaPath = `M ${firstX},${height} L ${coords
    .map(([x, y]) => `${x},${y}`)
    .join(' L ')} L ${lastX},${height} Z`;

  return (
    <svg
      className="mt-auto h-16 w-full overflow-visible"
      viewBox={`0 0 ${width} ${height}`}
      data-testid={`runtime-widget-area-${block.id}`}
      data-points={points.join(',')}
      role="img"
      aria-label={getBlockLabel(block)}
    >
      <path
        d={areaPath}
        className="fill-blue-500/20 stroke-none"
        data-testid={`runtime-widget-area-fill-${block.id}`}
      />
      <polyline className="fill-none stroke-blue-500" strokeWidth="3" points={line} />
    </svg>
  );
}

function RuntimeProgressWidget({ block }: { block: DslBlockV3 }) {
  const raw = getNumberProp(block.props?.value);
  if (raw === null) return <RuntimeWidgetEmpty block={block} />;
  const percent = Math.max(0, Math.min(100, raw));
  // Optional thresholds re-colour the bar by band (e.g. green ≥80 / amber ≥50 /
  // red below). Thresholds are [{ value, color }] sorted high→low; the first one
  // the percentage meets wins. Without thresholds the bar stays accent blue.
  const band = pickThresholdColor(block.props?.thresholds, percent);

  return (
    <div className="mt-auto pt-3" data-testid={`runtime-widget-progress-${block.id}`}>
      <div className="mb-1 flex items-center justify-between text-xs text-slate-500">
        <span data-testid={`runtime-widget-progress-label-${block.id}`}>{getBlockLabel(block)}</span>
        <span
          className="font-semibold text-slate-700"
          data-testid={`runtime-widget-progress-value-${block.id}`}
        >
          {percent}%
        </span>
      </div>
      <div
        className="h-2 w-full overflow-hidden rounded-full bg-slate-200"
        role="progressbar"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div
          className={`h-full rounded-full ${band}`}
          data-testid={`runtime-widget-progress-bar-${block.id}`}
          data-percent={String(percent)}
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  );
}

function RuntimeWidgetTable({ block }: { block: DslBlockV3 }) {
  const columns = getStringArray(block.props?.columns);
  const rows = getRows(block.props?.rows);
  if (!columns.length || !rows.length) return <RuntimeWidgetEmpty block={block} />;

  return (
    <table
      className="mt-3 w-full table-fixed border-collapse text-xs"
      data-testid={`runtime-widget-table-${block.id}`}
    >
      <thead>
        <tr>
          {columns.map((column) => (
            <th
              key={column}
              className="border-b border-slate-200 px-1 py-1 text-left font-semibold"
            >
              {column}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.slice(0, 3).map((row, rowIndex) => (
          <tr key={rowIndex}>
            {columns.map((column, columnIndex) => (
              <td
                key={`${rowIndex}-${column}`}
                className="truncate border-b border-slate-100 px-1 py-1"
              >
                {row[columnIndex] ?? ''}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RuntimeMarkdownWidget({ block }: { block: DslBlockV3 }) {
  const markdown = getStringProp(block.props?.markdown);
  if (!markdown) return <RuntimeWidgetEmpty block={block} />;
  return (
    <div
      className="mt-3 line-clamp-4 whitespace-pre-wrap text-xs leading-5 text-slate-600"
      data-testid={`runtime-widget-markdown-${block.id}`}
    >
      {markdown}
    </div>
  );
}

function RuntimeWidgetEmpty({ block }: { block: DslBlockV3 }) {
  const t = useRuntimeText();
  const emptyText = getStringProp(block.props?.emptyText) || t(DESIGNER_I18N.unified.runtime.noData);
  return (
    <div
      className="mt-auto rounded-md border border-dashed border-slate-300 px-3 py-2 text-xs text-slate-500"
      data-testid={`runtime-widget-empty-${block.id}`}
    >
      {emptyText}
    </div>
  );
}

function RuntimeTitle({ block }: { block: DslBlockV3 }) {
  const locale = React.useContext(RuntimeLocaleContext);
  return (
    <div className="mb-3 text-sm font-semibold text-slate-900">{getBlockLabel(block, locale)}</div>
  );
}

interface RuntimeBlockContextOptions {
  formValues?: Record<string, unknown>;
  selectedRows?: Array<Record<string, unknown>>;
  selectedRowIds?: string[];
  currentRow?: Record<string, unknown>;
  currentRowId?: string;
  permissionCode?: string;
  pickerSearch?: string;
}

function getRuntimeBlockContext(
  block: DslBlockV3,
  pageContext: RuntimePageContext,
  blockPath: string[],
  options: RuntimeBlockContextOptions = {},
): RuntimeExecutionContext {
  return {
    ...pageContext,
    blockId: block.id,
    blockType: block.blockType,
    blockPath,
    actionType: block.actionType,
    permissionCode: options.permissionCode,
    widgetType: block.widgetType,
    formValues: options.formValues,
    selectedRows: options.selectedRows,
    selectedRowIds: options.selectedRowIds,
    currentRow: options.currentRow,
    currentRowId: options.currentRowId,
    pickerSearch: options.pickerSearch,
  };
}

function getCurrentRouteQuery(): Record<string, string | string[]> {
  if (typeof window === 'undefined') return {};

  const params = new URLSearchParams(window.location.search);
  const query: Record<string, string | string[]> = {};
  params.forEach((value, key) => {
    const existing = query[key];
    if (Array.isArray(existing)) {
      query[key] = [...existing, value];
      return;
    }
    if (typeof existing === 'string') {
      query[key] = [existing, value];
      return;
    }
    query[key] = value;
  });
  return query;
}

function normalizeSpan(block: DslBlockV3): number {
  return typeof block.layout?.span === 'number' ? Math.max(1, Math.min(12, block.layout.span)) : 12;
}

function getSpanGridStyle(block: DslBlockV3): React.CSSProperties {
  const span = normalizeSpan(block);
  return {
    gridColumn: `span ${span} / span ${span}`,
  };
}

function getWidgetGridStyle(block: DslBlockV3): React.CSSProperties {
  const width = clampGrid(getGridNumber(block.layout?.w, 3), 1, 12);
  const height = clampGrid(getGridNumber(block.layout?.h, 2), 1, 12);
  const x = clampGrid(getGridNumber(block.layout?.x, 0), 0, Math.max(0, 12 - width));
  const y = Math.max(0, getGridNumber(block.layout?.y, 0));

  return {
    gridColumn: `${x + 1} / span ${width}`,
    gridRow: `${y + 1} / span ${height}`,
  };
}

function getGridNumber(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function clampGrid(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function getBlockLabel(block: DslBlockV3, locale = 'en-US'): string {
  const title = block.title;
  if (typeof title === 'string') return title;
  if (title) {
    const resolved = title[locale] || title['en-US'] || title.en || title['zh-CN'];
    if (resolved) return resolved;
  }
  if (typeof block.props?.label === 'string') return block.props.label;
  if (typeof block.props?.title === 'string') return block.props.title;
  return block.field || block.widgetType || block.actionType || block.blockType;
}

function getRuntimePermissionCode(block: DslBlockV3): string {
  return getStringProp(block.props?.permissionCode) || getStringProp(block.props?.permission);
}

function isRuntimeBlockPermissionAllowed(
  block: DslBlockV3,
  hasPermission: (permissionCode: string) => boolean,
): boolean {
  const permissionCode = getRuntimePermissionCode(block);
  return permissionCode ? hasPermission(permissionCode) : true;
}

function RuntimePermissionNotice({
  permissionCode,
  testId,
}: {
  permissionCode: string;
  testId: string;
}) {
  return (
    <div
      className="text-xs font-medium text-amber-700"
      data-permission-allowed="false"
      data-permission-code={permissionCode}
      data-testid={testId}
    >
      Requires permission: {permissionCode}
    </div>
  );
}

function getRuntimeActionConditionValues({
  formValues,
  currentRow,
  currentRowId,
}: {
  formValues?: Record<string, unknown>;
  currentRow?: Record<string, unknown>;
  currentRowId?: string;
}): Record<string, unknown> | undefined {
  if (currentRow) {
    return {
      ...currentRow,
      current: {
        row: currentRow,
        rowId: currentRowId,
      },
    };
  }
  return formValues;
}

function shouldExecuteLiveAction(block: DslBlockV3): boolean {
  return block.props?.executionMode === 'live' || block.props?.executeLive === true;
}

function hasExecutableWidgetDataSource(block: DslBlockV3): boolean {
  const dataSource = block.dataSource;
  if (!dataSource) return false;
  const type = getStringProp(dataSource.type) || getStringProp(dataSource.mode);
  const live = dataSource.executionMode === 'live' || dataSource.executeLive === true;
  const hasQueryBuilderQuery = isRecord(dataSource.query);
  const hasNamedQueryCode = Boolean(getNamedQueryCode(dataSource));
  const isNamedQuery = type === 'namedQuery' || type === 'named-query';

  return (
    (hasQueryBuilderQuery && (live || type === 'query-builder')) ||
    ((isNamedQuery || hasNamedQueryCode) && (live || isNamedQuery))
  );
}

function hasExecutableHelperDataSource(block: DslBlockV3): boolean {
  const dataSource = block.dataSource;
  if (!dataSource) return false;
  const type = getStringProp(dataSource.type) || getStringProp(dataSource.mode);
  const live = dataSource.executionMode === 'live' || dataSource.executeLive === true;
  const hasQueryBuilderQuery = isRecord(dataSource.query);
  const hasNamedQueryCode = Boolean(getNamedQueryCode(dataSource));
  const isNamedQuery = type === 'namedQuery' || type === 'named-query';

  return (
    (hasQueryBuilderQuery && (live || type === 'query-builder')) ||
    ((isNamedQuery || hasNamedQueryCode) && (live || isNamedQuery))
  );
}

function getNamedQueryCode(dataSource: Record<string, unknown>): string {
  return (
    getStringProp(dataSource.queryCode) ||
    getStringProp(dataSource.namedQueryCode) ||
    getStringProp(dataSource.namedQuery) ||
    getStringProp(dataSource.code)
  );
}

function mergeWidgetData(block: DslBlockV3, data: RuntimeWidgetData | null): DslBlockV3 {
  if (!data) return block;

  return {
    ...block,
    props: {
      ...block.props,
      ...(typeof data.value === 'string' ? { value: data.value } : {}),
      ...(data.series ? { series: data.series } : {}),
      ...(data.columns ? { columns: data.columns } : {}),
      ...(data.rows ? { rows: data.rows } : {}),
      ...(data.emptyText ? { emptyText: data.emptyText } : {}),
    },
  };
}

function getActionStatus(block: DslBlockV3): string {
  const feedback = getStringProp(block.props?.feedback);
  if (feedback) return feedback;

  const actionType = block.actionType || 'command';
  if (actionType === 'navigate') {
    return `Navigate to ${getStringProp(block.props?.to) || '#'}`;
  }
  if (actionType === 'workflow') {
    return `Workflow started: ${getStringProp(block.props?.workflowKey) || 'workflow'}`;
  }
  if (actionType === 'modal' || actionType === 'drawer') {
    return `${actionType === 'modal' ? 'Modal' : 'Drawer'} opened`;
  }
  if (actionType === 'submit') {
    return getStringProp(block.props?.successMessage) || 'Submitted';
  }
  if (actionType === 'create') {
    return `Create via ${getStringProp(block.props?.openMode) || 'drawer'}`;
  }
  return `Command queued: ${getStringProp(block.props?.command) || 'command'}`;
}

function getStringProp(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function getBooleanProp(value: unknown): boolean {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'string') return value.toLowerCase() === 'true';
  return false;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function getAiSuggestedFields(value: unknown): Array<{ field: string; label: string; value: string }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const field =
      getStringProp(item.field) || getStringProp(item.fieldcode) || getStringProp(item.code);
    const label = getStringProp(item.label) || getStringProp(item.fieldlabel) || field;
    const rawValue =
      item.value ??
      item.suggestion ??
      item.suggestedValue ??
      item.suggestedvalue ??
      item.generatedValue ??
      item.generatedvalue;
    const displayValue = rawValue == null ? '' : String(rawValue);
    if (!field && !label && !displayValue) return [];
    return [{ field: field || label || `field_${displayValue}`, label, value: displayValue }];
  });
}

function getBpmActions(value: unknown): Array<{ label: string; actionType: string }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const label = getStringProp(item.label) || getStringProp(item.name);
    const actionType =
      getStringProp(item.actionType) || getStringProp(item.actiontype) || getStringProp(item.type);
    if (!label) return [];
    return [{ label, actionType }];
  });
}

function getTimelineItems(
  value: unknown,
): Array<{ actor: string; action: string; time: string; description: string }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const action = getStringProp(item.action) || getStringProp(item.title);
    if (!action) return [];
    return [
      {
        actor: getStringProp(item.actor) || getStringProp(item.user),
        action,
        time:
          getStringProp(item.time) ||
          getStringProp(item.createdAt) ||
          getStringProp(item.createdat),
        description: getStringProp(item.description) || getStringProp(item.detail),
      },
    ];
  });
}

function getFieldHistoryEntries(
  value: unknown,
): Array<{ field: string; from: string; to: string; changedBy: string }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item)) return [];
    const field =
      getStringProp(item.field) || getStringProp(item.fieldcode) || getStringProp(item.label);
    if (!field) return [];
    return [
      {
        field,
        from: formatRuntimeCell(item.from ?? item.oldValue ?? item.oldvalue),
        to: formatRuntimeCell(item.to ?? item.newValue ?? item.newvalue),
        changedBy:
          getStringProp(item.changedBy) || getStringProp(item.changedby) || getStringProp(item.actor),
      },
    ];
  });
}

function getSeries(value: unknown): Array<{ label: string; value: number }> {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!item || typeof item !== 'object') return [];
    const record = item as Record<string, unknown>;
    const label = getStringProp(record.label);
    const rawValue = record.value;
    const numericValue =
      typeof rawValue === 'number' && Number.isFinite(rawValue) ? rawValue : Number(rawValue);
    if (!label || !Number.isFinite(numericValue)) return [];
    return [{ label, value: numericValue }];
  });
}

function getNumberProp(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

// Maps a threshold band color name to an on-token bar fill class. Unknown / absent
// colors fall through to the accent blue used by the other mini-renderers.
const THRESHOLD_BAR_COLORS: Record<string, string> = {
  green: 'bg-emerald-500',
  emerald: 'bg-emerald-500',
  amber: 'bg-amber-500',
  yellow: 'bg-amber-500',
  red: 'bg-rose-500',
  rose: 'bg-rose-500',
  blue: 'bg-blue-500',
};

/**
 * Pick the progress bar fill from threshold bands. `thresholds` is the same
 * [{ value, color }] shape the number-card widget already authors; bands are
 * evaluated high→low and the first one the percentage meets (>=) wins. Returns
 * the accent blue when no band matches or thresholds are absent/invalid.
 */
function pickThresholdColor(thresholds: unknown, percent: number): string {
  const fallback = 'bg-blue-500';
  if (!Array.isArray(thresholds)) return fallback;
  const bands = thresholds
    .flatMap((item) => {
      if (!item || typeof item !== 'object') return [];
      const record = item as Record<string, unknown>;
      const at = getNumberProp(record.value);
      const color = getStringProp(record.color).toLowerCase();
      if (at === null || !color) return [];
      return [{ at, color }];
    })
    .sort((a, b) => b.at - a.at);
  for (const band of bands) {
    if (percent >= band.at) {
      return THRESHOLD_BAR_COLORS[band.color] ?? fallback;
    }
  }
  return fallback;
}

function getStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => (typeof item === 'string' ? [item] : []));
}

function getRows(value: unknown): string[][] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((row) => {
    if (!Array.isArray(row)) return [];
    return [row.map((cell) => String(cell))];
  });
}
