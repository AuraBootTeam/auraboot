/**
 * FiltersBlockRenderer - 过滤器块渲染器
 */

import React from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { TimeWindowFilter } from './TimeWindowFilter';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { FieldRenderer } from '~/framework/meta/rendering/FieldRenderer';
import { useI18n } from '~/contexts/I18nContext';
import {
  executeSimpleWorkbenchAction,
  writeRuntimeState,
  useRuntimeStateSubscription,
} from './workbenchBlockUtils';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';

export interface FiltersBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const FiltersBlockRenderer: React.FC<FiltersBlockRendererProps> = ({ block, runtime }) => {
  const { t } = useI18n();
  useRuntimeStateSubscription(runtime);
  const windowConfig = (block as any).props?.timeWindow;
  if (windowConfig) return <TimeWindowFilter config={windowConfig} runtime={runtime} />;
  const fields = block.fields || [];
  const density = (block as any).density || 'default';
  const compact = density === 'compact';
  // URL filter sync (P1-01 URL筛选): with `urlSync: true`, the declared filter
  // fields are restored from the URL query on mount and written back to the URL
  // (replace, no history spam) on search/reset, so filtered views are deep-linkable.
  const urlSyncEnabled = (block as any).urlSync === true;
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const { hash } = useLocation();
  const visibleFields = fields.filter(
    (field) =>
      runtime.getStateManager().getFieldMeta(runtime.getScopeId(), field.field)?.hidden !== true &&
      (!field.visibleWhen || evaluateCondition(field.visibleWhen, runtime.getContext())),
  );

  const executeFilterHandler = async (handler: unknown) => {
    if (!handler) return;
    if (typeof handler === 'string') {
      await runtime.executeHandler(handler, {});
      return;
    }
    await executeSimpleWorkbenchAction(runtime, handler);
  };

  // 处理搜索
  const handleSearch = async () => {
    if (block.onSearch) {
      try {
        await executeFilterHandler(block.onSearch);
        if (urlSyncEnabled) {
          const form = ((runtime.getContext() as any).form ?? {}) as Record<string, any>;
          writeUrl(form);
        }
      } catch (err) {
        console.error('Search handler failed:', err);
      }
    }
  };

  // 处理重置
  const handleReset = async () => {
    if (block.resetFieldsOnReset === true) {
      for (const field of fields) {
        const value = null;
        runtime.getStateManager().updateField(runtime.getScopeId(), field.field, value);
        const bound = (field as { bindState?: string }).bindState;
        if (bound) writeRuntimeState(runtime, bound, value);
      }
    }
    if (block.onReset) {
      try {
        await executeFilterHandler(block.onReset);
        if (urlSyncEnabled) writeUrl({});
      } catch (err) {
        console.error('Reset handler failed:', err);
      }
    }
  };

  const writeUrl = (values: Record<string, any>) => {
    if (!urlSyncEnabled) return;
    const next = new URLSearchParams();
    for (const field of fields) {
      const value = values[field.field];
      if (value !== undefined && value !== null && String(value).trim() !== '') {
        next.set(field.field, String(value));
      }
    }
    setSearchParams(next, { replace: true });
    void navigate;
    void hash;
  };

  // On mount: seed declared fields from the URL query once and restore the
  // filtered view through the declared search handler.
  const seededRef = React.useRef(false);
  React.useEffect(() => {
    if (!urlSyncEnabled || seededRef.current) return;
    seededRef.current = true;
    const seeded: Record<string, any> = {};
    for (const field of fields) {
      const value = searchParams.get(field.field);
      if (value !== null && value.trim() !== '') {
        runtime.getStateManager().updateField(runtime.getScopeId(), field.field, value);
        seeded[field.field] = value;
      }
    }
    if (Object.keys(seeded).length > 0 && block.onSearch) {
      void executeFilterHandler(block.onSearch).catch((err) => {
        console.error('URL filter restore failed:', err);
      });
    }
    // Run once per mount; fields/searchParams are stable for the seeded restore.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      className={
        compact
          ? 'filters-block rounded-control border-border bg-panel border px-4 py-1.5'
          : 'filters-block border-border bg-subtle border-b px-6 py-4'
      }
      data-density={density}
    >
      <div className={compact ? 'flex flex-col gap-3 lg:flex-row lg:items-end' : undefined}>
        <div
          className={
            compact
              ? 'grid min-w-0 flex-1 grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4'
              : 'grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4'
          }
        >
          {visibleFields.map((field) => (
            <div key={field.field} data-authoring-node-id={(field as any).id || field.field}>
              <FieldRenderer field={field} runtime={runtime} />
            </div>
          ))}
        </div>
        <div
          className={
            compact ? 'flex shrink-0 justify-end gap-2' : 'mt-4 flex justify-end space-x-2'
          }
        >
          <button
            onClick={handleReset}
            data-testid="filter-btn-reset"
            className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-subtle border px-4 py-2"
          >
            {t('action.reset') !== 'action.reset' ? t('action.reset') : 'Reset'}
          </button>
          <button
            onClick={handleSearch}
            data-testid="filter-btn-search"
            className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-white"
          >
            {t('action.search') !== 'action.search' ? t('action.search') : 'Search'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default FiltersBlockRenderer;
