import { useEffect, useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';
import { DecisionIntegrationImpactPanel } from '~/shared/decision/ui/DecisionIntegrationImpactPanel';

interface DecisionIntegrationImpactBlockProps {
  block?: {
    props?: {
      targetType?: string;
      targetCode?: string;
      targetCodeField?: string;
      modelCode?: string;
    };
    targetType?: string;
    targetCode?: string;
    targetCodeField?: string;
    modelCode?: string;
  };
  runtime?: {
    getContext?: () => {
      record?: Record<string, unknown>;
      row?: Record<string, unknown>;
      data?: Record<string, unknown>;
      $page?: {
        modelCode?: string;
        pageKey?: string;
      };
    };
  };
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function snakeToCamel(value: string): string {
  return value.replace(/_([a-z])/g, (_, char: string) => char.toUpperCase());
}

function valueFromPath(record: Record<string, unknown>, path?: string): string | undefined {
  if (!path) return undefined;
  const candidates = Array.from(
    new Set([
      path,
      path
        .split('.')
        .map((part) => snakeToCamel(part))
        .join('.'),
      `data.${path}`,
      `data.${path
        .split('.')
        .map((part) => snakeToCamel(part))
        .join('.')}`,
    ]),
  );
  for (const candidate of candidates) {
    const value = candidate
      .split('.')
      .reduce<unknown>((current, key) => {
        if (current && typeof current === 'object' && key in current) {
          return (current as Record<string, unknown>)[key];
        }
        return undefined;
      }, record);
    const result = stringValue(value);
    if (result) return result;
  }
  return undefined;
}

export function DecisionIntegrationImpactBlock({
  block,
  runtime,
}: DecisionIntegrationImpactBlockProps) {
  const [searchParams] = useSearchParams();
  const params = useParams();
  const service = useMemo(() => getApiService(), []);
  const api = useMemo(() => {
    const http: HttpClient = {
      get: <T,>(endpoint: string, query?: Record<string, unknown>) =>
        service.get<T>(endpoint, query),
      post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
      delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
    };
    return createDecisionApi(http);
  }, [service]);

  const context = runtime?.getContext?.();
  const contextRecord = context?.record ?? context?.row ?? context?.data ?? {};
  const [resolvedRecord, setResolvedRecord] = useState<Record<string, unknown> | null>(null);
  const [recordLookupSettled, setRecordLookupSettled] = useState(false);
  const record = resolvedRecord ? { ...contextRecord, ...resolvedRecord } : contextRecord;
  const targetType =
    searchParams.get('targetType') ??
    block?.props?.targetType ??
    block?.targetType ??
    'CONNECTOR';
  const targetCodeField = block?.props?.targetCodeField ?? block?.targetCodeField;
  const fallbackModelCode = targetType === 'WEBHOOK' ? 'webhook' : undefined;
  const modelCode =
    stringValue(block?.props?.modelCode) ??
    stringValue(block?.modelCode) ??
    stringValue(context?.$page?.modelCode) ??
    stringValue(context?.$page?.pageKey) ??
    fallbackModelCode;
  const targetFromRecord = valueFromPath(record, targetCodeField);
  const detailRecordId =
    stringValue(params.recordId) ??
    stringValue(contextRecord.pid) ??
    stringValue(contextRecord.code);
  const shouldResolveRecordField =
    Boolean(targetCodeField) && !targetFromRecord && Boolean(modelCode) && Boolean(detailRecordId);

  useEffect(() => {
    setResolvedRecord(null);
    setRecordLookupSettled(false);
  }, [modelCode, detailRecordId, targetCodeField]);

  useEffect(() => {
    if (!shouldResolveRecordField || !modelCode || !detailRecordId) {
      setRecordLookupSettled(true);
      return;
    }
    let cancelled = false;
    service
      .get<Record<string, unknown>>(`/dynamic/${modelCode}/${detailRecordId}`)
      .then((response) => {
        if (!cancelled) {
          setResolvedRecord(response.data ?? null);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setResolvedRecord(null);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setRecordLookupSettled(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [modelCode, detailRecordId, service, shouldResolveRecordField]);

  const fallbackTargetCode =
    shouldResolveRecordField && !recordLookupSettled
      ? undefined
      : stringValue(record.pid) ?? stringValue(record.code) ?? detailRecordId;
  const targetCode =
    searchParams.get('targetCode') ??
    block?.props?.targetCode ??
    block?.targetCode ??
    targetFromRecord ??
    fallbackTargetCode;

  if (!targetCode && shouldResolveRecordField && !recordLookupSettled) {
    return (
      <section className="decision-integration-impact" data-testid="decision-integration-impact">
        <div className="decisionops-state">加载集成目标...</div>
      </section>
    );
  }

  return (
    <DecisionIntegrationImpactPanel
      api={api}
      targetType={targetType}
      targetCode={targetCode ?? ''}
    />
  );
}

export default DecisionIntegrationImpactBlock;
