import type { DslBlockV3 } from '../../types';
import { DESIGNER_I18N } from '~/shared/designer';
import React from 'react';
import { RuntimeTitle } from './RuntimePrimitives';
import { RuntimeLocaleContext, useRuntimeText } from './runtimeContexts';
import {
  getBlockLabel,
  getBooleanProp,
  getRawArray,
  getRawRecord,
  getSpanGridStyle,
  getStringProp,
  isRecord,
  readLocalizedLabel,
} from './runtimePresentation';





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

export function RuntimeStatCardPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeDescriptionPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeRecordCommentsPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeEmbeddedListPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeChartPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeRichTextPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeDividerPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeToolbarPreview({
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

export function RuntimeFiltersPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeFormWizardPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeTraceGraphPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeSelectionInfoPreview({ block }: { block: DslBlockV3 }) {
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

export function RuntimeGerberViewerPreview({ block }: { block: DslBlockV3 }) {
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
