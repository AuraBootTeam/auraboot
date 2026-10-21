import { WorkspaceRecordSection } from '../detail/WorkspaceRecordSection';
import { useCallback } from 'react';
import { BlockRenderer } from '@auraboot/runtime-kernel';
import { getLocalizedText, DynamicField } from '~/routes/_shared/dynamic-route-utils';
import { ContributedSubTableViewer as SubTableViewer } from '~/framework/extensions/contributed-components';
import { EmbeddedListBlockRenderer } from '~/framework/meta/rendering/blocks/EmbeddedListBlockRenderer';
import { MonthlyGridViewer } from '~/framework/meta/rendering/blocks/MonthlyGridViewer';
import { FieldHistoryViewer } from '~/framework/meta/rendering/blocks/FieldHistoryViewer';
import { ActivityTimeline } from '~/framework/meta/rendering/blocks/ActivityTimeline';
import { RecordComments } from '~/framework/meta/rendering/blocks/RecordComments';
import { LazyContributedComponent } from '~/framework/extensions/contributed-components';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { BlockConfig, DataSourceConfig, FieldConfig } from '~/framework/meta/schemas/types';
import { useTimezone } from '~/contexts/TimezoneContext';
import { useAuth } from '~/contexts/AuthContext';
import type { RecordData } from './detailPageTypes';
import {
  resolveDetailSectionIcon,
  getDetailValueTone,
  resolveSettingsCardField,
  resolveSettingsCardDisplayValue,
} from './detailFieldDisplay';
import { getByDataPath, readDetailRecordField } from './detailRecordRequests';
import { extractBlockDataRows, prepareDetailRuntimeBlock } from './detailBlockValues';
import { resolveSubTableDataSourceConfig } from './detailLayoutValues';
import { DataPathTable } from './DataPathTable';

/**
 * DetailBlockRenderer - renders a single block within the detail page
 */
