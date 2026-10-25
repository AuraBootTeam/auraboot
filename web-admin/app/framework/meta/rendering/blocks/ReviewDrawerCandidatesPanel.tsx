import { comparisonStatusLabel } from './ReviewDrawerEvidence';
import type React from 'react';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  comparisonStatusFieldClass,
  comparisonStatusForField,
  resolveCandidateFieldColumns,
} from './ReviewDrawerCandidateFields';
import {
  CandidateComparisonTable,
  CandidateFieldTable,
  CandidateSelectionSummary,
} from './ReviewDrawerCandidateTable';
import { EvidenceSummary } from './ReviewDrawerEvidence';
import { FieldGroups, FieldRows } from './ReviewDrawerFields';
import { PriceComparison, PriceLadder } from './ReviewDrawerPrices';
import {
  buttonClass,
  candidateKey,
  formatConfiguredValue,
  formatValue,
  isEmptyValue,
  localized,
  parseLadderRungs,
  readFieldValue,
  safeExternalUrl,
  scoreToneClass,
} from './reviewDrawerValues';
import { readPath, writeRuntimeState } from './workbenchBlockUtils';

export function ReviewDrawerCandidatesPanel({
  candidatesConfig,
  locale,
  t,
  renderEditTriggerInCandidatesHeader,
  setEditTriggerHost,
  exportConfig,
  isActionVisible,
  isActionDisabledByCondition,
  runningAction,
  runAction,
  candidateState,
  isCompactReview,
  usesCandidateComparisonTable,
  isRefreshingCandidates,
  isInitialCandidateLoading,
  candidates,
  candidateTableConfig,
  selectedCandidateKey,
  record,
  setSelectedCandidateKey,
  runtime,
  canonicalRecord,
  visibleCandidateContextFields,
  shouldShowDecisionStatus,
  shouldShowSelectedCandidateDetail,
  selectedCandidate,
  selectedCandidateFields,
  selectedCandidateGroups,
  decisionFields,
  usesSummaryEvidence,
}: {
  candidatesConfig: any;
  locale: string;
  t: (key: string, vars?: Record<string, any>) => string;
  renderEditTriggerInCandidatesHeader: boolean;
  setEditTriggerHost: React.Dispatch<React.SetStateAction<HTMLDivElement | null>>;
  exportConfig: any;
  isActionVisible: (actionConfig: any) => boolean;
  isActionDisabledByCondition: (actionConfig: any) => boolean;
  runningAction: string | null;
  runAction: (actionConfig: any, source: 'candidate' | 'export') => Promise<void>;
  candidateState: any;
  isCompactReview: boolean;
  usesCandidateComparisonTable: boolean;
  isRefreshingCandidates: boolean;
  isInitialCandidateLoading: boolean;
  candidates: any[];
  candidateTableConfig: any;
  selectedCandidateKey: string;
  record: any;
  setSelectedCandidateKey: React.Dispatch<React.SetStateAction<string>>;
  runtime: SchemaRuntime;
  canonicalRecord: any;
  visibleCandidateContextFields: any;
  shouldShowDecisionStatus: boolean;
  shouldShowSelectedCandidateDetail: boolean;
  selectedCandidate: any;
  selectedCandidateFields: any;
  selectedCandidateGroups: any;
  decisionFields: any;
  usesSummaryEvidence: boolean;
}) {
  return (
    <aside
      data-testid="review-drawer-tab-candidates"
      className="rounded-card border-border bg-panel flex h-full min-h-0 min-w-0 flex-col overflow-hidden border"
    >
      <header
        data-testid="review-drawer-candidates-header"
        className="border-border flex flex-wrap items-center justify-between gap-2 border-b px-3 py-2"
      >
        <h3 className="text-text min-w-0 flex-1 truncate text-sm font-semibold">
          {getLocalizedText(
            candidatesConfig.title || {
              'zh-CN': '候选物料与用户决策',
              en: 'Candidates and decision',
            },
            locale,
            t,
          )}
        </h3>
        {renderEditTriggerInCandidatesHeader && (
          <div
            ref={setEditTriggerHost}
            data-testid="review-drawer-edit-open-host"
            className="flex shrink-0"
          />
        )}
        {(exportConfig.actions || []).length > 0 && (
          <div className="flex shrink-0 flex-wrap justify-end gap-2">
            {exportConfig.actions.filter(isActionVisible).map((actionConfig: any) => {
              const code = String(actionConfig.code || actionConfig.id || actionConfig.label);
              const disabled = Boolean(isActionDisabledByCondition(actionConfig) || runningAction);
              return (
                <button
                  key={code}
                  type="button"
                  data-testid={`review-drawer-export-action-${code}`}
                  disabled={disabled}
                  onClick={() => {
                    void runAction(actionConfig, 'export');
                  }}
                  className={`rounded-control px-3 py-2 text-sm font-medium ${
                    buttonClass[actionConfig.variant || 'secondary'] || buttonClass.secondary
                  } disabled:cursor-not-allowed disabled:opacity-50`}
                >
                  {runningAction === `export:${code}`
                    ? t('common.loading')
                    : getLocalizedText(actionConfig.label || code, locale, t)}
                </button>
              );
            })}
          </div>
        )}
      </header>
      <div
        data-testid="review-drawer-candidate-list"
        aria-busy={candidateState?.loading ? true : undefined}
        className={`relative flex-1 [scrollbar-gutter:stable] overflow-auto ${
          isCompactReview ? 'min-h-[160px]' : 'min-h-0'
        } ${usesCandidateComparisonTable ? 'p-0' : 'space-y-1.5 p-2'}`}
      >
        {isRefreshingCandidates && (
          <div
            role="status"
            data-testid="review-drawer-candidates-refreshing"
            className="rounded-pill bg-panel/90 text-text-2 pointer-events-none absolute top-2 right-2 z-10 border px-2 py-1 text-xs shadow-sm"
          >
            {getLocalizedText({ 'zh-CN': '正在刷新…', en: 'Refreshing…' }, locale, t)}
          </div>
        )}
        {isInitialCandidateLoading ? (
          <div
            role="status"
            data-testid="review-drawer-candidates-loading"
            className="text-text-2 p-3 text-sm"
          >
            {getLocalizedText(
              { 'zh-CN': '正在加载候选物料…', en: 'Loading candidates…' },
              locale,
              t,
            )}
          </div>
        ) : candidateState?.error ? (
          <div
            role="alert"
            data-testid="review-drawer-candidates-error"
            className="text-text-2 p-3 text-sm"
          >
            {getLocalizedText(
              {
                'zh-CN': '候选物料加载失败，请重新打开复核面板。',
                en: 'Could not load candidates. Close and reopen the review panel to try again.',
              },
              locale,
              t,
            )}
          </div>
        ) : candidates.length === 0 ? (
          <div
            data-testid="review-drawer-candidates-empty"
            className="rounded-control border-border-strong text-text-2 border border-dashed p-3 text-sm"
          >
            {getLocalizedText(
              candidatesConfig.empty?.title || { 'zh-CN': '暂无候选', en: 'No candidates' },
              locale,
              t,
            )}
          </div>
        ) : usesCandidateComparisonTable ? (
          <CandidateComparisonTable
            candidates={candidates}
            tableConfig={candidateTableConfig}
            selectedKey={selectedCandidateKey}
            recommendedKey={
              candidateTableConfig.recommendedKeyField
                ? String(readPath(record, candidateTableConfig.recommendedKeyField) || '')
                : undefined
            }
            referenceRecord={record}
            locale={locale}
            t={t}
            testIdPrefix="review-drawer-candidate"
            keyField={candidateTableConfig.keyField}
            confirmableField={candidateTableConfig.confirmableField}
            onSelect={(candidate, rowKey) => {
              setSelectedCandidateKey(rowKey);
              if (candidatesConfig.selection?.bind) {
                writeRuntimeState(runtime, candidatesConfig.selection.bind, candidate);
              }
            }}
          />
        ) : (
          candidates.map((candidate: any, index: number) => {
            const rowKey = candidateKey(candidate, index);
            const scoreKey = String(
              candidate?.bom_me_material_code ?? candidate?.materialCode ?? rowKey,
            );
            const active = rowKey === selectedCandidateKey;
            const item = candidatesConfig.item || {};
            const titleText = formatValue(readPath(candidate, item.titleField), rowKey);
            const score = item.scoreField ? readPath(candidate, item.scoreField) : undefined;
            const scoreColor = item.statusColorField
              ? readPath(candidate, item.statusColorField)
              : undefined;
            const fieldColumns = resolveCandidateFieldColumns({
              locale,
              item,
              candidate,
              referenceRecord: {
                ...(record || {}),
                ...(canonicalRecord || {}),
              },
            });
            const hasUsableLadder = fieldColumns.some((field: any) => {
              if (field.format !== 'ladder') return false;
              const rungs = parseLadderRungs(readFieldValue(candidate, field));
              return Boolean(rungs && rungs.length >= 2);
            });
            const badgeFields = Array.isArray(item.badgeFields) ? item.badgeFields : [];
            const usesFieldTable =
              candidatesConfig.layout === 'fieldTable' || item.layout === 'fieldTable';
            return (
              <button
                key={rowKey}
                type="button"
                data-testid={`review-drawer-candidate-${rowKey}`}
                onClick={() => {
                  setSelectedCandidateKey(rowKey);
                  if (candidatesConfig.selection?.bind) {
                    writeRuntimeState(runtime, candidatesConfig.selection.bind, candidate);
                  }
                }}
                className={`rounded-card block w-full border p-3 text-left select-text ${
                  active ? 'bg-accent-weak border-accent' : 'border-border bg-panel hover:bg-hover'
                }`}
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <div
                      className="text-text font-mono text-xs font-semibold break-words whitespace-normal"
                      title={titleText}
                    >
                      {titleText}
                    </div>
                    {usesFieldTable ? (
                      <CandidateFieldTable
                        rowKey={rowKey}
                        candidate={candidate}
                        fields={fieldColumns}
                        badges={badgeFields}
                        locale={locale}
                        t={t}
                      />
                    ) : (
                      <dl className="mt-2 grid gap-x-3 gap-y-1.5 text-xs sm:grid-cols-2">
                        {fieldColumns.map((field: any) => {
                          const key = String(field.key || field.field || field.label);
                          const label = getLocalizedText(field.label || key, locale, t);
                          const rawValue = readFieldValue(candidate, field);
                          const ladder =
                            field.format === 'ladder' ? parseLadderRungs(rawValue) : null;
                          if (field.format === 'price-comparison' && hasUsableLadder) return null;
                          if (field.format === 'ladder' && (!ladder || ladder.length < 2))
                            return null;
                          if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
                          const value = formatConfiguredValue(rawValue, field, locale, t);
                          const comparisonStatus = comparisonStatusForField(candidate, field);
                          const comparisonClass = comparisonStatusFieldClass(comparisonStatus, field);
                          return (
                            <div
                              key={key}
                              data-testid={`review-drawer-candidate-${rowKey}-field-${key}`}
                              data-comparison-status={
                                comparisonStatus ? String(comparisonStatus) : undefined
                              }
                              className={`min-w-0 ${comparisonClass} ${
                                field.span === 2 ? 'sm:col-span-2' : ''
                              } grid grid-cols-[72px_minmax(0,1fr)] gap-2 ${
                                // A ladder is a multi-row card, so align its label to the top of
                                // the card and give the card room below it instead of letting the
                                // next field sit tight against it.
                                field.format === 'ladder' ? 'items-start pb-1' : 'items-baseline'
                              }`}
                            >
                              <dt className="text-text-2 min-w-0 break-words" title={label}>
                                {label}
                                {field.comparisonShowStatus && comparisonStatus && (
                                  <span className="ml-1 text-[10px]">· {comparisonStatusLabel(comparisonStatus, locale, t)}</span>
                                )}
                              </dt>
                              <dd
                                className="text-text min-w-0 break-words whitespace-normal"
                                title={value}
                              >
                                {field.format === 'price-comparison' ? (
                                  <PriceComparison
                                    original={rawValue}
                                    factored={readFieldValue(candidate, {
                                      field: field.factoredField,
                                    })}
                                    factor={readFieldValue(candidate, {
                                      field: field.factorField,
                                    })}
                                    locale={locale}
                                    t={t}
                                  />
                                ) : field.format === 'ladder' && ladder ? (
                                  <PriceLadder
                                    rowKey={rowKey}
                                    rungs={ladder}
                                    factor={readFieldValue(candidate, {
                                      field: field.factorField,
                                    })}
                                    locale={locale}
                                    t={t}
                                  />
                                ) : field.format === 'link' && safeExternalUrl(rawValue) ? (
                                  <a
                                    href={safeExternalUrl(rawValue) as string}
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    className="text-accent underline underline-offset-2"
                                    data-testid={`review-drawer-candidate-${rowKey}-link-${key}`}
                                  >
                                    {getLocalizedText(
                                      field.linkLabel || { 'zh-CN': '查看', en: 'Open' },
                                      locale,
                                      t,
                                    )}
                                  </a>
                                ) : (
                                  value
                                )}
                              </dd>
                            </div>
                          );
                        })}
                      </dl>
                    )}
                  </div>
                  {score !== undefined && (
                    <span
                      data-testid={`review-drawer-candidate-${scoreKey}-score`}
                      className={`rounded-pill px-1.5 py-0.5 text-xs font-semibold ${scoreToneClass(
                        scoreColor,
                      )}`}
                    >
                      {String(score)}
                    </span>
                  )}
                </div>
              </button>
            );
          })
        )}
      </div>
      {(visibleCandidateContextFields.length > 0 ||
        shouldShowDecisionStatus ||
        (shouldShowSelectedCandidateDetail &&
          selectedCandidate &&
          (selectedCandidateFields.length > 0 || selectedCandidateGroups.length > 0))) && (
        <section
          data-testid="review-drawer-decision-panel"
          className={`bg-subtle border-border shrink-0 overflow-auto border-t p-2.5 ${
            isCompactReview ? 'max-h-[28%]' : 'max-h-[48%]'
          }`}
        >
          {visibleCandidateContextFields.length > 0 && (
            <section data-testid="review-drawer-context-evidence">
              <FieldRows
                fields={visibleCandidateContextFields}
                record={record}
                locale={locale}
                t={t}
              />
            </section>
          )}
          {shouldShowDecisionStatus && (
            <section data-testid="review-drawer-decision-status">
              <h3 className="text-text text-sm font-semibold">
                {getLocalizedText(
                  candidatesConfig.decisionTitle || {
                    'zh-CN': '当前决策状态',
                    en: 'Decision',
                  },
                  locale,
                  t,
                )}
              </h3>
              <dl className="mt-2 space-y-1.5 text-sm">
                {decisionFields.length > 0 ? (
                  decisionFields.map((field: any) => {
                    const key = String(field.key || field.field || field.label);
                    const label = getLocalizedText(field.label || key, locale, t);
                    const rawValue = readFieldValue(record, field);
                    if (field.hideWhenEmpty && isEmptyValue(rawValue)) return null;
                    const value = formatConfiguredValue(rawValue, field, locale, t);
                    return (
                      <div key={key} className="grid grid-cols-[96px_minmax(0,1fr)] gap-2">
                        <dt className="text-text-2 text-xs">{label}</dt>
                        <dd className="text-text break-words">{value}</dd>
                      </div>
                    );
                  })
                ) : (
                  <>
                    <div className="grid grid-cols-[96px_minmax(0,1fr)] gap-2">
                      <dt className="text-text-2 text-xs">
                        {localized(locale, t, '标准编码', 'Standard Code')}
                      </dt>
                      <dd className="text-text font-mono">
                        {formatValue(
                          readPath(record, 'bom_std_material_code'),
                          localized(locale, t, '确认候选后写入', 'Pending confirmation'),
                        )}
                      </dd>
                    </div>
                    <div className="grid grid-cols-[96px_minmax(0,1fr)] gap-2">
                      <dt className="text-text-2 text-xs">
                        {localized(locale, t, '当前状态', 'Reason')}
                      </dt>
                      <dd className="text-text break-words">
                        {formatConfiguredValue(
                          readPath(record, 'bom_std_reason_code'),
                          candidatesConfig.reasonField || {},
                          locale,
                          t,
                        )}
                      </dd>
                    </div>
                  </>
                )}
              </dl>
            </section>
          )}
          {shouldShowSelectedCandidateDetail &&
            selectedCandidate &&
            (selectedCandidateFields.length > 0 || selectedCandidateGroups.length > 0) && (
              <section
                className={`rounded-control border-border bg-panel border ${
                  shouldShowDecisionStatus ? 'mt-3' : ''
                }`}
              >
                <header className="border-border text-text-2 border-b px-3 py-1.5 text-xs font-semibold">
                  {getLocalizedText(
                    candidatesConfig.selectedTitle || {
                      'zh-CN': '匹配证据',
                      en: 'Match Evidence',
                    },
                    locale,
                    t,
                  )}
                </header>
                {usesSummaryEvidence ? (
                  <div className="space-y-2 p-2.5">
                    <EvidenceSummary candidate={selectedCandidate} locale={locale} t={t} />
                  </div>
                ) : selectedCandidateGroups.length > 0 ? (
                  <FieldGroups
                    groups={selectedCandidateGroups}
                    record={selectedCandidate}
                    locale={locale}
                    t={t}
                  />
                ) : (
                  <FieldRows
                    fields={selectedCandidateFields}
                    record={selectedCandidate}
                    locale={locale}
                    t={t}
                  />
                )}
              </section>
            )}
        </section>
      )}
      {/*
              The actions live outside the decision panel on purpose. Inside it they were the last
              child of a max-h-[48%] overflow-auto section, so "确认此报价" scrolled out of sight
              behind the evidence it was meant to confirm. As a shrink-0 sibling in the aside's flex
              column they stay on screen however long the candidate detail runs.
            */}
      {(candidatesConfig.actions || []).length > 0 && (
        <footer
          data-testid="review-drawer-actions"
          className="border-border bg-panel flex shrink-0 flex-wrap items-center justify-between gap-3 border-t px-3 py-2.5"
        >
          {usesCandidateComparisonTable && selectedCandidate ? (
            <CandidateSelectionSummary
              candidate={selectedCandidate}
              config={candidateTableConfig.selectionSummary}
              referenceRecord={record}
              locale={locale}
              t={t}
            />
          ) : (
            <span />
          )}
          <div className="flex flex-wrap justify-end gap-2">
            {candidatesConfig.actions.filter(isActionVisible).map((actionConfig: any) => {
              const code = String(actionConfig.code || actionConfig.id || actionConfig.label);
              const requiresSelection =
                actionConfig.requiresSelection !== false && actionConfig.code !== 'undo_decision';
              const disabled = Boolean(
                (requiresSelection && !selectedCandidate) ||
                isActionDisabledByCondition(actionConfig) ||
                runningAction,
              );
              return (
                <button
                  key={code}
                  type="button"
                  data-testid={`review-drawer-candidate-action-${code}`}
                  disabled={disabled}
                  onClick={() => {
                    void runAction(actionConfig, 'candidate');
                  }}
                  className={`rounded-control px-3 py-2 text-sm font-medium ${
                    buttonClass[actionConfig.variant || 'primary'] || buttonClass.primary
                  } disabled:cursor-not-allowed disabled:opacity-50`}
                >
                  {runningAction === `candidate:${code}`
                    ? t('common.loading')
                    : getLocalizedText(actionConfig.label || code, locale, t)}
                </button>
              );
            })}
          </div>
        </footer>
      )}
    </aside>
  );
}
