import { useMemo } from 'react';
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
    };
    targetType?: string;
    targetCode?: string;
  };
  runtime?: {
    getContext?: () => {
      record?: Record<string, unknown>;
      row?: Record<string, unknown>;
    };
  };
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

export function DecisionIntegrationImpactBlock({
  block,
  runtime,
}: DecisionIntegrationImpactBlockProps) {
  const [searchParams] = useSearchParams();
  const params = useParams();
  const api = useMemo(() => {
    const service = getApiService();
    const http: HttpClient = {
      get: <T,>(endpoint: string, query?: Record<string, unknown>) =>
        service.get<T>(endpoint, query),
      post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
      delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
    };
    return createDecisionApi(http);
  }, []);

  const context = runtime?.getContext?.();
  const record = context?.record ?? context?.row ?? {};
  const targetType =
    searchParams.get('targetType') ??
    block?.props?.targetType ??
    block?.targetType ??
    'CONNECTOR';
  const targetCode =
    searchParams.get('targetCode') ??
    block?.props?.targetCode ??
    block?.targetCode ??
    stringValue(record.pid) ??
    stringValue(record.code) ??
    stringValue(params.recordId);

  return (
    <DecisionIntegrationImpactPanel
      api={api}
      targetType={targetType}
      targetCode={targetCode ?? ''}
    />
  );
}

export default DecisionIntegrationImpactBlock;
