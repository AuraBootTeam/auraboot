import React from 'react';
import { usePermissions } from '~/contexts/AuthContext';
import type { DslBlockV3, PageSchemaV3 } from '../types';
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
}

export function RecursiveBlockRenderer({
  schema,
  runtimeServices,
  permissionEvaluator,
}: RecursiveBlockRendererProps) {
  const { hasPermission } = usePermissions();
  const evaluatePermission = permissionEvaluator ?? hasPermission;
  const pageContext: RuntimePageContext = {
    source: 'unified-designer-runtime-preview',
    pageId: schema.id,
    pageKind: schema.kind,
    schemaVersion: schema.schemaVersion,
    routeQuery: getCurrentRouteQuery(),
  };

  return (
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
  );
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
    default:
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

function RuntimeTabs({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
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
          No tabs configured.
        </div>
      )}
    </section>
  );
}

function RuntimeAiFillBanner({ block, runtimeServices, pageContext, blockPath }: RuntimeBlockProps) {
  const formContext = React.useContext(RuntimeFormValueContext);
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
    'Review generated suggestions before applying them to the form.';
  const feedback =
    getStringProp(runtimeData?.feedback) ||
    getStringProp(block.props?.feedback) ||
    'Suggestions applied';
  const suggestedFields = getAiSuggestedFields(
    runtimeData?.suggestedFields ?? block.props?.suggestedFields ?? block.props?.fields,
  );
  const emptyText =
    getStringProp(runtimeData?.emptyText) ||
    getStringProp(block.props?.emptyText) ||
    'No suggestions';
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
    'No workflow tasks';
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
            <dt className="text-xs font-medium uppercase text-slate-400">Assignee</dt>
            <dd className="text-slate-800">{assignee}</dd>
          </div>
        ) : null}
        {dueAt ? (
          <div data-testid={`runtime-bpm-due-${block.id}`}>
            <dt className="text-xs font-medium uppercase text-slate-400">Due</dt>
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
    'No activity yet';

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
    'No field changes';

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
                <th className="px-3 py-2">Field</th>
                <th className="px-3 py-2">From</th>
                <th className="px-3 py-2">To</th>
                <th className="px-3 py-2">By</th>
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
        Loading live data...
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
        <option value="">{placeholder || 'Select...'}</option>
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
            No options configured
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
          <span>Link</span>
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
        placeholder={placeholder || 'All records...'}
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
        <option value="">{placeholder || 'All'}</option>
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
          setRuntimeError(normalizeRuntimeExecutionError(loadError, 'Picker options failed'));
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
  const searchPlaceholder = getStringProp(block.props?.searchPlaceholder) || 'Search records...';

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
        <option value="">{loading ? 'Loading records...' : placeholder || 'Select record...'}</option>
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
          <option value="">Select...</option>
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
  const rows = applyRuntimeListFilters(getRuntimeTableRows(block), selectionContext);
  const configuredColumnBlocks = getRuntimeTableColumnBlocks(block);
  const columnBlocks = configuredColumnBlocks.filter((child) =>
    isRuntimeBlockPermissionAllowed(child, hasPermission),
  );
  const rowActionBlocks = getRuntimeTableRowActionBlocks(block);
  const columns = getRuntimeTableColumns(block, rows, columnBlocks, configuredColumnBlocks.length > 0);

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
        <div className="px-3 py-4 text-sm text-slate-400">No records</div>
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
  const id = row.id ?? row.pid ?? row.key ?? row._id ?? index;
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
        {pendingConfirm ? 'Confirm' : executing ? 'Running' : getBlockLabel(block)}
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
  const value = getStringProp(effectiveBlock.props?.value);
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

function RuntimeWidgetBody({ block }: { block: DslBlockV3 }) {
  if (block.widgetType === 'bar-chart') {
    return <RuntimeBarChart block={block} />;
  }
  if (block.widgetType === 'line-chart') {
    return <RuntimeLineChart block={block} />;
  }
  if (block.widgetType === 'table') {
    return <RuntimeWidgetTable block={block} />;
  }
  if (block.widgetType === 'markdown') {
    return <RuntimeMarkdownWidget block={block} />;
  }
  return null;
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
  const emptyText = getStringProp(block.props?.emptyText) || 'No data';
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
  return <div className="mb-3 text-sm font-semibold text-slate-900">{getBlockLabel(block)}</div>;
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

function getBlockLabel(block: DslBlockV3): string {
  if (typeof block.title === 'string') return block.title;
  if (block.title?.en) return block.title.en;
  if (block.title?.['zh-CN']) return block.title['zh-CN'];
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
