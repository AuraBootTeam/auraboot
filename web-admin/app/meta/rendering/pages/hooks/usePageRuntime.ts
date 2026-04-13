/**
 * usePageRuntime Hook
 *
 * Creates a runtime environment from an already-loaded schema.
 * Used by PageContent components that receive schema from DynamicPageRenderer
 * (which handles schema loading separately).
 *
 * This is the "post-schema-load" part of useDynamicPageSetup:
 * - Expression context creation
 * - DataSourceManager initialization
 * - SchemaRuntime initialization
 * - i18n and navigation utilities
 */

import { useMemo } from 'react';
import { useNavigate } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { useDataSync } from '~/meta/hooks/useDataSync';
import { usePageDataSources } from '~/meta/hooks/usePageDataSources';
import { useSchemaRuntime } from '~/meta/hooks/useSchemaRuntime';
import { createExpressionContext } from '~/meta/runtime/expression/context';
import { fetchResult } from '~/services/http-client';
import type { SchemaRuntime } from '~/meta/runtime/schema-runtime';
import type { DataSourceManager } from '~/meta/runtime/data-pipeline/DataSourceManager';
import type { UnifiedSchema } from '~/meta/schemas/types';

export interface UsePageRuntimeOptions {
  /** Auth token */
  token?: string;
  /** Additional expression context (e.g., filters, form data) */
  additionalContext?: Record<string, any>;
  /** Disable SchemaRuntime (some pages may not need it) */
  disableRuntime?: boolean;
}

export interface UsePageRuntimeResult {
  runtime: SchemaRuntime | null;
  dataSourceManager: DataSourceManager;
  t: (key: string) => string;
  locale: string;
  navigate: ReturnType<typeof useNavigate>;
}

/**
 * Create runtime environment from an already-loaded schema.
 *
 * @param schema - The loaded UnifiedSchema (null is handled gracefully)
 * @param options - Optional runtime configuration
 */
export function usePageRuntime(
  schema: UnifiedSchema | null,
  options?: UsePageRuntimeOptions,
): UsePageRuntimeResult {
  const { additionalContext = {}, disableRuntime = false } = options || {};

  const navigate = useNavigate();
  const { t, locale } = useI18n();

  // Build $page metadata from schema
  const pageMetadata = useMemo(() => ({
    kind: (schema as any)?.kind,
    modelCode: (schema as any)?.modelCode,
    pageKey: (schema as any)?.pageKey,
  }), [schema]);

  // Build expression context
  const expressionContext = useMemo(() => {
    return createExpressionContext({
      locale,
      global: {
        locale,
        theme: 'light',
        user: undefined,
        tenant: undefined,
        t,
      },
      t,
      fetchResult,
      $page: pageMetadata,
      ...additionalContext,
    });
  }, [locale, t, pageMetadata, additionalContext]);

  // Initialize DataSourceManager
  const { manager: dataSourceManager } = usePageDataSources({
    context: expressionContext,
    schema,
  });

  // Initialize SchemaRuntime (conditional)
  const runtime = useSchemaRuntime(
    disableRuntime || !schema || !dataSourceManager
      ? { schema: null, dataSourceManager: dataSourceManager as any, navigate, locale, t }
      : {
          schema,
          dataSourceManager,
          navigate,
          locale,
          t,
          disableAutoFetch: true,
        },
  );

  // Real-time data sync: subscribe to model changes via SSE
  const modelCodes = useMemo(() => {
    const codes = new Set<string>();
    if (schema?.modelCode) codes.add(schema.modelCode);
    // Extract from sub-table blocks
    if (schema?.blocks) {
      for (const block of schema.blocks) {
        if ((block as any).childModel) codes.add((block as any).childModel);
        if ((block as any).modelCode) codes.add((block as any).modelCode);
      }
    }
    return codes;
  }, [schema]);

  useDataSync(dataSourceManager, modelCodes);

  return {
    runtime: disableRuntime ? null : runtime,
    dataSourceManager,
    t,
    locale,
    navigate,
  };
}
