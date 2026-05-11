/**
 * DetailPageContent - Page content component for detail/view pages
 *
 * Extracted from dynamic.$tableName.view.tsx route.
 * Receives schema + tableName + recordId + token from DynamicPageRenderer,
 * loads record data client-side, and renders the full detail view.
 *
 * Supports:
 * - tabs blockType with DetailTabConfig (nested blocks per tab)
 * - sub-table blockType with SubTableViewer (read-only child records)
 * - monthly-grid blockType with MonthlyGridViewer (12-month pivot view)
 * - form-section blockType (read-only field display)
 * - toolbar blockType in header area (commandCode + navigateTo + visibleWhen)
 * - resolveVia indirect queries for sub-tables
 * - summary aggregation rows
 */

import { useState, useCallback, useMemo, useEffect } from 'react';
import { Link, useLocation, useNavigate as useRouterNavigate } from 'react-router';
import { PrintButton } from '~/framework/meta/rendering/components/PrintButton';
import { RecordShareDialog } from '~/ui/shared/RecordShareDialog';
import type { PageContentProps } from '~/framework/meta/profiles/types';
import { usePageRuntime } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';
import {
  getLocalizedText,
  DynamicField,
  buildApiEndpoint,
} from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useDictCache } from '~/framework/meta/rendering/pages/hooks/useDictCache';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useToastContext } from '~/contexts/ToastContext';
import { ErrorAlert } from '~/ui/ErrorAlert';
import { ReportGenerateButton } from '~/framework/smart/components/report/ReportGenerateButton';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import { InlineApprovalPanel } from '~/framework/smart/components/approval/InlineApprovalPanel';
import { SubTableViewer } from '~/framework/meta/rendering/blocks/SubTableViewer';
import { MonthlyGridViewer } from '~/framework/meta/rendering/blocks/MonthlyGridViewer';
import { FieldHistoryViewer } from '~/framework/meta/rendering/blocks/FieldHistoryViewer';
import { ActivityTimeline } from '~/framework/meta/rendering/blocks/ActivityTimeline';
import { RecordComments } from '~/framework/meta/rendering/blocks/RecordComments';
import { NbaSuggestionBar } from '~/framework/meta/rendering/blocks/NbaSuggestionBar';
import { BpmPanelBlock } from '~/framework/meta/rendering/blocks/BpmPanelBlock';
import { BlockRenderer } from '~/framework/meta/rendering/BlockRenderer';
import FormDialog from '~/framework/meta/runtime/actions/FormDialog';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type {
  BlockConfig,
  ButtonConfig,
  DataSourceConfig,
  DetailTabConfig,
  FieldConfig,
} from '~/framework/meta/schemas/types';
import { deriveTestId, buttonTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { evaluateVisibleWhen as evaluateVisibleWhenExpression } from './utils/visibleWhen';

interface RecordData {
  [key: string]: any;
  id?: string;
  pid?: string;
}

export function resolveDetailFieldComponent(meta?: {
  dataType?: string;
  extension?: Record<string, any>;
}): string | undefined {
  if (!meta) return undefined;

  const renderComp =
    meta.extension?.renderComponent ||
    meta.extension?.component ||
    meta.extension?.uiComponent;

  if (renderComp) {
    return String(renderComp).trim().toLowerCase();
  }

  switch (String(meta.dataType || '').toLowerCase()) {
    case 'boolean':
      return 'switch';
    case 'date':
      return 'date';
    case 'datetime':
      return 'datetime';
    case 'file':
      return 'fileattachment';
    default:
      return undefined;
  }
}

export function buildDetailRecordEndpoint(tableName: string, recordId: string): string {
  return buildApiEndpoint(tableName, recordId);
}

export function resolveSubTableDataSourceConfig(
  dataSource: BlockConfig['dataSource'] | undefined,
  schemaDataSources?: Record<string, DataSourceConfig>,
  dataSourceManager?: { getConfig: (id: string) => DataSourceConfig | undefined } | null,
): BlockConfig['dataSource'] | undefined {
  if (typeof dataSource !== 'string') {
    return dataSource;
  }

  return schemaDataSources?.[dataSource] ?? dataSourceManager?.getConfig(dataSource) ?? dataSource;
}

/**
 * DetailPageContent - renders a detail/view page from DSL schema.
 *
 * Unlike the route version that loads record data server-side via loader,
 * this component loads record data client-side using fetchResult in a useEffect.
 */
export function DetailPageContent(props: PageContentProps) {
  const { schema, tableName, recordId, token } = props;
  const location = useLocation();
  const routerNavigate = useRouterNavigate();

  // Client-side record + model field loading (parallelized)
  const [recordData, setRecordData] = useState<RecordData>({});
  const [recordLoading, setRecordLoading] = useState(true);
  const [modelFieldMap, setModelFieldMap] = useState<Map<string, any>>(new Map());

  useEffect(() => {
    if (!recordId || !tableName) {
      setRecordLoading(false);
      return;
    }
    let cancelled = false;

    async function loadModelFields(): Promise<void> {
      // Use /api/dynamic/{pageKey}/field-meta which requires model-level read permission
      // instead of /api/meta/models/{pid}/fields which requires management permission
      const pageKey = schema?.modelCode || tableName;
      if (!pageKey) return;
      const fieldsRes = await fetchResult<any[]>(`/api/dynamic/${pageKey}/field-meta`, {
        method: 'get',
        token: token || undefined,
      });
      if (cancelled) return;
      if (!ResultHelper.isSuccess(fieldsRes) || !fieldsRes.data) return;
      const map = new Map<string, any>();
      for (const f of fieldsRes.data) {
        map.set(f.code, f);
      }
      setModelFieldMap(map);
    }

    async function loadRecord(): Promise<void> {
      const endpoint = buildDetailRecordEndpoint(tableName, recordId!);
      const result = await fetchResult<RecordData>(endpoint, {
        method: 'get',
        token: token || undefined,
      });
      if (cancelled) return;
      if (ResultHelper.isSuccess(result) && result.data) {
        setRecordData(result.data);
      }
    }

    (async () => {
      try {
        // Fetch model fields and record data in parallel
        await Promise.all([
          loadModelFields().catch(() => {}),
          loadRecord().catch(() => {}),
        ]);
      } finally {
        if (!cancelled) setRecordLoading(false);
      }
    })();

    return () => { cancelled = true; };
  }, [recordId, tableName, schema?.modelCode, token]);

  // Stable callback to reload the parent record (used after sub-table command execution)
  const reloadRecord = useCallback(() => {
    if (!recordId || !tableName) return;
    const endpoint = buildDetailRecordEndpoint(tableName, recordId);
    fetchResult<RecordData>(endpoint, { method: 'get', token: token || undefined })
      .then((result) => {
        if (ResultHelper.isSuccess(result) && result.data) setRecordData(result.data);
      })
      .catch(() => {});
  }, [recordId, tableName, token]);

  // Enrich a page-schema field with model field metadata (dictCode, component, dataType)
  const enrichField = useCallback(
    (field: FieldConfig): FieldConfig => {
      const meta = modelFieldMap.get(field.field);
      if (!meta) return field;

      const enriched = { ...field } as any;
      const extensionProps = meta.extension && typeof meta.extension === 'object'
        ? meta.extension
        : {};

      enriched.props = {
        ...(field.props || {}),
        ...extensionProps,
      };

      // Add dictCode from model field
      if (meta.dictCode && !enriched.dictCode) {
        enriched.dictCode = meta.dictCode;
      }

      // Derive component from renderComponent or dataType
      if (!enriched.component) {
        const resolvedComponent = resolveDetailFieldComponent(meta);
        if (resolvedComponent) {
          enriched.component = resolvedComponent;
        }
      }

      if (!enriched.referenceModelCode) {
        enriched.referenceModelCode =
          meta.referenceModelCode ||
          meta.extension?.referenceModelCode ||
          meta.extension?.refModelCode;
      }

      return enriched as FieldConfig;
    },
    [modelFieldMap],
  );

  // Collect all dictCodes from schema fields (including inside tabs → blocks → form-section → fields)
  // Also check modelFieldMap for dictCodes not declared in the page schema
  const allDictCodes = useMemo(() => {
    if (!schema?.blocks) return [];
    const codes: string[] = [];
    const collectFromFields = (fields: FieldConfig[]) => {
      for (const f of fields) {
        if (f.dictCode) codes.push(f.dictCode);
        const meta = modelFieldMap.get(f.field);
        if (meta?.dictCode) codes.push(meta.dictCode);
      }
    };
    for (const block of schema.blocks) {
      if (block.blockType === 'tabs' && block.tabs) {
        for (const tab of block.tabs as any[]) {
          if (tab.blocks) {
            for (const b of tab.blocks) {
              if (b.blockType === 'form-section' && b.fields) {
                collectFromFields(b.fields);
              }
            }
          }
        }
      }
      if (block.blockType === 'form-section' && block.fields) {
        collectFromFields(block.fields);
      }
    }
    return [...new Set(codes)];
  }, [schema, modelFieldMap]);

  const { getDictItems } = useDictCache({ dictCodes: allDictCodes, token: token || undefined });

  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const showToast = useCallback(
    (message: string, type: 'success' | 'error' | 'warning' | 'info' = 'info') => {
      switch (type) {
        case 'success':
          showSuccessToast(message);
          break;
        case 'error':
          showErrorToast(message);
          break;
        case 'warning':
          showWarningToast(message);
          break;
        case 'info':
          showInfoToast(message);
          break;
      }
    },
    [showSuccessToast, showErrorToast, showWarningToast, showInfoToast],
  );

  const [shareDialogOpen, setShareDialogOpen] = useState(false);

  // Use usePageRuntime instead of useDynamicPageSetup
  const { runtime, dataSourceManager, t, locale, navigate } = usePageRuntime(schema, {
    token: token || undefined,
    showToast,
    additionalContext: {
      record: recordData,
      $page: {
        kind: (schema as any)?.kind,
        modelCode: (schema as any)?.modelCode,
        pageKey: (schema as any)?.pageKey,
        recordId: recordId || undefined,
      },
    },
  });

  // Use unified action handler for toolbar buttons (commandCode, navigateTo)
  const {
    handleAction,
    loading: actionLoading,
    error: actionError,
    setError,
  } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {
      record: recordData,
      loadData: reloadRecord,
    },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
  });

  // Evaluate visibleWhen expression against the record
  const evaluateVisibleWhen = useCallback(
    (visibleWhen: string | undefined): boolean => {
      return evaluateVisibleWhenExpression(visibleWhen, {
        record: recordData || {},
        row: recordData || {},
        form: recordData || {},
      });
    },
    [recordData],
  );

  // Resolve button label using i18n conventions
  const resolveButtonLabel = useCallback(
    (button: ButtonConfig): string => {
      if (button.content) return getLocalizedText(button.content, locale, t);
      if (button.label) return getLocalizedText(button.label, locale, t);
      // Try plugin-namespaced key from commandCode (e.g., "crm:contact_lead" → "crm.action.contact_lead")
      if (button.commandCode && button.commandCode.includes(':')) {
        const [ns, actionCode] = button.commandCode.split(':');
        const nsKey = `${ns}.action.${actionCode}`;
        const nsResolved = t(nsKey);
        if (nsResolved !== nsKey) return nsResolved;
      }
      if (button.label) {
        const labelStr = typeof button.label === 'string' ? button.label : undefined;
        if (labelStr) {
          const labelResolved = t(labelStr);
          if (labelResolved && labelResolved !== labelStr) return labelResolved;
          return labelStr;
        }
      }
      if (button.action && typeof button.action === 'string') {
        // Map action values to i18n keys (e.g., "edit" → "update" in i18n)
        const actionKeyMap: Record<string, string> = { edit: 'update', navigate: 'view' };
        const i18nAction = actionKeyMap[button.action] || button.action;
        const i18nKey = `action.${i18nAction}`;
        const resolved = t(i18nKey);
        if (resolved !== i18nKey) return resolved;
      }
      // Try button code as i18n key
      const codeKey = `action.${button.code}`;
      const codeResolved = t(codeKey);
      if (codeResolved !== codeKey) return codeResolved;
      return button.code;
    },
    [locale, t],
  );

  // Extract blocks from schema
  const allBlocks = useMemo(() => {
    return schema?.blocks || [];
  }, [schema]);

  const headerToolbar = useMemo(
    () => allBlocks.find((b: BlockConfig) => b.blockType === 'toolbar'),
    [allBlocks],
  );
  const effectiveHeaderToolbar = headerToolbar || null;

  const tabsBlock = useMemo(
    () => allBlocks.find((b: BlockConfig) => b.blockType === 'tabs'),
    [allBlocks],
  );

  // For simple detail pages without tabs, find form-section blocks directly
  const directFormBlocks = useMemo(
    () => allBlocks.filter((b: BlockConfig) => b.blockType === 'form-section'),
    [allBlocks],
  );
  const effectiveDirectFormBlocks = directFormBlocks;

  const directSubTableBlocks = useMemo(
    () => allBlocks.filter((b: BlockConfig) => b.blockType === 'sub-table'),
    [allBlocks],
  );

  const directMonthlyGridBlocks = useMemo(
    () => allBlocks.filter((b: BlockConfig) => b.blockType === 'monthly-grid'),
    [allBlocks],
  );

  // G7 dispatch — blocks not handled by the hardcoded switch above still need
  // to render. Collect anything that is NOT a known detail-page block type and
  // delegate to BlockRenderer (unified block dispatcher). This lets chart,
  // description, rich-text, divider, stat-card etc. render on detail pages
  // without every detail page having to opt in explicitly.
  const DETAIL_SPECIALIZED_BLOCK_TYPES = new Set<string>([
    'toolbar',
    'tabs',
    'form-section',
    'detail-section',
    'sub-table',
    'monthly-grid',
    'activity-timeline',
    'record-comments',
    'field-history',
    'bpm-panel',
  ]);
  const directMiscBlocks = useMemo(
    () =>
      allBlocks.filter(
        (b: BlockConfig) => !DETAIL_SPECIALIZED_BLOCK_TYPES.has(b.blockType as string),
      ),
    [allBlocks],
  );

  // System tabs are injected by backend into dsl_schema. Filter out system tabs when no recordId (new record).
  const allTabs = (tabsBlock?.tabs || []) as DetailTabConfig[];
  const tabs = recordId ? allTabs : allTabs.filter((t) => !t.system);
  const [activeTab, setActiveTab] = useState(0);

  const tabHashKeys = useMemo(
    () =>
      tabs.map((tab, index) => {
        const rawKey = typeof tab.key === 'string' && tab.key.trim().length > 0
          ? tab.key.trim()
          : `tab-${index + 1}`;
        return decodeURIComponent(rawKey);
      }),
    [tabs],
  );

  const resolveTabIndexFromHash = useCallback(
    (hash: string): number => {
      if (!hash) return -1;
      const decodedHash = decodeURIComponent(hash.replace(/^#/, '').trim());
      if (!decodedHash) return -1;
      return tabHashKeys.findIndex((key) => key === decodedHash);
    },
    [tabHashKeys],
  );

  useEffect(() => {
    if (tabs.length === 0) return;
    const matchedIndex = resolveTabIndexFromHash(location.hash);
    if (matchedIndex >= 0) {
      setActiveTab(matchedIndex);
      return;
    }
    setActiveTab((current) => (current < tabs.length ? current : 0));
  }, [location.hash, resolveTabIndexFromHash, tabs.length]);

  const handleTabChange = useCallback(
    (index: number) => {
      setActiveTab(index);
      const nextTabKey = tabHashKeys[index];
      if (!nextTabKey) return;
      const nextHash = `#${encodeURIComponent(nextTabKey)}`;
      if (location.hash === nextHash) return;
      routerNavigate(
        {
          pathname: location.pathname,
          search: location.search,
          hash: nextHash,
        },
        { preventScrollReset: true },
      );
    },
    [location.hash, location.pathname, location.search, routerNavigate, tabHashKeys],
  );

  // Show loading while record is being fetched
  if (recordLoading) {
    return <LoadingSpinner />;
  }

  // Error handling
  if (actionError) {
    return (
      <ErrorAlert
        title={t('common.loadError') || 'Load failed'}
        error={actionError}
        onRetry={() => {
          setError(null);
          window.location.reload();
        }}
      />
    );
  }

  return (
    <div
      className="mx-auto w-full px-2 py-3"
      data-testid={deriveTestId('detail', schema?.modelCode || tableName, 'container')}
    >
      <div className="rounded-lg bg-white shadow-sm">
        {/* Page Header with title + toolbar buttons (hidden in print) */}
        <div className="print-hide border-b border-gray-200 px-6 py-4" data-print="hide">
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-4">
              <Link to={`/p/${tableName}`} className="text-gray-400 hover:text-gray-600">
                <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M15 19l-7-7 7-7"
                  />
                </svg>
              </Link>
              <h2 className="text-lg font-medium text-gray-900">
                {getLocalizedText(schema.title, locale, t)}
              </h2>
            </div>

            {/* Header toolbar buttons */}
            <div className="print-hide flex items-center space-x-2" data-print="hide">
              {schema.extension?.showShare !== false && (
                <button
                  onClick={() => setShareDialogOpen(true)}
                  className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50"
                  data-testid={deriveTestId('detail', schema?.modelCode || tableName, 'share-btn')}
                >
                  {t('action.share') || 'Share'}
                </button>
              )}
              {schema.extension?.showReport !== false && recordData?.pid && (
                <ReportGenerateButton
                  modelCode={schema?.modelCode || tableName}
                  recordPid={recordData.pid}
                />
              )}
              <PrintButton title={getLocalizedText(schema.title, locale, t)} />
              {effectiveHeaderToolbar?.buttons && effectiveHeaderToolbar.buttons.length > 0 && (
                <>
                  {effectiveHeaderToolbar.buttons
                    .filter((button: ButtonConfig) => evaluateVisibleWhen(button.visibleWhen))
                    .map((button: ButtonConfig) => (
                      <button
                        key={button.code}
                        data-testid={`toolbar-btn-${button.code}`}
                        data-ab-testid={buttonTestId('detail', schema?.modelCode || tableName, button.code)}
                        onClick={() => handleAction(button, recordData)}
                        disabled={actionLoading}
                        className={`rounded-md px-3 py-1.5 text-sm font-medium ${
                          button.primary
                            ? 'bg-blue-600 text-white hover:bg-blue-700'
                            : button.danger
                              ? 'bg-red-600 text-white hover:bg-red-700'
                              : 'border border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
                        } disabled:cursor-not-allowed disabled:opacity-50`}
                      >
                        {resolveButtonLabel(button)}
                      </button>
                    ))}
                </>
              )}
            </div>
          </div>
        </div>

        {/* Print-only header — visible only during print */}
        <div className="print-header">{getLocalizedText(schema.title, locale, t)}</div>
        <div className="print-meta">
          {new Date().toLocaleDateString(locale, {
            year: 'numeric',
            month: 'long',
            day: 'numeric',
          })}
        </div>

        {/* AI Next Best Action suggestions (hidden in print) */}
        <div className="print-hide">
          {recordData?.pid && (
            <NbaSuggestionBar
              modelCode={schema?.modelCode || tableName}
              recordPid={recordData.pid}
              token={token || undefined}
            />
          )}
        </div>

        {/* Content: Tabs mode or Direct mode */}
        {tabs.length > 0 ? (
          <div>
            {/* Tab headers (hidden in print — only active tab content shows) */}
            <div className="print-hide border-b border-gray-200 px-6" data-print="hide">
              <nav
                className="-mb-px flex space-x-8"
                role="tablist"
                aria-label="Tabs"
              >
                {tabs.map((tab, index) => (
                  <button
                    key={tab.key || index}
                    role="tab"
                    aria-selected={activeTab === index}
                    data-testid={deriveTestId(
                      'detail',
                      schema?.modelCode || tableName,
                      'tab',
                      tab.key || String(index),
                    )}
                    onClick={() => handleTabChange(index)}
                    className={`border-b-2 px-1 py-3 text-sm font-medium ${
                      activeTab === index
                        ? 'border-blue-500 text-blue-600'
                        : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
                    }`}
                  >
                    {getLocalizedText(tab.label, locale, t)}
                  </button>
                ))}
              </nav>
            </div>

            {/* Tab content — render all tabs but hide inactive ones to prefetch sub-table data in parallel */}
            {tabs.map((tab, tabIndex) => (
              <div
                key={tab.key || tabIndex}
                className="p-6"
                style={{ display: activeTab === tabIndex ? undefined : 'none' }}
              >
                {tab.blocks?.map((block: BlockConfig, blockIndex: number) => {
                  // BPM panel includes a ReactFlow canvas; mounting it inside a
                  // display:none tab produces an incorrect first fitView scale.
                  // Keep other tab blocks pre-rendered, but defer bpm-panel
                  // until its tab is actually active.
                  if (activeTab !== tabIndex && block.blockType === 'bpm-panel') {
                    return null;
                  }

                  return (
                    <DetailBlockRenderer
                      key={block.id || `tab-${tabIndex}-block-${blockIndex}`}
                      block={block}
                      recordData={recordData}
                      recordId={recordId!}
                      token={token || undefined}
                      locale={locale}
                      t={t}
                      modelCode={schema?.modelCode || tableName}
                      evaluateEditableWhen={evaluateVisibleWhen}
                      onDataChange={reloadRecord}
                      getDictItems={getDictItems}
                      enrichField={enrichField}
                      runtime={runtime as SchemaRuntime}
                      schemaDataSources={schema?.dataSources}
                      dataSourceManager={dataSourceManager}
                    />
                  );
                })}
              </div>
            ))}
            {/* Inline approval panel — shown when the record has an associated BPM process */}
            {recordData?.pid && <InlineApprovalPanel recordPid={recordData.pid} />}
          </div>
        ) : (
          /* Direct mode: render form-sections and sub-tables without tabs */
          <div className="space-y-6 p-6">
            {effectiveDirectFormBlocks.map((block: BlockConfig, blockIndex: number) => (
              <DetailBlockRenderer
                key={block.id || `block-${blockIndex}`}
                block={block}
                recordData={recordData}
                recordId={recordId!}
                token={token || undefined}
                locale={locale}
                t={t}
                modelCode={schema?.modelCode || tableName}
                evaluateEditableWhen={evaluateVisibleWhen}
                onDataChange={reloadRecord}
                getDictItems={getDictItems}
                enrichField={enrichField}
                runtime={runtime as SchemaRuntime}
                schemaDataSources={schema?.dataSources}
                dataSourceManager={dataSourceManager}
              />
            ))}
            {directSubTableBlocks.map((block: BlockConfig, blockIndex: number) => (
              <DetailBlockRenderer
                key={block.id || `sub-${blockIndex}`}
                block={block}
                recordData={recordData}
                recordId={recordId!}
                token={token || undefined}
                locale={locale}
                t={t}
                modelCode={schema?.modelCode || tableName}
                evaluateEditableWhen={evaluateVisibleWhen}
                onDataChange={reloadRecord}
                getDictItems={getDictItems}
                enrichField={enrichField}
                runtime={runtime as SchemaRuntime}
                schemaDataSources={schema?.dataSources}
                dataSourceManager={dataSourceManager}
              />
            ))}
            {directMonthlyGridBlocks.map((block: BlockConfig, blockIndex: number) => (
              <DetailBlockRenderer
                key={block.id || `monthly-${blockIndex}`}
                block={block}
                recordData={recordData}
                recordId={recordId!}
                token={token || undefined}
                locale={locale}
                t={t}
                modelCode={schema?.modelCode || tableName}
                evaluateEditableWhen={evaluateVisibleWhen}
                onDataChange={reloadRecord}
                getDictItems={getDictItems}
                enrichField={enrichField}
                runtime={runtime as SchemaRuntime}
                schemaDataSources={schema?.dataSources}
                dataSourceManager={dataSourceManager}
              />
            ))}

            {/* G7 — unified fallback dispatch for blocks not handled by the
                hardcoded detail switch (chart / description / rich-text /
                divider / stat-card / etc.). Renders via BlockRenderer using
                the fallback renderer registry. Unknown blockTypes surface as
                a visible placeholder + console.warn (not silently dropped). */}
            {runtime &&
              directMiscBlocks.map((block: BlockConfig, blockIndex: number) => (
                <BlockRenderer
                  key={block.id || `misc-${blockIndex}`}
                  block={block}
                  runtime={runtime}
                  areaId="detail-direct"
                />
              ))}

            {/* Fallback: no structured blocks found, show all fields */}
            {effectiveDirectFormBlocks.length === 0 &&
              directSubTableBlocks.length === 0 &&
              directMonthlyGridBlocks.length === 0 &&
              directMiscBlocks.length === 0 &&
              tabs.length === 0 && (
                <FallbackDetailView schema={schema} recordData={recordData} locale={locale} />
              )}

            {/* Inline approval panel — shown when the record has an associated BPM process */}
            {recordData?.pid && <InlineApprovalPanel recordPid={recordData.pid} />}

            {/* Default back/edit buttons (hidden in print) */}
            {!effectiveHeaderToolbar && (
              <div
                className="print-hide flex justify-end space-x-3 border-t border-gray-200 pt-6"
                data-print="hide"
              >
                <Link
                  to={`/p/${tableName}`}
                  className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  {t('action.back')}
                </Link>
                <Link
                  to={`/p/${tableName}/edit/${recordId}`}
                  className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
                >
                  {t('action.update')}
                </Link>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Record Share Dialog */}
      {shareDialogOpen && recordId && (
        <RecordShareDialog
          open={shareDialogOpen}
          onClose={() => setShareDialogOpen(false)}
          resourceCode={schema?.modelCode || tableName}
          recordId={recordId}
        />
      )}

      <FormDialog />
    </div>
  );
}

/**
 * DetailBlockRenderer - renders a single block within the detail page
 */
function DetailBlockRenderer({
  block,
  recordData,
  recordId,
  token,
  locale,
  t,
  modelCode,
  evaluateEditableWhen,
  onDataChange,
  getDictItems,
  enrichField,
  runtime,
  schemaDataSources,
  dataSourceManager,
}: {
  block: BlockConfig;
  recordData: RecordData;
  recordId: string;
  token?: string;
  locale: string;
  t: (key: string) => string;
  modelCode?: string;
  evaluateEditableWhen?: (expr: string | undefined) => boolean;
  onDataChange?: () => void;
  getDictItems?: (
    code: string,
  ) => Array<{ value: string; label: string; extension?: Record<string, any> }>;
  enrichField?: (field: FieldConfig) => FieldConfig;
  runtime?: SchemaRuntime;
  schemaDataSources?: Record<string, DataSourceConfig>;
  dataSourceManager?: { getConfig: (id: string) => DataSourceConfig | undefined };
}) {
  const resolveModelFieldLabel = useCallback(
    (fieldCode: string): string => {
      if (modelCode) {
        const modelKey = `model.${modelCode}.${fieldCode}.label`;
        const modelLabel = t(modelKey);
        if (modelLabel && modelLabel !== modelKey) return modelLabel;
      }
      const fieldKey = `field.${fieldCode}.label`;
      const fieldLabel = t(fieldKey);
      if (fieldLabel && fieldLabel !== fieldKey) return fieldLabel;
      return fieldCode;
    },
    [modelCode, t],
  );

  if (block.blockType === 'form-section') {
    return (
      <div className="form-section">
        {block.title && (
          <h3 className="mb-5 text-sm font-semibold tracking-wider text-gray-500 uppercase">
            {getLocalizedText(block.title, locale, t)}
          </h3>
        )}
        {block.fields && block.fields.length > 0 && (
          <div className="grid grid-cols-1 gap-x-8 gap-y-5 md:grid-cols-2">
            {block.fields.map((field: FieldConfig) => {
              const colSpan = field.layout?.colSpan || (field.span === 2 ? 12 : 6);
              const isFullWidth = colSpan >= 12 || field.span === 2;
              const resolvedField: FieldConfig = field.label
                ? field
                : { ...field, label: resolveModelFieldLabel(field.field) };
              const enrichedField = enrichField ? enrichField(resolvedField) : resolvedField;

              return (
                <div
                  key={field.field}
                  data-testid={`form-field-${field.field}`}
                  className={`${isFullWidth ? 'md:col-span-2' : ''} border-b border-gray-100 pb-4`}
                >
                  <DynamicField
                    field={enrichedField}
                    value={recordData ? recordData[field.field] : undefined}
                    onChange={() => {}}
                    readOnly={true}
                    locale={locale}
                    getDictItems={getDictItems}
                  />
                </div>
              );
            })}
          </div>
        )}
      </div>
    );
  }

  if (block.blockType === 'sub-table') {
    // Support both nested block.subTable and flat block-level properties
    const blockDataSource = resolveSubTableDataSourceConfig(
      (block as any).dataSource,
      schemaDataSources,
      dataSourceManager,
    );
    const subTableConfig =
      block.subTable ||
      ((block as any).modelCode || (block as any).foreignKey || (block as any).dataSource
        ? {
            childModel: (block as any).modelCode,
            parentField: (block as any).foreignKey,
            columns: (block as any).columns || [],
            actions: (block as any).actions,
            readOnly: true,
            dataSource: blockDataSource,
          }
        : null);
    if (subTableConfig) {
      // Evaluate editableWhen condition against parent record
      const isEditable =
        !subTableConfig.readOnly &&
        (subTableConfig.editableWhen && evaluateEditableWhen
          ? evaluateEditableWhen(subTableConfig.editableWhen)
          : !subTableConfig.readOnly);

      return (
        <div className="sub-table-section">
          {block.title && (
            <h3 className="mb-5 text-sm font-semibold tracking-wider text-gray-500 uppercase">
              {getLocalizedText(block.title, locale, t)}
            </h3>
          )}
          <SubTableViewer
            config={subTableConfig}
            parentRecordId={recordId}
            parentRecordData={recordData}
            token={token}
            locale={locale}
            t={t}
            isEditable={isEditable}
            onDataChange={onDataChange}
          />
        </div>
      );
    }
  }

  if (block.blockType === 'activity-timeline') {
    // Activity API uses record PID, not numeric ID
    const pid = recordData?.pid || recordId;
    return (
      <ActivityTimeline
        modelCode={modelCode || ''}
        recordPid={String(pid)}
        token={token}
        locale={locale}
        t={t}
      />
    );
  }

  if (block.blockType === 'record-comments') {
    const pid = recordData?.pid || recordId;
    return (
      <RecordComments
        modelCode={modelCode || ''}
        recordPid={String(pid)}
        token={token}
        locale={locale}
        t={t}
      />
    );
  }

  if (block.blockType === 'field-history') {
    // Audit API expects numeric record ID, not PID
    const numericId = recordData?.id || recordId;
    return (
      <FieldHistoryViewer
        modelCode={modelCode || ''}
        recordId={String(numericId)}
        token={token}
        locale={locale}
        t={t}
      />
    );
  }

  if (block.blockType === 'bpm-panel') {
    return <BpmPanelBlock block={block as any} record={recordData} recordId={recordId} />;
  }

  if (block.blockType === 'monthly-grid' && block.monthlyGrid) {
    return (
      <div className="monthly-grid-section">
        {block.title && (
          <h3 className="mb-5 text-sm font-semibold tracking-wider text-gray-500 uppercase">
            {getLocalizedText(block.title, locale, t)}
          </h3>
        )}
        <MonthlyGridViewer
          config={block.monthlyGrid}
          parentRecordId={recordId}
          token={token}
          locale={locale}
          t={t}
        />
      </div>
    );
  }

  // G7 — unified fallback dispatch. Unknown block types fall through to the
  // BlockRenderer registry (chart / description / rich-text / divider /
  // stat-card / form / filters / etc.). Renders an "Unknown block type"
  // placeholder if no renderer is registered — NEVER silently null.
  if (runtime) {
    return (
      <BlockRenderer block={block} runtime={runtime} areaId="detail-tab" />
    );
  }

  // No runtime available — log and surface the miss so it's diagnosable.
  // eslint-disable-next-line no-console
  console.warn(
    `[DetailBlockRenderer] No runtime context; cannot dispatch blockType="${block.blockType}"`,
  );
  return (
    <div
      className="rounded border border-yellow-300 bg-yellow-50 p-4"
      data-testid="detail-block-unknown"
    >
      <p className="text-yellow-800">
        Unknown block type on detail page: <code>{String(block.blockType)}</code>
      </p>
    </div>
  );
}

/**
 * FallbackDetailView - fallback for schemas without structured blocks
 */
function FallbackDetailView({
  schema,
  recordData,
  locale,
}: {
  schema: any;
  recordData: RecordData;
  locale: string;
}) {
  // Try to extract fields from any block
  const fields: FieldConfig[] = [];
  if (schema?.blocks) {
    for (const block of schema.blocks) {
      if (block.fields) {
        fields.push(...block.fields);
      }
    }
  }

  if (fields.length === 0) return null;

  return (
    <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
      {fields.map((field: FieldConfig) => (
        <div key={field.field} data-testid={`form-field-${field.field}`} className={field.span === 2 ? 'md:col-span-2' : ''}>
          <DynamicField
            field={field}
            value={recordData ? recordData[field.field] : undefined}
            onChange={() => {}}
            readOnly={true}
            locale={locale}
          />
        </div>
      ))}
    </div>
  );
}
