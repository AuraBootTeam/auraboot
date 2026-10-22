import React, { useEffect, useRef, useState } from 'react';
import { useAuth } from '~/contexts/AuthContext';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  resolveProfiledFieldColumns,
  resolveProfiledFieldGroups,
} from './ReviewDrawerCandidateFields';
import { ReviewDrawerCandidatesPanel } from './ReviewDrawerCandidatesPanel';
import { DrawerEditForm } from './ReviewDrawerEditForm';
import { ReviewDrawerEvidenceRail } from './ReviewDrawerEvidenceRail';
import { Badge } from './ReviewDrawerFields';
import {
  DEFAULT_DRAWER_LAYOUT,
  type DrawerLayoutState,
  drawerLayoutStorageKey,
  getViewportSize,
  normalizeDrawerLayout,
  persistDrawerLayout,
  type PointerState,
  readStoredDrawerLayout,
} from './reviewDrawerLayout';
import {
  buildTemplateFieldConfigs,
  candidateKey,
  fillTemplate,
  findRelatedRecord,
  formatConfiguredValue,
  formatValue,
  isEmptyValue,
  localized,
  readFieldValue,
  resolveRawColumnGroups,
  stateBindingFromExpression,
} from './reviewDrawerValues';
import { collectSemanticResolutionTraces } from './SemanticResolutionEvidence';
import {
  executeSimpleWorkbenchAction,
  readDataSourceRows,
  readDataSourceState,
  readPath,
  resolveRuntimeValue,
  useDataSourceSubscription,
  useRuntimeStateSubscription,
  writeRuntimeState,
} from './workbenchBlockUtils';

export { parseLadderRungs, safeExternalUrl } from './reviewDrawerValues';
export { collectSemanticResolutionTraces } from './SemanticResolutionEvidence';

