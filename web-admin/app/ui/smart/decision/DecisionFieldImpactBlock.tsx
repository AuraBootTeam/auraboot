import { useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';
import { DecisionFieldImpactPanel } from '~/shared/decision/ui/DecisionFieldImpactPanel';

interface DecisionFieldImpactBlockProps {
  block?: {
    props?: {
      initialFieldRef?: string;
      initialCurrentDataType?: string;
    };
    initialFieldRef?: string;
    initialCurrentDataType?: string;
  };
}

export function DecisionFieldImpactBlock({ block }: DecisionFieldImpactBlockProps) {
  const [searchParams] = useSearchParams();
  const api = useMemo(() => {
    const service = getApiService();
    const http: HttpClient = {
      get: <T,>(endpoint: string, params?: Record<string, unknown>) =>
        service.get<T>(endpoint, params),
      post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
      delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
    };
    return createDecisionApi(http);
  }, []);

  const initialFieldRef =
    searchParams.get('fieldRef') ??
    block?.props?.initialFieldRef ??
    block?.initialFieldRef;
  const initialCurrentDataType =
    searchParams.get('currentDataType') ??
    searchParams.get('dataType') ??
    block?.props?.initialCurrentDataType ??
    block?.initialCurrentDataType;

  return (
    <DecisionFieldImpactPanel
      api={api}
      initialFieldRef={initialFieldRef ?? undefined}
      initialCurrentDataType={initialCurrentDataType ?? undefined}
    />
  );
}

export default DecisionFieldImpactBlock;
