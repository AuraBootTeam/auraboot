import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useAuth } from '~/contexts/AuthContext';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { CandidateComparisonTable, CandidateSelectionSummary } from './ReviewDrawerCandidateTable';
import { FieldRows } from './ReviewDrawerFields';
import { PriceComparison, PriceLadder } from './ReviewDrawerPrices';
import {
  formatConfiguredValue,
  isEmptyValue,
  parseLadderRungs,
  safeExternalUrl,
} from './reviewDrawerValues';
import {
  executeSimpleWorkbenchAction,
  readDataSourceRows,
  readPath,
  writeRuntimeState,
} from './workbenchBlockUtils';

/**
 * An optional inline edit form at the top of the drawer.
 *
 * This table opens the drawer on a single row click, so double-click-to-edit in the grid fights it
 * — a dblclick is two clicks, each of which opens the drawer first. Editing lives here instead,
 * beside the line's own context, which is also where a reviewer who spotted a wrong value is
 * already looking. Fields are declared on the block; submitting runs a command with the collected
 * values, so a corrected part number or per-set usage re-prices without leaving the panel.
 */
export function DrawerEditForm({
  config,
  record,
  runtime,
  locale,
  t,
  onOpenChange,
  renderTriggerInHost = false,
  triggerHost,
  isRecordSelected,
}: {
  config: any;
  record: any;
  runtime: SchemaRuntime;
  locale: string;
  t: (key: string) => string;
  onOpenChange?: (open: boolean) => void;
  renderTriggerInHost?: boolean;
  triggerHost?: HTMLElement | null;
  isRecordSelected?: () => boolean;
}) {
  const fields: any[] = Array.isArray(config?.fields) ? config.fields : [];
  const [open, setOpen] = useState(false);
  const [values, setValues] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<Record<string, any> | null>(null);
  const [selectedPreviewId, setSelectedPreviewId] = useState('');

  const { hasPermission } = useAuth();
  const canEdit = !config?.permissionCode || hasPermission(config.permissionCode);
  const canEditRef = useRef(canEdit);
  canEditRef.current = canEdit;
  const permissionEpoch = useRef({ allowed: canEdit, generation: 0 });
  if (permissionEpoch.current.allowed !== canEdit) {
    permissionEpoch.current = {
      allowed: canEdit,
      generation: permissionEpoch.current.generation + 1,
    };
  }
  useEffect(() => {
    if (canEdit) return;
    setOpen(false);
    setValues({});
    setPreview(null);
    setSelectedPreviewId('');
    setError(null);
    onOpenChange?.(false);
  }, [canEdit, onOpenChange]);

  const isTwoPhase = Boolean(config?.previewCommand && config?.confirmCommand);
  if (fields.length === 0 || (!config?.command && !isTwoPhase))
    return <div data-testid="review-drawer-edit-form-empty" />;
  const recordPid = record ? String(record.pid ?? '') : '';
  const disabled = !recordPid || !canEdit;

  function begin() {
    if (disabled) return;
    const seed: Record<string, string> = {};
    for (const f of fields) {
      const raw = Object.prototype.hasOwnProperty.call(f, 'defaultValue')
        ? f.defaultValue
        : readPath(record, f.valueField || f.field);
      seed[f.field] = raw === undefined || raw === null ? '' : String(raw);
    }
    if (isTwoPhase && config.modeField && config.searchField && config.modeValueFields) {
      let mode =
        seed[config.modeField] ||
        String(fields.find((field: any) => field.field === config.modeField)?.defaultValue || '');
      const exactValue = readPath(record, config.modeValueFields.exact);
      if (
        mode === 'exact' &&
        (exactValue === undefined || exactValue === null || exactValue === '')
      ) {
        mode = 'spec';
      }
      seed[config.modeField] = mode;
      const sourceField = config.modeValueFields[mode];
      const searchValue = sourceField ? readPath(record, sourceField) : '';
      seed[config.searchField] =
        searchValue === undefined || searchValue === null ? '' : String(searchValue);
    }
    setValues(seed);
    setError(null);
    setPreview(null);
    setSelectedPreviewId('');
    setOpen(true);
    onOpenChange?.(true);
  }

  async function submit() {
    if (!canEditRef.current) return;
    const permissionGeneration = permissionEpoch.current.generation;
    // A field marked required must not be cleared: a non-standard BOM's description is Yunhan's
    // search key, so submitting it blank would re-price against nothing.
    const missing = fields.find((f: any) => f.required && (values[f.field] ?? '').trim() === '');
    if (missing) {
      setError(
        getLocalizedText(
          missing.requiredMessage || { 'zh-CN': '规格描述不能为空', en: 'Description is required' },
          locale,
          t,
        ),
      );
      return;
    }
    setSaving(true);
    setError(null);
    const payload: Record<string, string> = {};
    for (const f of fields) {
      const v = (values[f.field] ?? '').trim();
      if (v !== '') payload[f.field] = v;
    }
    try {
      const result = await executeSimpleWorkbenchAction(runtime, {
        action: 'command.execute',
        args: {
          command: isTwoPhase ? config.previewCommand : config.command,
          targetRecordPid: recordPid,
          payload,
          reload: isTwoPhase ? [] : Array.isArray(config.reload) ? config.reload : [],
        },
      });
      if (!canEditRef.current || permissionGeneration !== permissionEpoch.current.generation)
        return;
      if (isTwoPhase) {
        if (!result || typeof result !== 'object') {
          throw new Error(
            getLocalizedText(
              { 'zh-CN': '查价未返回可预览结果', en: 'The price search returned no preview' },
              locale,
              t,
            ),
          );
        }
        const resultRecord = result as Record<string, any>;
        setPreview(resultRecord);
        const candidateConfig = config?.preview || {};
        const candidateRows = Array.isArray(readPath(resultRecord, candidateConfig.candidatesField))
          ? readPath(resultRecord, candidateConfig.candidatesField)
          : [];
        const idField = candidateConfig.previewIdField || 'previewId';
        const recommendedId = readPath(
          resultRecord,
          candidateConfig.recommendedIdField || 'recommendedPreviewId',
        );
        const recommended = candidateRows.find(
          (candidate: any) => String(readPath(candidate, idField)) === String(recommendedId || ''),
        );
        const firstConfirmable = candidateRows.find((candidate: any) =>
          Boolean(readPath(candidate, candidateConfig.confirmableField || 'confirmable')),
        );
        const initial = recommended || firstConfirmable || candidateRows[0];
        setSelectedPreviewId(initial ? String(readPath(initial, idField) || '') : '');
      } else {
        setOpen(false);
        onOpenChange?.(false);
      }
    } catch (e: any) {
      if (!canEditRef.current || permissionGeneration !== permissionEpoch.current.generation)
        return;
      setError(e?.message || String(e));
    } finally {
      setSaving(false);
    }
  }

  async function confirmPreview() {
    if (!canEditRef.current || !preview || !isTwoPhase) return;
    const permissionGeneration = permissionEpoch.current.generation;
    const previewIdField = config.preview?.previewIdField || 'previewId';
    const candidateRows = Array.isArray(readPath(preview, config.preview?.candidatesField))
      ? readPath(preview, config.preview?.candidatesField)
      : [];
    const selectedPreview = candidateRows.find(
      (candidate: any) => String(readPath(candidate, previewIdField)) === selectedPreviewId,
    );
    const previewId = readPath(selectedPreview || preview, previewIdField);
    if (!previewId) {
      setError(
        getLocalizedText(
          { 'zh-CN': '查价预览缺少编号，请重新查价', en: 'Preview id is missing; search again' },
          locale,
          t,
        ),
      );
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const result = await executeSimpleWorkbenchAction(runtime, {
        action: 'command.execute',
        args: {
          command: config.confirmCommand,
          targetRecordPid: recordPid,
          payload: { previewId },
          reload: Array.isArray(config.reload) ? config.reload : [],
        },
      });
      if (!canEditRef.current || permissionGeneration !== permissionEpoch.current.generation)
        return;
      // A delayed reload must not reopen a closed drawer or replace a newly selected row.
      if (isRecordSelected && !isRecordSelected()) return;
      for (const selection of Array.isArray(config.afterConfirmSelections)
        ? config.afterConfirmSelections
        : []) {
        const resultKey = readPath(result, selection.resultField);
        const row = readDataSourceRows(runtime, selection.dataSource).find(
          (candidate: any) =>
            String(readPath(candidate, selection.keyField || 'pid')) === String(resultKey),
        );
        if (row && selection.bind) writeRuntimeState(runtime, selection.bind, row);
      }
      setPreview(null);
      setSelectedPreviewId('');
      setOpen(false);
      onOpenChange?.(false);
    } catch (e: any) {
      if (!canEditRef.current || permissionGeneration !== permissionEpoch.current.generation)
        return;
      setError(e?.message || String(e));
    } finally {
      setSaving(false);
    }
  }

  function cancel() {
    setPreview(null);
    setSelectedPreviewId('');
    setError(null);
    setOpen(false);
    onOpenChange?.(false);
  }

  function updateValue(key: string, value: string) {
    setValues((previous) => {
      const next = { ...previous, [key]: value };
      if (
        isTwoPhase &&
        key === config.modeField &&
        config.searchField &&
        config.modeValueFields?.[value]
      ) {
        const raw = readPath(record, config.modeValueFields[value]);
        next[config.searchField] = raw === undefined || raw === null ? '' : String(raw);
      }
      return next;
    });
    if (error) setError(null);
  }

  const previewConfig = config?.preview || {};
  const previewFields: any[] = Array.isArray(previewConfig.fields) ? previewConfig.fields : [];
  const previewCandidates: any[] =
    preview && Array.isArray(readPath(preview, previewConfig.candidatesField))
      ? readPath(preview, previewConfig.candidatesField)
      : [];
  const selectedPreviewCandidate = previewCandidates.find(
    (candidate: any) =>
      String(readPath(candidate, previewConfig.previewIdField || 'previewId')) ===
      selectedPreviewId,
  );
  const confirmable = preview
    ? Boolean(
        readPath(
          selectedPreviewCandidate || preview,
          previewConfig.confirmableField || 'confirmable',
        ),
      )
    : false;

  const openButton = (
    <button
      type="button"
      data-testid="review-drawer-edit-open"
      disabled={disabled}
      onClick={begin}
      className={`rounded-control border px-3 py-1.5 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-50 ${
        renderTriggerInHost
          ? 'border-accent bg-panel text-accent hover:bg-accent-weak'
          : 'border-border bg-panel text-text hover:bg-hover'
      }`}
    >
      {getLocalizedText(
        config.openLabel || { 'zh-CN': '编辑此行并重新查价', en: 'Edit this row' },
        locale,
        t,
      )}
    </button>
  );

  if (!open && renderTriggerInHost) {
    return triggerHost ? createPortal(openButton, triggerHost) : null;
  }

  return (
    <div
      data-testid="review-drawer-edit-form"
      className={`border-border bg-panel min-h-0 border-b ${
        open ? 'flex h-full flex-col overflow-hidden' : 'px-4 py-2'
      }`}
    >
      {!open ? (
        openButton
      ) : preview ? (
        <div
          data-testid="review-drawer-edit-preview"
          className="flex h-full min-h-0 flex-col overflow-hidden"
        >
          <div
            data-testid="review-drawer-edit-scroll"
            className="min-h-0 flex-1 overflow-y-auto p-3"
          >
            <section className="rounded-control border-border bg-subtle overflow-hidden border">
              <header className="border-border bg-panel text-text border-b px-3 py-2 text-sm font-semibold">
                {getLocalizedText(
                  previewConfig.title || { 'zh-CN': '查价预览', en: 'Price Preview' },
                  locale,
                  t,
                )}
              </header>
              {previewConfig.notice && (
                <div
                  data-testid="review-drawer-edit-preview-notice"
                  className="border-border bg-accent-weak text-text-2 border-b px-3 py-2 text-xs"
                >
                  {getLocalizedText(previewConfig.notice, locale, t)}
                </div>
              )}
              {previewFields.length > 0 && (
                <FieldRows
                  fields={previewFields}
                  record={preview}
                  locale={locale}
                  t={t}
                  layout={previewConfig.layout}
                />
              )}
              {previewCandidates.length > 0 && (
                <div
                  className="border-border border-t p-3"
                  data-testid="review-drawer-edit-preview-candidates"
                >
                  <div className="mb-2 flex items-center justify-between gap-2">
                    <h4 className="text-text text-sm font-semibold">
                      {getLocalizedText(
                        previewConfig.candidatesTitle || {
                          'zh-CN': '产品与报价候选',
                          en: 'Product and price candidates',
                        },
                        locale,
                        t,
                      )}
                    </h4>
                    <span className="text-text-3 text-xs">
                      {previewCandidates.length}{' '}
                      {locale.toLowerCase().startsWith('zh') ? '个候选' : 'candidates'}
                    </span>
                  </div>
                  {previewConfig.candidateLayout === 'comparisonTable' ? (
                    <CandidateComparisonTable
                      candidates={previewCandidates}
                      tableConfig={previewConfig.candidateTable || {}}
                      selectedKey={selectedPreviewId}
                      recommendedKey={String(
                        readPath(
                          preview,
                          previewConfig.recommendedIdField || 'recommendedPreviewId',
                        ) || '',
                      )}
                      referenceRecord={preview}
                      locale={locale}
                      t={t}
                      testIdPrefix="review-drawer-edit-preview-candidate"
                      keyField={previewConfig.previewIdField || 'previewId'}
                      confirmableField={previewConfig.confirmableField || 'confirmable'}
                      onSelect={(_candidate, candidateId) => setSelectedPreviewId(candidateId)}
                    />
                  ) : (
                    <div className="grid gap-2" role="radiogroup">
                      {previewCandidates.map((candidate: any, index: number) => {
                        const candidateId = String(
                          readPath(candidate, previewConfig.previewIdField || 'previewId') || index,
                        );
                        const selected = candidateId === selectedPreviewId;
                        const candidateConfirmable = Boolean(
                          readPath(candidate, previewConfig.confirmableField || 'confirmable'),
                        );
                        const matchScore = readPath(
                          candidate,
                          previewConfig.candidateMatchScoreField || 'matchScore',
                        );
                        const procurementScore = readPath(
                          candidate,
                          previewConfig.candidateProcurementScoreField || 'procurementScore',
                        );
                        const reasons = readPath(
                          candidate,
                          previewConfig.candidateReasonsField || 'matchReasons',
                        );
                        const candidateFields = Array.isArray(previewConfig.candidateFields)
                          ? previewConfig.candidateFields
                          : [];
                        const hasUsableLadder = candidateFields.some((field: any) => {
                          if (field.format !== 'ladder') return false;
                          const rungs = parseLadderRungs(readPath(candidate, field.field));
                          return Boolean(rungs && rungs.length >= 2);
                        });
                        return (
                          <div
                            key={candidateId}
                            role="radio"
                            tabIndex={candidateConfirmable ? 0 : -1}
                            aria-checked={selected}
                            aria-disabled={!candidateConfirmable}
                            onClick={() => {
                              if (candidateConfirmable) setSelectedPreviewId(candidateId);
                            }}
                            onKeyDown={(event) => {
                              if (
                                candidateConfirmable &&
                                (event.key === 'Enter' || event.key === ' ')
                              ) {
                                event.preventDefault();
                                setSelectedPreviewId(candidateId);
                              }
                            }}
                            data-testid={`review-drawer-edit-preview-candidate-${candidateId}`}
                            className={`rounded-card border p-3 text-left transition-colors ${
                              selected
                                ? 'border-accent bg-accent-weak ring-accent ring-1'
                                : 'border-border bg-panel hover:bg-hover'
                            } ${candidateConfirmable ? 'cursor-pointer' : 'cursor-not-allowed opacity-60'}`}
                          >
                            <div className="flex flex-wrap items-start justify-between gap-2">
                              <div className="min-w-0">
                                <div className="text-text font-semibold [overflow-wrap:anywhere]">
                                  {String(
                                    readPath(
                                      candidate,
                                      previewConfig.candidateTitleField || 'productModel',
                                    ) || '—',
                                  )}
                                </div>
                                <div className="text-text-2 mt-0.5 text-xs [overflow-wrap:anywhere]">
                                  {String(
                                    readPath(
                                      candidate,
                                      previewConfig.candidateSubtitleField || 'description',
                                    ) || '—',
                                  )}
                                </div>
                              </div>
                              <div className="flex shrink-0 flex-wrap gap-1.5 text-xs">
                                {matchScore !== undefined && matchScore !== null && (
                                  <span className="rounded-full bg-blue-50 px-2 py-1 text-blue-700">
                                    {locale.toLowerCase().startsWith('zh') ? '匹配' : 'Match'}{' '}
                                    {String(matchScore)}
                                  </span>
                                )}
                                {procurementScore !== undefined && procurementScore !== null && (
                                  <span className="rounded-full bg-emerald-50 px-2 py-1 text-emerald-700">
                                    {locale.toLowerCase().startsWith('zh') ? '采购' : 'Buy'}{' '}
                                    {String(procurementScore)}
                                  </span>
                                )}
                              </div>
                            </div>
                            <div className="mt-2 grid gap-x-4 gap-y-1 sm:grid-cols-2">
                              {candidateFields.map((field: any) => {
                                const key = String(field.key || field.field || field.label);
                                const value = readPath(candidate, field.field);
                                const ladder =
                                  field.format === 'ladder' ? parseLadderRungs(value) : null;
                                if (field.format === 'price-comparison' && hasUsableLadder)
                                  return null;
                                if (field.format === 'ladder' && (!ladder || ladder.length < 2))
                                  return null;
                                if (field.hideWhenEmpty && isEmptyValue(value)) return null;
                                const externalUrl =
                                  field.format === 'link' ? safeExternalUrl(value) : null;
                                const spansFullWidth =
                                  field.format === 'price-comparison' || field.format === 'ladder';
                                return (
                                  <div
                                    key={key}
                                    className={`text-xs ${spansFullWidth ? 'sm:col-span-2' : ''}`}
                                  >
                                    <div className="text-text-3 mb-0.5">
                                      {getLocalizedText(field.label || key, locale, t)}：
                                    </div>
                                    <div className="text-text-2 [overflow-wrap:anywhere]">
                                      {field.format === 'price-comparison' ? (
                                        <PriceComparison
                                          original={value}
                                          factored={readPath(candidate, field.factoredField)}
                                          factor={readPath(candidate, field.factorField)}
                                          locale={locale}
                                          t={t}
                                        />
                                      ) : field.format === 'ladder' && ladder ? (
                                        <PriceLadder
                                          rowKey={`preview-${candidateId}-${key}`}
                                          rungs={ladder}
                                          factor={readPath(candidate, field.factorField)}
                                          locale={locale}
                                          t={t}
                                        />
                                      ) : externalUrl ? (
                                        <a
                                          href={externalUrl}
                                          target="_blank"
                                          rel="noopener noreferrer"
                                          className="text-accent underline underline-offset-2"
                                          data-testid={`review-drawer-edit-preview-candidate-${candidateId}-link-${key}`}
                                          onClick={(event) => event.stopPropagation()}
                                        >
                                          {getLocalizedText(
                                            field.linkLabel || { 'zh-CN': '查看', en: 'Open' },
                                            locale,
                                            t,
                                          )}
                                        </a>
                                      ) : (
                                        formatConfiguredValue(value, field, locale, t)
                                      )}
                                    </div>
                                  </div>
                                );
                              })}
                            </div>
                            {Array.isArray(reasons) && reasons.length > 0 && (
                              <div
                                className="text-text-2 mt-2 text-xs"
                                data-testid={`review-drawer-edit-preview-candidate-${candidateId}-reasons`}
                              >
                                {reasons.map(String).join(' · ')}
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              )}
              {readPath(preview, previewConfig.messageField || 'message') && (
                <div
                  data-testid="review-drawer-edit-preview-message"
                  className="border-border text-text-2 border-t px-3 py-2 text-sm"
                >
                  {String(readPath(preview, previewConfig.messageField || 'message'))}
                </div>
              )}
            </section>
            {error && (
              <div data-testid="review-drawer-edit-error" className="text-status-red mt-2 text-xs">
                {error}
              </div>
            )}
          </div>
          <div
            data-testid="review-drawer-edit-actions"
            className="border-border bg-panel flex shrink-0 flex-wrap items-center justify-between gap-3 border-t px-4 py-2.5"
          >
            {previewConfig.candidateLayout === 'comparisonTable' && selectedPreviewCandidate ? (
              <CandidateSelectionSummary
                candidate={selectedPreviewCandidate}
                config={previewConfig.candidateTable?.selectionSummary}
                referenceRecord={preview}
                locale={locale}
                t={t}
              />
            ) : (
              <span />
            )}
            <div className="flex flex-wrap justify-end gap-2">
              <button
                type="button"
                data-testid="review-drawer-edit-cancel"
                onClick={cancel}
                className="rounded-control border-border bg-panel text-text hover:bg-hover border px-3 py-1.5 text-sm"
              >
                {t('common.cancel') || 'Cancel'}
              </button>
              <button
                type="button"
                data-testid="review-drawer-edit-back"
                onClick={() => {
                  setPreview(null);
                  setSelectedPreviewId('');
                  setError(null);
                }}
                className="rounded-control border-border bg-panel text-text hover:bg-hover border px-3 py-1.5 text-sm"
              >
                {getLocalizedText(
                  config.backLabel || { 'zh-CN': '返回修改', en: 'Edit search' },
                  locale,
                  t,
                )}
              </button>
              {confirmable && (
                <button
                  type="button"
                  data-testid="review-drawer-edit-confirm"
                  disabled={saving || !canEdit}
                  onClick={() => void confirmPreview()}
                  className="rounded-control bg-accent px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
                >
                  {saving
                    ? t('common.loading')
                    : getLocalizedText(
                        config.confirmLabel || {
                          'zh-CN': '确认并采用',
                          en: 'Confirm and adopt',
                        },
                        locale,
                        t,
                      )}
                </button>
              )}
            </div>
          </div>
        </div>
      ) : (
        <div className="flex h-full min-h-0 flex-col overflow-hidden">
          <div
            data-testid="review-drawer-edit-scroll"
            className="min-h-0 flex-1 overflow-y-auto px-4 py-3"
          >
            <div className="flex flex-wrap gap-3">
              {fields.map((f: any) => {
                const key = String(f.field);
                const label = getLocalizedText(f.label || key, locale, t);
                if (f.type === 'radio') {
                  return (
                    <fieldset
                      key={key}
                      data-testid={`review-drawer-edit-field-${key}`}
                      className="text-text-2 min-w-[220px] text-xs"
                    >
                      <legend>
                        {label}
                        {f.required && <span className="text-status-red ml-0.5">*</span>}
                      </legend>
                      <div className="mt-1 flex flex-wrap gap-3">
                        {(Array.isArray(f.options) ? f.options : []).map((option: any) => (
                          <label
                            key={String(option.value)}
                            className="text-text flex items-center gap-1.5"
                          >
                            <input
                              type="radio"
                              name={`review-drawer-edit-${key}`}
                              value={String(option.value)}
                              checked={values[key] === String(option.value)}
                              onChange={(event) => updateValue(key, event.target.value)}
                            />
                            <span>{getLocalizedText(option.label || option.value, locale, t)}</span>
                          </label>
                        ))}
                      </div>
                    </fieldset>
                  );
                }
                return (
                  <label
                    key={key}
                    data-testid={`review-drawer-edit-field-${key}`}
                    className="text-text-2 flex min-w-[160px] flex-1 flex-col gap-1 text-xs"
                  >
                    <span>
                      {label}
                      {f.required && <span className="text-status-red ml-0.5">*</span>}
                    </span>
                    <input
                      className="rounded-control border-border bg-panel text-text border px-2 py-1 text-sm"
                      value={values[key] ?? ''}
                      inputMode={f.type === 'number' ? 'numeric' : undefined}
                      placeholder={
                        f.placeholder ? getLocalizedText(f.placeholder, locale, t) : undefined
                      }
                      onChange={(e) => updateValue(key, e.target.value)}
                    />
                  </label>
                );
              })}
            </div>
            {error && (
              <div data-testid="review-drawer-edit-error" className="text-status-red mt-2 text-xs">
                {error}
              </div>
            )}
          </div>
          <div
            data-testid="review-drawer-edit-actions"
            className="border-border bg-panel flex shrink-0 justify-end gap-2 border-t px-4 py-2.5"
          >
            <button
              type="button"
              data-testid="review-drawer-edit-cancel"
              onClick={cancel}
              className="rounded-control border-border bg-panel text-text hover:bg-hover border px-3 py-1.5 text-sm"
            >
              {t('common.cancel') || 'Cancel'}
            </button>
            <button
              type="button"
              data-testid="review-drawer-edit-submit"
              disabled={saving || !canEdit}
              onClick={() => void submit()}
              className="rounded-control bg-accent px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
            >
              {saving
                ? t('common.loading')
                : getLocalizedText(
                    config.submitLabel || { 'zh-CN': '保存并重新查价', en: 'Save and re-price' },
                    locale,
                    t,
                  )}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