export interface ReviewDrawerBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const ReviewDrawerBlockRenderer: React.FC<ReviewDrawerBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const { hasPermission } = useAuth();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const evaluator = runtime.getEvaluator();
  const contextExpression = (block as any).context;
  const contextRecord = resolveRuntimeValue(runtime, contextExpression);
  const compareConfig = (block as any).compare || {};
  const candidatesConfig = (block as any).candidates || {};
  const hasCandidatesConfig = Boolean((block as any).candidates);
  const usesCandidateComparisonTable = candidatesConfig.layout === 'comparisonTable';
  const candidateTableConfig = candidatesConfig.table || {};
  const editFormConfig = (block as any).editForm;
  const renderEditTriggerInCandidatesHeader =
    hasCandidatesConfig && editFormConfig?.openPlacement === 'candidates-header';
  const exportConfig = (block as any).exportImpact || {};
  const sourceConfig = (block as any).source || {};
  const sourceRecordConfig = sourceConfig.record || {};
  const contextDataSource = (block as any).contextDataSource;
  const contextKeyField = (block as any).contextKeyField || 'pid';
  const closeClearsContext = (block as any).closeClearsContext !== false;
  const rawRecordConfig = compareConfig.rawRecord || {};
  const canonicalRecordConfig = compareConfig.canonicalRecord || {};
  const rawDataSource = rawRecordConfig.dataSource;
  const canonicalDataSource = canonicalRecordConfig.dataSource;
  const sourceDataSource = sourceRecordConfig.dataSource;
  const candidateDataSource = candidatesConfig.dataSource;
  const exportDataSource = exportConfig.dataSource;
  const contextStateBinding = stateBindingFromExpression(contextExpression);
  const candidateContextFields = Array.isArray(candidatesConfig.contextFields)
    ? candidatesConfig.contextFields
    : [];
  const selectedCandidateFields = Array.isArray(candidatesConfig.selectedFields)
    ? candidatesConfig.selectedFields
    : [];
  const selectedCandidateGroups = Array.isArray(candidatesConfig.selectedGroups)
    ? candidatesConfig.selectedGroups
    : Array.isArray(candidatesConfig.groups)
      ? candidatesConfig.groups
      : [];
  const shouldShowDecisionStatus = candidatesConfig.showDecisionStatus !== false;
  const shouldShowSelectedCandidateDetail = candidatesConfig.showSelectedDetail !== false;
  const usesSummaryEvidence = candidatesConfig.selectedEvidenceMode === 'summary';
  const blockLayoutMode = String((block as any).layoutMode || '');
  const compareLayoutMode = String(compareConfig.layoutMode || '');
  const contextSummaryConfig = (block as any).contextSummary;
  const usesLightChrome = (block as any).chrome === 'light';
  const isCompactReview = blockLayoutMode === 'compact-review';
  const isStackedCompare = isCompactReview || compareLayoutMode === 'stacked';
  const layoutStorageKey = drawerLayoutStorageKey(runtime, block, context);
  const configuredDefaultLayout: DrawerLayoutState = {
    ...DEFAULT_DRAWER_LAYOUT,
    ...(isCompactReview ? { height: Math.round(getViewportSize().height * 0.85) } : {}),
    ...((block as any).defaultLayout || {}),
  };
  const initialLayoutRef = useRef<DrawerLayoutState | null>(null);
  if (initialLayoutRef.current === null) {
    initialLayoutRef.current = readStoredDrawerLayout(
      layoutStorageKey,
      configuredDefaultLayout,
      Boolean((block as any).defaultLayout),
    );
  }
  const initialLayout = initialLayoutRef.current;

  const [selectedCandidateKey, setSelectedCandidateKey] = useState('');
  const [dismissedRecordKey, setDismissedRecordKey] = useState('');
  const [isMaximized, setIsMaximized] = useState(false);
  const [isEditFormOpen, setIsEditFormOpen] = useState(false);
  const [position, setPosition] = useState({
    left: initialLayout.left,
    top: initialLayout.top,
  });
  const [size, setSize] = useState({
    width: initialLayout.width,
    height: initialLayout.height,
  });
  const [runningAction, setRunningAction] = useState<string | null>(null);
  const [editTriggerHost, setEditTriggerHost] = useState<HTMLDivElement | null>(null);
  const dragRef = useRef<PointerState | null>(null);
  const resizeRef = useRef<PointerState | null>(null);
  const layoutRef = useRef<DrawerLayoutState>({
    left: initialLayout.left,
    top: initialLayout.top,
    width: initialLayout.width,
    height: initialLayout.height,
  });

  useRuntimeStateSubscription(runtime);
  useDataSourceSubscription(runtime, contextDataSource);
  useDataSourceSubscription(runtime, rawDataSource);
  useDataSourceSubscription(runtime, canonicalDataSource);
  useDataSourceSubscription(runtime, sourceDataSource);
  useDataSourceSubscription(runtime, candidateDataSource);
  useDataSourceSubscription(runtime, exportDataSource);

  const contextRows = readDataSourceRows(runtime, contextDataSource);
  const contextRecordKey = readPath(contextRecord, contextKeyField);
  const record =
    contextDataSource && contextRecordKey !== undefined && contextRecordKey !== null
      ? contextRows.find(
          (row: any) => String(readPath(row, contextKeyField)) === String(contextRecordKey),
        ) || contextRecord
      : contextRecord;
  const selectedRecordKey = record ? String(record.pid ?? record.bom_std_row_no ?? '') : '';
  const candidateState = readDataSourceState(runtime, candidateDataSource);
  const hasCandidateSnapshot = candidateState?.data !== undefined && candidateState?.data !== null;
  const isInitialCandidateLoading = Boolean(candidateState?.loading && !hasCandidateSnapshot);
  const isRefreshingCandidates = Boolean(candidateState?.loading && hasCandidateSnapshot);
  // Polling reloads the selected row's evidence after every committed BOM batch. DataSourceManager
  // deliberately keeps the previous successful payload while that request is in flight. Keep
  // rendering the same snapshot here as well: replacing a full candidate list with a loading line
  // every 1.5 seconds makes the fixed review surface visibly reflow and shake.
  const candidates = candidateState?.error ? [] : readDataSourceRows(runtime, candidateDataSource);
  const exportRows = readDataSourceRows(runtime, exportDataSource);
  const selectedCandidate = candidates.find((row: any, index: number) => {
    const key = candidateTableConfig.keyField
      ? String(readPath(row, candidateTableConfig.keyField) ?? index)
      : candidateKey(row, index);
    return key === selectedCandidateKey;
  });

  useEffect(() => {
    setSelectedCandidateKey('');
    setIsEditFormOpen(false);
    if (candidatesConfig.selection?.bind) {
      writeRuntimeState(runtime, candidatesConfig.selection.bind, {});
    }
  }, [selectedRecordKey, candidatesConfig.selection?.bind, runtime]);

  useEffect(() => {
    if (!selectedRecordKey) {
      setDismissedRecordKey('');
    }
  }, [selectedRecordKey]);

  const runAction = async (actionConfig: any, source: 'candidate' | 'export') => {
    if (actionConfig.permissionCode && !hasPermission(actionConfig.permissionCode)) return;
    const code = String(actionConfig.code || actionConfig.id || actionConfig.label);
    setRunningAction(`${source}:${code}`);
    try {
      await executeSimpleWorkbenchAction(runtime, actionConfig?.onClick);
    } catch (error) {
      console.error('[ReviewDrawerBlockRenderer] action failed:', error);
    } finally {
      setRunningAction(null);
    }
  };

  // Fully close the review drawer by clearing the selected context row, so it returns to the
  // inline empty state instead of collapsing to a floating "展开行级复核" pill.
  const closeDrawer = () => {
    setSelectedCandidateKey('');
    setIsEditFormOpen(false);
    if (candidatesConfig.selection?.bind) {
      writeRuntimeState(runtime, candidatesConfig.selection.bind, {});
    }
    if (!closeClearsContext) {
      setDismissedRecordKey(selectedRecordKey);
      return;
    }
    if (contextStateBinding) {
      writeRuntimeState(runtime, contextStateBinding, {});
    }
  };

  useEffect(() => {
    layoutRef.current = {
      left: position.left,
      top: position.top,
      width: size.width,
      height: size.height,
    };
  }, [position.left, position.top, size.height, size.width]);

  useEffect(() => {
    const handleMouseMove = (event: MouseEvent) => {
      if (dragRef.current) {
        const nextLeft = (dragRef.current.left || 0) + event.clientX - dragRef.current.x;
        const nextTop = (dragRef.current.top || 0) + event.clientY - dragRef.current.y;
        const normalized = normalizeDrawerLayout({
          left: nextLeft,
          top: nextTop,
          width: layoutRef.current.width,
          height: layoutRef.current.height,
        });
        setPosition({
          left: normalized.left,
          top: normalized.top,
        });
      }
      if (resizeRef.current) {
        const nextWidth = (resizeRef.current.width || 0) + event.clientX - resizeRef.current.x;
        const nextHeight = (resizeRef.current.height || 0) + event.clientY - resizeRef.current.y;
        const normalized = normalizeDrawerLayout({
          left: layoutRef.current.left,
          top: layoutRef.current.top,
          width: nextWidth,
          height: nextHeight,
        });
        setSize({
          width: normalized.width,
          height: normalized.height,
        });
      }
    };
    const handleMouseUp = () => {
      dragRef.current = null;
      resizeRef.current = null;
    };
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, []);

  useEffect(() => {
    if (isMaximized) return;
    persistDrawerLayout(layoutStorageKey, {
      left: position.left,
      top: position.top,
      width: size.width,
      height: size.height,
    });
  }, [isMaximized, layoutStorageKey, position.left, position.top, size.height, size.width]);

  if (!record || Object.keys(record).length === 0) {
    const emptyTitle = getLocalizedText((block as any).empty?.title || 'Select a row', locale, t);
    return (
      <div
        className="rounded-control border-border bg-panel text-text-2 border p-4 text-sm"
        data-testid="review-drawer-empty"
      >
        {emptyTitle}
      </div>
    );
  }

  if (!closeClearsContext && dismissedRecordKey && dismissedRecordKey === selectedRecordKey) {
    return (
      <button
        type="button"
        className="rounded-control bg-panel text-text shadow-pop border-border hover:bg-hover fixed right-4 bottom-4 z-50 border px-4 py-2 text-sm font-medium"
        data-testid="review-drawer-minimized"
        onClick={() => setDismissedRecordKey('')}
      >
        {localized(locale, t, '展开行级复核', 'Open row review')}
      </button>
    );
  }

  const titleTemplate = (block as any).titleTemplate;
  const title = titleTemplate
    ? fillTemplate(
        String(titleTemplate),
        runtime,
        record,
        buildTemplateFieldConfigs(block),
        locale,
        t,
      )
    : getLocalizedText(block.title || 'Review', locale, t);
  const rawRecord = findRelatedRecord(runtime, rawRecordConfig, record);
  const canonicalRecord = findRelatedRecord(runtime, canonicalRecordConfig, record);
  const sourceRecord = sourceRecordConfig.dataSource
    ? findRelatedRecord(runtime, sourceRecordConfig, record)
    : record;
  const sourceSummaryItems =
    sourceConfig.showInMain !== false && Array.isArray(sourceConfig.summary?.items)
      ? sourceConfig.summary.items
      : [];
  const rawFields = Array.isArray(compareConfig.rawFields) ? compareConfig.rawFields : [];
  const rawColumnGroups = resolveRawColumnGroups(rawRecord, compareConfig);
  const canonicalFields = Array.isArray(compareConfig.canonicalFields)
    ? compareConfig.canonicalFields
    : [];
  const resolvedCanonicalGroups = compareConfig.canonicalFieldProfiles
    ? resolveProfiledFieldGroups({
        locale,
        item: {
          fieldColumns: canonicalFields,
          fieldProfiles: compareConfig.canonicalFieldProfiles,
        },
        record: canonicalRecord,
        referenceRecord: record,
      })
    : [];
  const resolvedCanonicalFields =
    resolvedCanonicalGroups.length > 0
      ? resolvedCanonicalGroups.flatMap((group) => group.fields)
      : compareConfig.canonicalFieldProfiles
        ? resolveProfiledFieldColumns({
            item: {
              fieldColumns: canonicalFields,
              fieldProfiles: compareConfig.canonicalFieldProfiles,
            },
            record: canonicalRecord,
            referenceRecord: record,
          })
        : canonicalFields;
  const sourceCards = Array.isArray(sourceConfig.cards) ? sourceConfig.cards : [];
  const sourcePolicies = Array.isArray(sourceConfig.policies) ? sourceConfig.policies : [];
  const sourceResolutions = collectSemanticResolutionTraces(
    sourceRecord,
    sourceConfig.resolutionGroups,
  );
  // Labeled JSON evidence blocks (e.g. handover snapshots). Structured/JSONB data belongs here as a
  // collapsible, formatted, labeled <pre> — not crammed into scalar summaryBadges where it renders as
  // raw inline JSON. Additive alongside the singular sourceConfig.jsonField.
  const sourceJsonFields = Array.isArray(sourceConfig.jsonFields) ? sourceConfig.jsonFields : [];
  const exportFields = Array.isArray(exportConfig.fields) ? exportConfig.fields : [];
  const decisionFields = Array.isArray(candidatesConfig.decisionFields)
    ? candidatesConfig.decisionFields
    : [];
  const visibleCandidateContextFields = candidateContextFields.filter(
    (field: any) => !field.hideWhenEmpty || !isEmptyValue(readFieldValue(record, field)),
  );
  const summaryBadges = Array.isArray((block as any).summaryBadges)
    ? (block as any).summaryBadges
    : [];
  const hasRawComparePanel = rawFields.length > 0 || rawColumnGroups.length > 0;
  const hasComparePanel = hasRawComparePanel || resolvedCanonicalFields.length > 0;
  const hasSourceDetails =
    sourceConfig.showInMain !== false &&
    (sourceResolutions.length > 0 ||
      sourceCards.length > 0 ||
      sourcePolicies.length > 0 ||
      sourceJsonFields.length > 0 ||
      Boolean(sourceConfig.jsonField));
  const hasExportDetails =
    exportConfig.showInMain !== false && (exportFields.length > 0 || exportRows.length > 0);
  const hasLeftRail =
    hasComparePanel || sourceSummaryItems.length > 0 || hasSourceDetails || hasExportDetails;

  const actionContext = {
    ...context,
    record,
    row: record,
    selectedRecord: record,
    selectedCandidate,
  };
  const isActionVisible = (actionConfig: any) =>
    (!actionConfig.permissionCode || hasPermission(actionConfig.permissionCode)) &&
    (!actionConfig.visibleWhen ||
      evaluator.evaluateCondition(actionConfig.visibleWhen, actionContext));
  const isActionDisabledByCondition = (actionConfig: any) =>
    actionConfig.disabledWhen
      ? evaluator.evaluateCondition(actionConfig.disabledWhen, actionContext)
      : false;

  const jumpRow = (offset: number) => {
    if (!contextStateBinding || contextRows.length <= 1) return;
    const currentIndex = contextRows.findIndex((row: any) => {
      const key = readPath(row, contextKeyField);
      return String(key) === String(contextRecordKey);
    });
    if (currentIndex < 0) return;
    const nextIndex = (currentIndex + offset + contextRows.length) % contextRows.length;
    writeRuntimeState(runtime, contextStateBinding, contextRows[nextIndex]);
  };

  const drawerStyle: React.CSSProperties = isMaximized
    ? {
        left: 16,
        top: 16,
        width: 'calc(100vw - 32px)',
        height: 'calc(100vh - 32px)',
        minWidth: 0,
      }
    : {
        left: position.left,
        top: position.top,
        width: size.width,
        height: size.height,
        maxWidth: 'calc(100vw - 24px)',
        maxHeight: 'calc(100vh - 24px)',
        minWidth: 'min(760px, calc(100vw - 24px))',
      };

  return (
    <section
      data-testid="review-drawer"
      style={drawerStyle}
      className={`rounded-card bg-panel shadow-pop border-border fixed z-50 grid min-h-[500px] max-w-[calc(100vw-24px)] overflow-hidden border ${
        isEditFormOpen
          ? 'grid-rows-[auto_auto_minmax(0,1fr)]'
          : 'grid-rows-[auto_auto_auto_minmax(0,1fr)]'
      }`}
    >
      <div
        className={`flex min-h-12 cursor-move items-center justify-between gap-3 overflow-hidden border-b px-4 ${
          usesLightChrome
            ? 'border-border bg-panel text-text'
            : 'border-accent bg-accent text-white'
        }`}
        onMouseDown={(event) => {
          if ((event.target as HTMLElement).closest('button') || isMaximized) return;
          dragRef.current = {
            x: event.clientX,
            y: event.clientY,
            left: position.left,
            top: position.top,
          };
          event.preventDefault();
        }}
      >
        <h2 className="min-w-0 flex-1 truncate text-base font-semibold" title={title}>
          {title}
        </h2>
        <div className="flex shrink-0 items-center gap-1.5">
          <button
            type="button"
            aria-label={localized(locale, t, '上一行', 'Previous row')}
            onClick={() => jumpRow(-1)}
            className={`rounded-control inline-flex h-7 w-7 items-center justify-center text-sm ${
              usesLightChrome ? 'text-text-2 hover:bg-hover' : 'text-white hover:bg-white/15'
            }`}
          >
            ↑
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '下一行', 'Next row')}
            onClick={() => jumpRow(1)}
            className={`rounded-control inline-flex h-7 w-7 items-center justify-center text-sm ${
              usesLightChrome ? 'text-text-2 hover:bg-hover' : 'text-white hover:bg-white/15'
            }`}
          >
            ↓
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '切换最大化', 'Toggle maximize')}
            onClick={() => setIsMaximized((value) => !value)}
            className={`rounded-control inline-flex h-7 w-7 items-center justify-center text-sm ${
              usesLightChrome ? 'text-text-2 hover:bg-hover' : 'text-white hover:bg-white/15'
            }`}
          >
            □
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '关闭复核浮层', 'Close review drawer')}
            onClick={closeDrawer}
            className={`rounded-control inline-flex h-7 w-7 items-center justify-center text-sm ${
              usesLightChrome ? 'text-text-2 hover:bg-hover' : 'text-white hover:bg-white/15'
            }`}
          >
            ×
          </button>
        </div>
      </div>

      {contextSummaryConfig ? (
        <div
          data-testid="review-drawer-context-summary"
          className="border-border bg-panel border-b px-4 py-3"
        >
          <div className="rounded-control border-border bg-subtle flex min-w-0 flex-wrap items-center gap-x-6 gap-y-1 border px-3 py-2 text-sm">
            <div className="min-w-[240px] flex-1 [overflow-wrap:anywhere]">
              <span className="text-text-2">
                {getLocalizedText(
                  contextSummaryConfig.label || { 'zh-CN': '原始需求', en: 'Original request' },
                  locale,
                  t,
                )}
                ：
              </span>
              <span className="text-text font-semibold">
                {formatConfiguredValue(
                  readPath(record, contextSummaryConfig.valueField),
                  contextSummaryConfig,
                  locale,
                  t,
                )}
              </span>
            </div>
            {contextSummaryConfig.quantityField && (
              <div className="shrink-0 whitespace-nowrap">
                <span className="text-text-2">
                  {getLocalizedText(
                    contextSummaryConfig.quantityLabel || {
                      'zh-CN': '需求数量',
                      en: 'Required quantity',
                    },
                    locale,
                    t,
                  )}
                  ：
                </span>
                <span className="text-text font-semibold tabular-nums">
                  {formatValue(readPath(record, contextSummaryConfig.quantityField))}
                </span>
              </div>
            )}
          </div>
        </div>
      ) : (
        <div className="border-border bg-panel flex max-w-full flex-wrap items-center gap-2 overflow-x-auto border-b px-4 py-3">
          {summaryBadges.map((badge: any) => (
            <Badge
              key={String(badge.key || badge.valueField || badge.label)}
              badge={badge}
              record={record}
              runtime={runtime}
              relatedRecords={{ raw: rawRecord, canonical: canonicalRecord, source: sourceRecord }}
              locale={locale}
              t={t}
            />
          ))}
        </div>
      )}

      <DrawerEditForm
        key={selectedRecordKey}
        config={editFormConfig}
        record={record}
        runtime={runtime}
        locale={locale}
        t={t}
        onOpenChange={setIsEditFormOpen}
        renderTriggerInHost={renderEditTriggerInCandidatesHeader}
        triggerHost={editTriggerHost}
        isRecordSelected={() => {
          const current = resolveRuntimeValue(runtime, contextExpression);
          return (
            current != null &&
            Object.keys(current).length > 0 &&
            String(readPath(current, contextKeyField)) === String(contextRecordKey)
          );
        }}
      />

      <div
        data-testid="review-drawer-content-grid"
        data-layout-mode={isCompactReview ? 'compact-review' : 'default'}
        className={`bg-subtle min-h-0 max-w-full overflow-hidden p-4 ${
          isEditFormOpen ? 'hidden' : ''
        }`}
      >
        <div
          data-testid="review-drawer-content-layout"
          className={`grid h-full min-h-0 min-w-0 gap-3 ${
            hasLeftRail && hasCandidatesConfig
              ? isCompactReview
                ? 'grid-rows-[minmax(0,0.25fr)_minmax(0,0.75fr)] md:grid-cols-[minmax(260px,0.34fr)_minmax(0,1fr)] md:grid-rows-1 xl:grid-cols-[minmax(320px,0.34fr)_minmax(0,1fr)]'
                : 'xl:grid-cols-[minmax(0,1fr)_minmax(380px,440px)]'
              : 'grid-cols-1'
          }`}
        >
          {hasLeftRail && (
            <ReviewDrawerEvidenceRail
              hasComparePanel={hasComparePanel}
              isStackedCompare={isStackedCompare}
              hasRawComparePanel={hasRawComparePanel}
              compareConfig={compareConfig}
              locale={locale}
              t={t}
              rawFields={rawFields}
              rawRecord={rawRecord}
              rawColumnGroups={rawColumnGroups}
              resolvedCanonicalFields={resolvedCanonicalFields}
              resolvedCanonicalGroups={resolvedCanonicalGroups}
              canonicalRecord={canonicalRecord}
              record={record}
              sourceSummaryItems={sourceSummaryItems}
              sourceConfig={sourceConfig}
              sourceRecord={sourceRecord}
              hasSourceDetails={hasSourceDetails}
              sourceResolutions={sourceResolutions}
              sourceCards={sourceCards}
              sourcePolicies={sourcePolicies}
              sourceJsonFields={sourceJsonFields}
              hasExportDetails={hasExportDetails}
              exportFields={exportFields}
              exportRows={exportRows}
            />
          )}

          {/* The candidates panel is BOM's: it talks about 候选物料 and 标准编码 and writing a
              chosen code back. Rendering it for a page that never configured `candidates` puts one
              domain's vocabulary in front of another domain's users — a FAQ reviewer has no idea
              what a 标准编码 is, and nothing on the panel does anything. Show it when it is asked for. */}
          {hasCandidatesConfig && (
            <ReviewDrawerCandidatesPanel
              candidatesConfig={candidatesConfig}
              locale={locale}
              t={t}
              renderEditTriggerInCandidatesHeader={renderEditTriggerInCandidatesHeader}
              setEditTriggerHost={setEditTriggerHost}
              exportConfig={exportConfig}
              isActionVisible={isActionVisible}
              isActionDisabledByCondition={isActionDisabledByCondition}
              runningAction={runningAction}
              runAction={runAction}
              candidateState={candidateState}
              isCompactReview={isCompactReview}
              usesCandidateComparisonTable={usesCandidateComparisonTable}
              isRefreshingCandidates={isRefreshingCandidates}
              isInitialCandidateLoading={isInitialCandidateLoading}
              candidates={candidates}
              candidateTableConfig={candidateTableConfig}
              selectedCandidateKey={selectedCandidateKey}
              record={record}
              setSelectedCandidateKey={setSelectedCandidateKey}
              runtime={runtime}
              canonicalRecord={canonicalRecord}
              visibleCandidateContextFields={visibleCandidateContextFields}
              shouldShowDecisionStatus={shouldShowDecisionStatus}
              shouldShowSelectedCandidateDetail={shouldShowSelectedCandidateDetail}
              selectedCandidate={selectedCandidate}
              selectedCandidateFields={selectedCandidateFields}
              selectedCandidateGroups={selectedCandidateGroups}
              decisionFields={decisionFields}
              usesSummaryEvidence={usesSummaryEvidence}
            />
          )}
        </div>
      </div>

      {!isMaximized && (
        <button
          type="button"
          aria-label={localized(locale, t, '调整复核浮层大小', 'Resize review drawer')}
          className="absolute right-0 bottom-0 h-5 w-5 cursor-nwse-resize"
          onMouseDown={(event) => {
            resizeRef.current = {
              x: event.clientX,
              y: event.clientY,
              width: size.width,
              height: size.height,
            };
            event.preventDefault();
          }}
        >
          <span className="border-accent absolute right-1 bottom-1 h-2 w-2 border-r-2 border-b-2" />
        </button>
      )}
    </section>
  );
};

export default ReviewDrawerBlockRenderer;