export function DetailBlockRenderer({
  block,
  recordData,
  rawData,
  recordPid,
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
  rawData?: any;
  recordPid: string;
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
  dataSourceManager?: {
    getConfig: (id: string) => DataSourceConfig | undefined;
  };
}) {
  const { timezone, formats } = useTimezone();
  const { hasPermission: canReadBlock } = useAuth();
  const resolveModelFieldLabel = useCallback(
    (fieldCode: string): string => {
      if (modelCode) {
        const modelKey = `model.${modelCode}.${fieldCode}.label`;
        const modelLabel = t(modelKey);
        if (modelLabel && modelLabel !== modelKey) return modelLabel;
        const modelFieldKey = `field.${modelCode}.${fieldCode}.label`;
        const modelFieldLabel = t(modelFieldKey);
        if (modelFieldLabel && modelFieldLabel !== modelFieldKey) return modelFieldLabel;
      }
      const fieldKey = `field.${fieldCode}.label`;
      const fieldLabel = t(fieldKey);
      if (fieldLabel && fieldLabel !== fieldKey) return fieldLabel;
      return fieldCode;
    },
    [modelCode, t],
  );

  if (block.permissionCode && !canReadBlock(block.permissionCode)) return null;
  if (block.visibleWhen && evaluateEditableWhen && !evaluateEditableWhen(block.visibleWhen)) {
    return null;
  }

  if (block.blockType === 'form-section' || block.blockType === 'detail-section') {
    // dataPath binds this section to a nested object of the raw payload (e.g. a license's
    // latestHeartbeat); without it the section uses the unwrapped master record.
    const sectionDataPath = (block as any).dataPath as string | undefined;
    const sectionRecord = sectionDataPath
      ? getByDataPath(rawData, sectionDataPath) || {}
      : recordData;
    const displayVariant = String((block as any).extension?.displayVariant || '');
    if (displayVariant === 'object') {
      return (
        <WorkspaceRecordSection
          block={block}
          fields={(block.fields || [])
            .filter(
              (field) =>
                !field.visibleWhen ||
                !evaluateEditableWhen ||
                evaluateEditableWhen(field.visibleWhen),
            )
            .map((field) => {
              const labeled = field.label
                ? field
                : { ...field, label: resolveModelFieldLabel(field.field) };
              return enrichField ? enrichField(labeled) : labeled;
            })}
          record={sectionRecord}
          token={token}
          locale={locale}
          timezone={timezone}
          t={t}
          getDictItems={getDictItems}
        />
      );
    }
    if (block.blockType === 'detail-section' && displayVariant === 'settings-card') {
      const Icon = resolveDetailSectionIcon(block);
      const title = block.title ? getLocalizedText(block.title, locale, t) : '';
      const description = (block as any).description
        ? getLocalizedText((block as any).description, locale, t)
        : '';
      return (
        <section className="border-border bg-panel mb-4 overflow-hidden rounded-lg border shadow-sm">
          <div className="border-border bg-subtle flex items-start gap-3 border-b px-5 py-4">
            <div className="bg-status-blue-bg text-status-blue ring-status-blue/20 flex h-9 w-9 shrink-0 items-center justify-center rounded-md ring-1">
              <Icon className="h-4 w-4" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <h3 className="text-text text-base font-semibold">{title}</h3>
              {description ? <p className="text-text-2 mt-1 text-sm">{description}</p> : null}
            </div>
          </div>
          {block.fields && block.fields.length > 0 && (
            <div className="grid grid-cols-1 gap-3 p-4 sm:p-5 md:grid-cols-2">
              {block.fields.map((field: FieldConfig) => {
                const colSpan =
                  field.layout?.colSpan || field.colSpan || (field.span === 2 ? 12 : 6);
                const isFullWidth = colSpan >= 12 || field.span === 2;
                const resolvedField = resolveSettingsCardField(
                  field,
                  resolveModelFieldLabel,
                  enrichField,
                );
                const label = resolvedField.label
                  ? getLocalizedText(resolvedField.label as any, locale, t)
                  : field.field;
                const rawValue = sectionRecord
                  ? readDetailRecordField(sectionRecord, field.field)
                  : undefined;
                const fieldDisplayValue = sectionRecord
                  ? readDetailRecordField(sectionRecord, `${field.field}_display`)
                  : undefined;
                const valueText = resolveSettingsCardDisplayValue(
                  rawValue,
                  fieldDisplayValue,
                  resolvedField,
                  getDictItems,
                  timezone,
                  formats,
                );
                const isLongText = valueText.includes('\n') || valueText.length > 56;
                return (
                  <div
                    key={field.field}
                    data-testid={`form-field-${field.field}`}
                    className={`${isFullWidth ? 'md:col-span-2' : ''} border-border bg-panel ring-border min-w-0 rounded-md border px-4 py-3 ring-1`}
                  >
                    <div className="text-text-2 text-xs font-medium">{label}</div>
                    {isLongText ? (
                      <p className="text-text mt-2 text-sm leading-6 break-all whitespace-pre-line">
                        {valueText}
                      </p>
                    ) : (
                      <span
                        className={`mt-2 inline-flex w-fit max-w-full items-center rounded-md border px-2.5 py-1 text-sm font-medium break-all ${getDetailValueTone(valueText)}`}
                      >
                        {valueText}
                      </span>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </section>
      );
    }
    return (
      <section className="form-section border-border bg-panel rounded-card border p-5 shadow-sm">
        {block.title && (
          <h3 className="text-text mb-4 text-sm font-semibold">
            {getLocalizedText(block.title, locale, t)}
          </h3>
        )}
        {block.fields && block.fields.length > 0 && (
          <div className="grid grid-cols-1 gap-x-8 gap-y-4 md:grid-cols-2">
            {block.fields.map((field: FieldConfig) => {
              const colSpan = field.layout?.colSpan || field.colSpan || (field.span === 2 ? 12 : 6);
              const isFullWidth = colSpan >= 12 || field.span === 2;
              const resolvedField: FieldConfig = field.label
                ? field
                : { ...field, label: resolveModelFieldLabel(field.field) };
              const enrichedField = enrichField ? enrichField(resolvedField) : resolvedField;

              return (
                <div
                  key={field.field}
                  data-testid={`form-field-${field.field}`}
                  className={`${isFullWidth ? 'md:col-span-2' : ''} min-w-0`}
                >
                  <DynamicField
                    field={enrichedField}
                    value={
                      sectionRecord ? readDetailRecordField(sectionRecord, field.field) : undefined
                    }
                    displayValue={
                      sectionRecord
                        ? readDetailRecordField(sectionRecord, `${field.field}_display`)
                        : undefined
                    }
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
      </section>
    );
  }

  if (block.blockType === 'sub-table' && (block as any).dataPath) {
    // dataPath sub-table: render rows directly from a nested array of the raw payload
    // (e.g. a plan version's priceComponents / quotaTemplates returned by the aggregate
    // detail endpoint). No separate fetch; read-only.
    const rows = extractBlockDataRows(rawData, (block as any).dataPath);
    const columns = ((block as any).columns || []) as any[];
    return (
      <DataPathTable
        title={block.title}
        columns={columns}
        rows={rows}
        locale={locale}
        t={t}
        getDictItems={getDictItems}
      />
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
            toolbarActions: (block as any).toolbarActions,
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
        <div
          className={
            (subTableConfig as any).presentation?.kind === 'collection'
              ? 'sub-table-section border-border bg-panel rounded-card border px-6 py-5'
              : 'sub-table-section'
          }
        >
          {block.title && (subTableConfig as any).presentation?.kind !== 'collection' && (
            <h3 className="text-text-2 mb-5 text-sm font-semibold tracking-wider uppercase">
              {getLocalizedText(block.title, locale, t)}
            </h3>
          )}
          <SubTableViewer
            config={
              (subTableConfig as any).presentation?.kind === 'collection'
                ? {
                    ...subTableConfig,
                    presentation: {
                      ...(subTableConfig as any).presentation,
                      title: block.title,
                    },
                  }
                : subTableConfig
            }
            parentRecordPid={recordPid}
            parentRecordData={recordData}
            token={token}
            locale={locale}
            t={t}
            isEditable={isEditable}
            onDataChange={onDataChange}
            runtime={runtime}
          />
        </div>
      );
    }
  }

  if (block.blockType === 'embedded-list') {
    return (
      <EmbeddedListBlockRenderer
        block={block}
        runtime={runtime}
        parentRecordPid={recordPid}
        parentRecordData={recordData}
        token={token}
      />
    );
  }

  if (block.blockType === 'activity-timeline') {
    // Activity API uses record PID, not numeric ID
    const pid = recordData?.pid || recordPid;
    return (
      <section>
        {block.title && (
          <h3 className="text-text mb-4 text-sm font-semibold">
            {getLocalizedText(block.title, locale, t)}
          </h3>
        )}
        <ActivityTimeline
          modelCode={modelCode || ''}
          recordPid={String(pid)}
          token={token}
          locale={locale}
          t={t}
          businessDataSource={
            (block as any).businessDataSource ??
            (block as any).dataSource ??
            (block as any).subTable?.dataSource
          }
          compact={(block as any).extension?.density === 'compact'}
          previewLimit={(block as any).extension?.previewLimit}
          businessRecordMapping={(block as any).businessRecordMapping}
        />
      </section>
    );
  }

  if (block.blockType === 'record-comments') {
    const pid = recordData?.pid || recordPid;
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
    // Field audit queries are scoped to the public record PID.
    const auditRecordPid = recordData?.pid || recordPid;
    return (
      <section>
        {block.title && (
          <h3 className="text-text mb-4 text-sm font-semibold">
            {getLocalizedText(block.title, locale, t)}
          </h3>
        )}
        <FieldHistoryViewer
          modelCode={modelCode || ''}
          recordPid={String(auditRecordPid)}
          token={token}
          locale={locale}
          t={t}
          compact={(block as any).extension?.density === 'compact'}
          fields={(block as any).extension?.fields}
          units={(block as any).extension?.units}
        />
      </section>
    );
  }

  if (block.blockType === 'workflow-panel') {
    return (
      <LazyContributedComponent
        contributionId="workflow-panel"
        block={block as any}
        record={recordData}
        recordPid={recordPid}
        onDataChange={onDataChange}
      />
    );
  }

  if (block.blockType === 'monthly-grid' && block.monthlyGrid) {
    return (
      <div className="monthly-grid-section">
        {block.title && (
          <h3 className="text-text-2 mb-5 text-sm font-semibold tracking-wider uppercase">
            {getLocalizedText(block.title, locale, t)}
          </h3>
        )}
        <MonthlyGridViewer
          config={block.monthlyGrid}
          parentRecordPid={recordPid}
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
    // Chart blocks fed by a record-scoped namedQuery need their ${record.*}/${recordPid}
    // params resolved here (the chart's runtime context does not carry the detail record).
    const dispatchBlock = prepareDetailRuntimeBlock(block, recordData, recordPid);
    return <BlockRenderer block={dispatchBlock} runtime={runtime} areaId="detail-tab" />;
  }

  // No runtime available — log and surface the miss so it's diagnosable.
  // eslint-disable-next-line no-console
  console.warn(
    `[DetailBlockRenderer] No runtime context; cannot dispatch blockType="${block.blockType}"`,
  );
  return (
    <div
      className="bg-status-amber-bg border-status-amber rounded border p-4"
      data-testid="detail-block-unknown"
    >
      <p className="text-status-amber">
        Unknown block type on detail page: <code>{String(block.blockType)}</code>
      </p>
    </div>
  );
}
