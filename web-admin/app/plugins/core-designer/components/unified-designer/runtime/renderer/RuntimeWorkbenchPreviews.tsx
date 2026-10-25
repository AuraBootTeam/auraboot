import type { DslBlockV3 } from '../../types';
import { DESIGNER_I18N } from '~/shared/designer';
import React from 'react';
import { RuntimeTitle } from './RuntimePrimitives';
import { RuntimeLocaleContext, useRuntimeText } from './runtimeContexts';
import {
  getMetricStripMetrics,
  getRawArray,
  getRawRecord,
  getSpanGridStyle,
  getStringProp,
  isRecord,
  readLocalizedLabel,
  WORKBENCH_PREVIEW_BUTTON_CLASS,
  WORKBENCH_TONE_CARD_CLASS,
  WORKBENCH_TONE_DOT_CLASS,
} from './runtimePresentation';





export function RuntimeMetricStripPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeStatusBannerPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeWorkbenchActionBarPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeEvidencePanelPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeCandidateListPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeArtifactTimelinePreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeReviewDrawerPreview({ block }: { block: DslBlockV3 }) {
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
