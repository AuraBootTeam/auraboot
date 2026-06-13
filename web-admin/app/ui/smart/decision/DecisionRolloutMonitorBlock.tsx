import { useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';
import { DecisionRolloutMonitor } from '~/shared/decision/ui/DecisionRolloutMonitor';

interface DecisionRolloutMonitorBlockProps {
  block?: {
    props?: {
      initialDecisionCode?: string;
    };
    initialDecisionCode?: string;
  };
}

export function DecisionRolloutMonitorBlock({ block }: DecisionRolloutMonitorBlockProps) {
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

  const initialDecisionCode =
    searchParams.get('decisionCode') ??
    searchParams.get('code') ??
    block?.props?.initialDecisionCode ??
    block?.initialDecisionCode;
  const initialBaselineVersion =
    searchParams.get('baselineVersion') ?? searchParams.get('baseline');
  const initialCandidateVersion =
    searchParams.get('candidateVersion') ?? searchParams.get('candidate');

  return (
    <DecisionRolloutMonitor
      api={api}
      initialDecisionCode={initialDecisionCode}
      initialBaselineVersion={initialBaselineVersion ?? undefined}
      initialCandidateVersion={initialCandidateVersion ?? undefined}
    />
  );
}

export default DecisionRolloutMonitorBlock;
