/**
 * EmbeddedListBlockRenderer — `embedded-list` block dispatcher.
 *
 * Renders a {@link RecordListView} scoped to the surrounding record. It resolves
 * the parent record id from explicit props (the detail-page path, which passes
 * `parentRecordId`/`parentRecordData` exactly like sub-table) and falls back to
 * the runtime expression context (`$page.recordId` / `record.pid`) for generic
 * composite pages dispatched through BlockRenderer.
 *
 * Block config (flat table contract):
 *   { blockType: "embedded-list", modelCode | childModel, parentField | foreignKey,
 *     columns: [...], title?, pageSize?, searchable?, filterable? }
 */

import React from 'react';
import type { BlockConfig, ColumnConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { RecordListView } from '~/framework/meta/rendering/blocks/RecordListView';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useI18n } from '~/contexts/I18nContext';

export interface EmbeddedListBlockRendererProps {
  block: BlockConfig;
  runtime?: SchemaRuntime;
  /** Detail-page path: the surrounding record id (e.g. the conversion task pid). */
  parentRecordId?: string;
  /** Detail-page path: the surrounding record data. */
  parentRecordData?: Record<string, any>;
  /** Optional explicit token; defaults to the session cookie. */
  token?: string;
}

export function EmbeddedListBlockRenderer({
  block,
  runtime,
  parentRecordId,
  parentRecordData,
  token,
}: EmbeddedListBlockRendererProps) {
  const { t, locale } = useI18n();
  const cfg = block as any;

  const modelCode: string | undefined = cfg.modelCode || cfg.childModel;
  const columns: ColumnConfig[] = cfg.columns || cfg.table?.columns || [];
  const parentField: string | undefined = cfg.parentField || cfg.foreignKey;

  // Prefer the explicitly-injected parent id (reliable, route-derived); fall
  // back to the runtime context for generic composite pages.
  const ctx = runtime?.getContext?.() as any;
  const recordId =
    parentRecordId ?? ctx?.$page?.recordId ?? parentRecordData?.pid ?? ctx?.record?.pid ?? ctx?.record?.id;

  const fixedFilters =
    parentField && recordId != null ? { [parentField]: String(recordId) } : undefined;

  if (!modelCode) {
    return (
      <div className="rounded border border-yellow-300 bg-yellow-50 p-4">
        <p className="text-yellow-800">
          embedded-list block "{block.id}" is missing modelCode/childModel
        </p>
      </div>
    );
  }

  const title = cfg.title ? getLocalizedText(cfg.title, locale, t) : null;

  return (
    <div className="embedded-list-section" data-testid={`embedded-list-${block.id}`}>
      {title && (
        <h3 className="mb-3 text-sm font-semibold tracking-wider text-gray-500 uppercase">{title}</h3>
      )}
      <RecordListView
        modelCode={modelCode}
        columns={columns}
        fixedFilters={fixedFilters}
        token={token ?? undefined}
        runtime={runtime}
        pageSize={cfg.pageSize ?? cfg.table?.pagination?.pageSize ?? 10}
        searchable={cfg.searchable !== false}
        filterable={cfg.filterable !== false}
        locale={locale}
        testIdPrefix={`embedded-list-${block.id}`}
      />
    </div>
  );
}

export default EmbeddedListBlockRenderer;
