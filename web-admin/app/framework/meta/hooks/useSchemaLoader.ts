/**
 * useSchemaLoader Hook
 * 用于加载和管理 Schema
 *
 * 使用统一的 API 端点：/api/pages/key/{pageKey}
 * - Model 相关页面：pageKey 格式为 "{modelCode}_{kind}"，如 "device_list"
 * - Model 无关页面：pageKey 为自定义标识，如 "dashboard_main"
 */

import { useState, useEffect, useRef } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import { DslMigrator } from '~/framework/meta/migration';

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
  type?: 'list' | 'form' | 'detail' | 'dashboard' | 'kanban' | 'composite';
  token?: string;
}

export interface UseSchemaLoaderResult {
  schema: UnifiedSchema | null;
  loading: boolean;
  error: Error | null;
  reload: () => Promise<void>;
}

/**
 * PageSchemaDTO response structure
 *
 * Fields: kind, blocks, layout, title, profile, schemaVersion
 */
interface PageSchemaDTO {
  pid: string;
  pageKey: string;
  modelCode: string | null;
  modelCategory: string | null;
  name: string;
  title: string | Record<string, string>;
  description: string;
  kind: string;
  commandCode?: string;
  blocks: any[];
  layout: Record<string, any>;
  profile: string;
  schemaVersion: number;
  metaInfo: Record<string, unknown>;
  isTemplate: boolean;
  extension?: Record<string, any>;
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
  const computePageKey = (): string => {
    if (options.pageKey) {
      return options.pageKey;
    }
    if (options.tableName && options.type) {
      return `${options.tableName}_${options.type}`;
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

      // Build UnifiedSchema from v2 PageSchemaDTO
      const pageSchemaDTO = result.data;
      if (!pageSchemaDTO) {
        throw new Error(`No schema found for pageKey: ${pageKey}`);
      }

      // Build raw schema from DTO — always use v2 structure
      const raw: Record<string, any> = {
        kind: pageSchemaDTO.kind,
        title: pageSchemaDTO.title,
        blocks: pageSchemaDTO.blocks || [],
        layout: pageSchemaDTO.layout || { type: 'stack' },
        profile: pageSchemaDTO.profile || 'admin',
        schemaVersion: pageSchemaDTO.schemaVersion,
        pageKey: pageSchemaDTO.pageKey,
        commandCode: pageSchemaDTO.commandCode,
        modelCode: pageSchemaDTO.modelCode,
        modelCategory: pageSchemaDTO.modelCategory,
        ...(pageSchemaDTO.extension?.dataSource && {
          dataSource: pageSchemaDTO.extension.dataSource,
        }),
        // Propagate extension so consumers (e.g. relatedPages) can read it
        ...(pageSchemaDTO.extension && { extension: pageSchemaDTO.extension }),
      };

      // Migrate to current schema version (throws on error)
      const merged = DslMigrator.migrate(raw) as unknown as UnifiedSchema;

      // Dev-mode DSL validation
      if (process.env.NODE_ENV === 'development') {
        import('~/framework/meta/validation/DslValidator')
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
