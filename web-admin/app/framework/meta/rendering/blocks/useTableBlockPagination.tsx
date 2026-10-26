import React, { useEffect, useRef } from 'react';
import type { PaginationConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { DataSourceState } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { Pagination } from '~/ui/Pagination';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { readPath, writeRuntimeState } from './workbenchBlockUtils';

/** Keep pagination state in the DSL context so reloads retain the current page. */
export function useTableBlockPagination(
  runtime: SchemaRuntime,
  config: PaginationConfig | undefined,
  source: string | undefined,
  state: DataSourceState | undefined,
): React.ReactNode {
  const context = runtime.getContext();
  const binding = config?.server;
  const signature = JSON.stringify((binding?.resetOn ?? []).map((path) => readPath(context, path)));
  const previous = useRef(signature);
  useEffect(() => {
    if (previous.current !== signature) {
      previous.current = signature;
      if (binding && Number(context.state?.[binding.pageState] ?? 1) !== 1) {
        writeRuntimeState(runtime, binding.pageState, 1);
      }
    }
  }, [runtime, binding, signature, context.state]);

  if (!binding || !source || state?.data == null) return null;
  const t = context.t || ((key: string) => key);
  const text = (zh: string, en: string) =>
    getLocalizedText({ 'zh-CN': zh, en }, context.locale || 'zh-CN', t);
  const page = state.data;
  const validBinding =
    /^[A-Za-z_$][\w$]*$/.test(binding.pageState) &&
    /^[A-Za-z_$][\w$]*$/.test(binding.pageSizeState) &&
    binding.pageState !== binding.pageSizeState;
  if (
    !validBinding ||
    !Array.isArray(page.records) ||
    !Number.isSafeInteger(page.total) ||
    page.total < 0 ||
    !Number.isSafeInteger(page.current) ||
    page.current < 1 ||
    !Number.isSafeInteger(page.pageSize) ||
    page.pageSize < 1 ||
    page.records.length > page.pageSize ||
    page.records.length > page.total
  ) {
    return (
      <div role="alert" data-testid="table-pagination-error" className="text-danger px-6 py-3">
        {text(
          '分页数据无效，请重新加载；如仍失败请联系管理员。',
          'Invalid pagination data. Reload and contact the administrator if it persists.',
        )}
      </div>
    );
  }
  return (
    <fieldset
      disabled={Boolean(state.loading || state.error)}
      aria-label={text('列表分页', 'List pagination')}
      data-testid="table-pagination"
      className="min-w-0"
    >
      <Pagination
        current={page.current}
        pageSize={page.pageSize}
        total={page.total}
        alwaysVisible
        compact
        pageSizeOptions={config?.pageSizeOptions}
        showPageSizeSelector={config?.showSizeChanger !== false}
        showQuickJumper={config?.showQuickJumper}
        t={t}
        onChange={(current) => writeRuntimeState(runtime, binding.pageState, current)}
        onPageSizeChange={(size) => {
          runtime.getStateManager().updateState(runtime.getScopeId(), binding.pageState, 1);
          writeRuntimeState(runtime, binding.pageSizeState, size);
        }}
      />
    </fieldset>
  );
}
