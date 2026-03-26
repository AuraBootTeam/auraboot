/**
 * useSchemaLoader Hook
 * 用于加载和管理 Schema
 *
 * 使用统一的 API 端点：/api/pages/key/{pageKey}
 * - Model 相关页面：pageKey 格式为 "{modelCode}_{pageType}"，如 "device_list"
 * - Model 无关页面：pageKey 为自定义标识，如 "dashboard_main"
 */

import { useState, useEffect, useRef } from 'react';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { UnifiedSchema } from '~/meta/schemas/types';
import {
  mapApiPageTypeToSchemaKind,
  mapRuntimePageTypeToSchemaType,
} from '~/meta/utils/page-semantics';

/**
 * In-memory schema cache with LRU eviction.
 * Avoids redundant API calls when navigating between pages and back.
 * Cache is per-session only (cleared on page refresh).
 */
const SCHEMA_CACHE_MAX = 50;
const schemaCache = new Map<string, { schema: UnifiedSchema; timestamp: number }>();

function getCachedSchema(pageKey: string): UnifiedSchema | null {
  const entry = schemaCache.get(pageKey);
  if (!entry) return null;
  // Expire after 5 minutes to pick up schema changes during dev
  if (Date.now() - entry.timestamp > 5 * 60 * 1000) {
    schemaCache.delete(pageKey);
    return null;
  }
  // Move to end (LRU)
  schemaCache.delete(pageKey);
  schemaCache.set(pageKey, entry);
  return entry.schema;
}

function setCachedSchema(pageKey: string, schema: UnifiedSchema): void {
  // Evict oldest if at capacity
  if (schemaCache.size >= SCHEMA_CACHE_MAX) {
    const oldest = schemaCache.keys().next().value;
    if (oldest) schemaCache.delete(oldest);
  }
  schemaCache.set(pageKey, { schema, timestamp: Date.now() });
}

export interface UseSchemaLoaderOptions {
  /**
   * 页面唯一标识
   * 可以直接传入 pageKey（如 "device_list", "dashboard_main"）
   * 或者传入 tableName，由 hook 自动拼接为 "{tableName}_{type}"
   */
  tableName?: string;
  pageKey?: string;
  type?: 'list' | 'new' | 'detail' | 'page';
  token?: string;
}

export interface UseSchemaLoaderResult {
  schema: UnifiedSchema | null;
  loading: boolean;
  error: Error | null;
  reload: () => Promise<void>;
}

/**
 * Map frontend page type to backend schema type for pageKey generation
 */
/**
 * PageSchemaDTO response structure
 */
interface PageSchemaDTO {
  pid: string;
  pageKey: string;
  modelCode: string | null;
  modelCategory: string | null;
  pageCategory: string;
  name: string;
  title: string;
  description: string;
  pageType: string;
  commandCode?: string;
  dslSchema: UnifiedSchema;
  schemaVersion: number | null;
  metaInfo: Record<string, unknown>;
  isTemplate: boolean;
}

/**
 * Schema Loader Hook
 *
 * 使用方式：
 * 1. 直接传入 pageKey：useSchemaLoader({ pageKey: 'dashboard_main' })
 * 2. 传入 tableName + type：useSchemaLoader({ tableName: 'device', type: 'list' })
 *    将自动生成 pageKey = 'device_list'
 */
export function useSchemaLoader(options: UseSchemaLoaderOptions): UseSchemaLoaderResult {
  const [schema, setSchema] = useState<UnifiedSchema | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  // Compute pageKey from options
  // NOTE: URL convention uses hyphens (e.g. /dynamic/e2et-record) but
  // page_key in DB uses underscores (e.g. e2et_record_list).
  // We normalize hyphens to underscores when building the pageKey.
  const computePageKey = (): string => {
    if (options.pageKey) {
      return options.pageKey;
    }
    if (options.tableName && options.type) {
      const schemaType = mapRuntimePageTypeToSchemaType(options.type);
      const normalizedTableName = options.tableName.replace(/-/g, '_');
      return `${normalizedTableName}_${schemaType}`;
    }
    throw new Error('Either pageKey or (tableName + type) must be provided');
  };

  const loadSchema = async () => {
    try {
      setError(null);

      const pageKey = computePageKey();

      // Check in-memory cache first
      const cached = getCachedSchema(pageKey);
      if (cached) {
        setSchema(cached);
        setLoading(false);
        return;
      }

      setLoading(true);

      const endpoint = `/api/pages/key/${pageKey}`;
      let result = await fetchResult<PageSchemaDTO>(endpoint, {
        method: 'get',
        token: options.token,
      });

      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.message || 'Failed to load schema');
      }

      // Extract dslSchema from PageSchemaDTO
      const pageSchemaDTO = result.data;
      if (!pageSchemaDTO || !pageSchemaDTO.dslSchema) {
        throw new Error(`No schema found for pageKey: ${pageKey}`);
      }

      // Merge page-level metadata into dslSchema
      const merged: any = { ...pageSchemaDTO.dslSchema };
      if (!merged.title && pageSchemaDTO.title) {
        merged.title = pageSchemaDTO.title;
      }
      if (!merged.pageKey && pageSchemaDTO.pageKey) {
        merged.pageKey = pageSchemaDTO.pageKey;
      }
      if (!merged.pageCategory && pageSchemaDTO.pageCategory) {
        merged.pageCategory = pageSchemaDTO.pageCategory;
      }
      if (!merged.commandCode && pageSchemaDTO.commandCode) {
        merged.commandCode = pageSchemaDTO.commandCode;
      }
      // Runtime relies on schema.kind for renderer selection. Older DSL pages
      // may omit kind but still provide pageType in PageSchemaDTO.
      if (!merged.kind) {
        const inferredKind = mapApiPageTypeToSchemaKind(pageSchemaDTO.pageType);
        if (inferredKind) {
          merged.kind = inferredKind;
        }
      }
      // Inject schemaVersion from DB column into the runtime schema
      if (pageSchemaDTO.schemaVersion != null && merged.schemaVersion == null) {
        merged.schemaVersion = pageSchemaDTO.schemaVersion;
      }
      // Inject modelCategory for conditional rendering (e.g., Activity tab)
      if (!merged.modelCategory && pageSchemaDTO.modelCategory) {
        merged.modelCategory = pageSchemaDTO.modelCategory;
      }

      // Dev-mode DSL validation
      if (process.env.NODE_ENV === 'development') {
        import('~/meta/validation/DslValidator')
          .then(({ validateStructure }) => {
            const messages = validateStructure(merged);
            if (messages.length > 0) {
              console.warn(`[useSchemaLoader] DSL validation warnings for "${pageKey}":`, messages);
            }
          })
          .catch(() => {
            // Validation module not available — skip silently
          });
      }

      // Store in cache
      setCachedSchema(pageKey, merged);
      setSchema(merged);
    } catch (err) {
      const error = err instanceof Error ? err : new Error('Unknown error');
      setError(error);
      console.error('Failed to load schema:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadSchema();
  }, [options.pageKey, options.tableName, options.type]);

  return {
    schema,
    loading,
    error,
    reload: loadSchema,
  };
}
